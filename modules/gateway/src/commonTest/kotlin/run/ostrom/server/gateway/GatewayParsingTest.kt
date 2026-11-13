// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.ModelPrice
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.RequestBody
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.gateway.domain.TopLevelJson
import run.ostrom.server.gateway.domain.UsageMeter
import run.ostrom.server.gateway.domain.WireProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the gateway: the body scan, the usage meter, the prices. */
class GatewayParsingTest {
    private val keys = setOf("model", "stream", "metadata", "store")

    @Test
    fun theScanFindsTopLevelFieldsAndSkipsNestedOnes() {
        val text = """ { "messages" : [ {"model":"inner","x":"a\"}]"} , [1,{"y":2}] ], "model":"claude-opus-5",
            |"stream":true,"n":-1.5e3,"metadata":{"user_id":"u"},"nested":{"a":[{}]},"z":null } 
        """.trimMargin()
        val fields = assertNotNull(TopLevelJson.scan(text, keys))
        assertEquals(1, fields.open)
        assertEquals(7, fields.memberCount)
        assertEquals("claude-opus-5", TopLevelJson.string(text, fields.ranges["model"]))
        assertEquals(true, TopLevelJson.boolean(text, fields.ranges["stream"]))
        assertEquals("""{"user_id":"u"}""", fields.ranges["metadata"]?.let { text.substring(it) })
        assertNull(fields.ranges["store"])
        val escapedBackslash = """{"a":"b\\","c":"d\\\"e","model":"m"}"""
        assertEquals(
            "m",
            TopLevelJson.string(escapedBackslash, TopLevelJson.scan(escapedBackslash, keys)?.ranges?.get("model")),
        )
        assertEquals(TopLevelJson.Fields(0, emptyMap(), 0), TopLevelJson.scan("{}", keys))
        assertEquals(TopLevelJson.Fields(1, emptyMap(), 0), TopLevelJson.scan(" { } ", keys))
    }

    @Test
    fun malformedTextAndRepeatedWantedKeysAreRefused() {
        val malformed = listOf(
            "", "  ", "[]", "{", "{}x", "{} {}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "{\"a\":1 \"b\":2}", "{a:1}",
            "{\"a\":\"unterminated}", "{\"a\":\"ends with a backslash\\", "{\"a\":[1,2}", "{\"a\":{\"b\":\"x\"",
            "{\"a\":1", "{\"model\":\"a\",\"model\":\"b\"}",
            // Escaped keys the provider decodes to a wanted one: one model checked, another one used.
            "{\"model\":\"a\",\"mod\\u0065l\":\"b\"}", "{\"st\\u006fre\":true}", "{\"x\\\"y\":1}",
        )
        for (text in malformed) assertNull(TopLevelJson.scan(text, keys), text)
        assertNotNull(TopLevelJson.scan("{\"other\":1,\"other\":2}", keys), "Only wanted keys must be unique")
    }

    @Test
    fun valuesOfTheWrongTypeReadAsAbsent() {
        val text = """{"model":5,"stream":"yes","bad":"\x"}"""
        val fields = assertNotNull(TopLevelJson.scan(text, keys + "bad"))
        assertNull(TopLevelJson.string(text, fields.ranges["model"]))
        assertNull(TopLevelJson.string(text, fields.ranges["bad"]), "An invalid escape is no string")
        assertNull(TopLevelJson.string(text, null))
        assertNull(TopLevelJson.boolean(text, fields.ranges["stream"]))
        assertNull(TopLevelJson.boolean(text, null))
        val off = """{"stream":false}"""
        assertEquals(false, TopLevelJson.boolean(off, TopLevelJson.scan(off, keys)?.ranges?.get("stream")))
    }

    @Test
    fun aRewriteReplacesAndInsertsInOnePass() {
        val text = """{"b":1,"store":true,"a":2}"""
        val fields = assertNotNull(TopLevelJson.scan(text, keys + "a" + "b"))
        val replacements = mapOf(fields.ranges.getValue("a") to "20", fields.ranges.getValue("store") to "false")
        assertEquals(
            """{"x":1,"y":2,"b":1,"store":false,"a":20}""",
            TopLevelJson.rewrite(text, fields, replacements, listOf("\"x\":1", "\"y\":2")),
        )
        assertEquals(text, TopLevelJson.rewrite(text, fields, emptyMap(), emptyList()))
        val empty = assertNotNull(TopLevelJson.scan("{ }", keys))
        assertEquals("""{"x":1 }""", TopLevelJson.rewrite("{ }", empty, emptyMap(), listOf("\"x\":1")))
    }

    @Test
    fun aBodyNamesAValidModelOrGetsTheDefault() {
        val named = assertNotNull(RequestBody.read("""{"model":"gpt-5","stream":true}""", "default"))
        assertEquals("gpt-5", named.model)
        assertTrue(named.stream)
        assertEquals("""{"model":"gpt-5","stream":true}""", named.render())
        val defaulted = assertNotNull(RequestBody.read("""{"stream":false}""", "claude-opus-5"))
        assertEquals("claude-opus-5", defaulted.model)
        assertFalse(defaulted.stream)
        assertEquals("""{"model":"claude-opus-5","stream":false}""", defaulted.render())
        assertEquals(
            """{"model":"m","store":false}""",
            RequestBody.read("{}", "m")?.render(members = listOf("\"store\":false")),
        )
        assertEquals("RequestBody(m, 2 chars)", RequestBody.read("{}", "m").toString())
        for ((text, default) in listOf(
            "[]" to "m",
            "{}" to null,
            """{"model":null}""" to "m",
            """{"model":""}""" to null,
            """{"model":"has space"}""" to null,
            """{"model":"tab\u0001"}""" to null,
            """{"model":"${"m".repeat(RequestBody.MAX_MODEL + 1)}"}""" to null,
        )) {
            assertNull(RequestBody.read(text, default), text)
        }
        assertNotNull(RequestBody.read("""{"model":"${"m".repeat(RequestBody.MAX_MODEL)}"}""", null))
    }

    @Test
    fun theMeterReadsAnthropicEventsWithoutKeepingTheText() {
        val meter = UsageMeter(WireProtocol.MESSAGES)
        assertNull(meter.usage)
        val events = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":120,"cache_read_input_tokens":4000,"cache_creation_input_tokens":30,"output_tokens":1}}}

            data: {"type":"content_block_delta","delta":{"text":"the usage of words"}}
            data: not json but usage
            data: {"type":"ping"}
            dummy: a usage line that is no data
            data: ["usage"]
            data: {"type":"message_delta","usage":{"output_tokens":512}}
            data: {"type":"message_delta","usage":{"output_tokens":-3,"input_tokens":1e999}}
            data: {"type":"message_delta","usage":{"output_tokens":100000001}}
            data: {"type":"message_start","message":{"id":"m"},"usage":{}}
            d
        """.trimIndent().encodeToByteArray()
        // Fed in small slices: lines cross chunks.
        events.toList().chunked(7).forEach { meter.accept(it.toByteArray(), it.size) }
        meter.accept(byteArrayOf(10), 1)
        assertEquals(TokenUsage(input = 120, output = 512, cacheRead = 4000, cacheWrite = 30), meter.usage)
    }

    @Test
    fun theMeterReadsOpenAiShapesStreamedOrWhole() {
        val responses = UsageMeter(WireProtocol.RESPONSES)
        val completed = "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":100," +
            "\"input_tokens_details\":{\"cached_tokens\":300},\"output_tokens\":7}}}\n"
        responses.accept(completed.encodeToByteArray(), completed.length)
        assertEquals(TokenUsage(input = 0, output = 7, cacheRead = 100), responses.usage, "Cached never above total")
        val whole = UsageMeter(WireProtocol.RESPONSES)
        whole.acceptBody("""{"usage":{"input_tokens":50,"output_tokens":5}}""".encodeToByteArray())
        assertEquals(TokenUsage(input = 50, output = 5), whole.usage)
        val noInput = UsageMeter(WireProtocol.RESPONSES)
        noInput.acceptBody("""{"usage":{"output_tokens":5}}""".encodeToByteArray())
        noInput.acceptBody("""{"response":{}}""".encodeToByteArray())
        noInput.acceptBody("not json".encodeToByteArray())
        assertNull(noInput.usage)

        val chat = UsageMeter(WireProtocol.CHAT)
        chat.acceptBody(
            """{"usage":{"prompt_tokens":80,"prompt_tokens_details":{"cached_tokens":20},"completion_tokens":9}}"""
                .encodeToByteArray(),
        )
        assertEquals(TokenUsage(input = 60, output = 9, cacheRead = 20), chat.usage)
        val chatNoOutput = UsageMeter(WireProtocol.CHAT)
        chatNoOutput.acceptBody("""{"usage":{"prompt_tokens":8}}""".encodeToByteArray())
        chatNoOutput.acceptBody("""{"choices":[]}""".encodeToByteArray())
        assertEquals(TokenUsage(input = 8), chatNoOutput.usage)
        val noCached = UsageMeter(WireProtocol.CHAT)
        noCached.acceptBody(
            """{"usage":{"prompt_tokens":8,"prompt_tokens_details":{},"completion_tokens":1}}""".encodeToByteArray(),
        )
        assertEquals(TokenUsage(input = 8, output = 1), noCached.usage, "Details without a cached count")
    }

    @Test
    fun aLineTooLongIsDroppedAndTheNextOneRead() {
        val meter = UsageMeter(WireProtocol.CHAT)
        val long = ("data: {\"usage\":{\"prompt_tokens\":1},\"pad\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}\n")
            .encodeToByteArray()
        // Fed in two slices: the second goes on dropping the line the first outgrew.
        meter.accept(long, long.size - 10)
        meter.accept(long.copyOfRange(long.size - 10, long.size), 10)
        assertNull(meter.usage, "A line over 8 MiB is never parsed")
        val next = "data: {\"usage\":{\"prompt_tokens\":2}}\n".encodeToByteArray()
        meter.accept(next, next.size)
        assertEquals(TokenUsage(input = 2), meter.usage)
        val wide = (
            "data: {\"pad\":\"" + "y".repeat(
                4096,
            ) + "\",\"usage\":{\"prompt_tokens\":3}}\n"
            ).encodeToByteArray()
        wide.toList().chunked(1000).forEach { meter.accept(it.toByteArray(), it.size) }
        assertEquals(TokenUsage(input = 3), meter.usage, "A line longer than the first buffer grows it")
        val completed = (
            "data: {\"type\":\"response.completed\",\"response\":{\"output\":\"" + "z".repeat(600 * 1024) +
                "\",\"usage\":{\"input_tokens\":9,\"output_tokens\":4}}}\n"
            ).encodeToByteArray()
        val responses = UsageMeter(WireProtocol.RESPONSES)
        responses.accept(completed, completed.size)
        assertEquals(TokenUsage(input = 9, output = 4), responses.usage, "A whole response on one line is metered")
    }

    @Test
    fun pricesPreferTheExactModelThenTheLongestPrefix() {
        val usage = TokenUsage(input = 1_000_000, output = 1_000_000, cacheRead = 1_000_000, cacheWrite = 1_000_000)
        val table = PriceTable()
        assertEquals(
            4_000_000L + 5_000_000 + 200_000 + 20_000_000,
            table.costMicros(InferenceProvider.CLAUDE_SHARED, "Claude-Opus-5-5-20261001", usage),
        )
        assertEquals(
            5_000_000L + 6_250_000 + 500_000 + 25_000_000,
            table.costMicros(InferenceProvider.ANTHROPIC, "claude-opus-5", usage),
        )
        assertNull(table.costMicros(InferenceProvider.OPENAI, "claude-opus-5", usage), "No price across families")
        assertNull(table.costMicros(InferenceProvider.ANTHROPIC, "claude-unknown", usage))
        val custom =
            PriceTable(
                listOf(ModelPrice("openai", "gpt-5", 1, 0, 0, 3), ModelPrice("openai", "gpt-5-mini", 2, 0, 0, 0)),
            )
        assertEquals(
            4L,
            custom.costMicros(
                InferenceProvider.CODEX_SHARED,
                "gpt-5",
                TokenUsage(input = 1_000_000, output = 1_000_000),
            ),
        )
        assertEquals(2L, custom.costMicros(InferenceProvider.OPENAI, "gpt-5-mini", TokenUsage(input = 1_000_000)))
        assertEquals(0L, custom.costMicros(InferenceProvider.OPENAI, "gpt-5", TokenUsage(input = 1)))
    }
}
