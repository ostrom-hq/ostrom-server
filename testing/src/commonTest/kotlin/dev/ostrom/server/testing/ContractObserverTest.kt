// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.MapConfigSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** [ContractObserver] checks every answer of a server against the contract, and [Contract.from] finds the contract. */
class ContractObserverTest {
    // A server whose answers follow the small contract of ContractFixtures, or not, one way per route.
    private fun ApplicationTestBuilder.things(observer: ContractObserver) = application {
        install(observer.plugin)
        routing {
            get("/api/v1/things") { list(call) }
            get("/api/v1/things/{id}") {
                when (call.parameters["id"]) {
                    "a" -> call.respondText("""{"id": "a"}""", json)
                    "drift" -> call.respondText("""{"name": 1}""", json)
                    "teapot" -> call.respondText("{}", json, HttpStatusCode(418, "Teapot"))
                    "crash" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.InternalServerError)
                    "crash-bare" -> call.respondText("{}", json, HttpStatusCode.InternalServerError)
                    "accepted" -> call.respondText("{}", json, HttpStatusCode.Accepted)
                    "text" -> call.respondText("a")
                    "broken" -> call.respondText("{not json", json)
                    else -> call.respondText("", json)
                }
            }
            post("/api/v1/things") {
                when (call.request.queryParameters["as"]) {
                    "json" -> call.respondText("{}", json, HttpStatusCode.NoContent)
                    "empty" -> call.respond(TextContent("", json, HttpStatusCode.NoContent))
                    "error" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.BadRequest)
                    else -> call.respond(HttpStatusCode.NoContent)
                }
            }
            get("/api/v1/things/export") { export(call) }
            get("/api/v1/things/stream") { stream(call) }
            delete("/api/v1/things/{id}") { remove(call) }
            get("/streamed/elsewhere") { call.respondBytesWriter(json) { writeStringUtf8("""{"x": 1}""") } }
            get("/health") { call.respondText("""{"x": 1}""", json) }
        }
    }

    private fun report(directory: Path): String = SystemFileSystem.list(directory).single().let { file ->
        SystemFileSystem.source(file).buffered().use { it.readString() }
    }

    @Test
    fun eachDriftFailsUnlessKnownAndWhatWasSeenIsWritten() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(ContractFixtures.contract, setOf("getThing 418 $ STATUS"), reports.toString())
        things(observer)
        client.get("/api/v1/things")
        val things = listOf("a", "drift", "teapot", "crash", "crash-bare", "text", "broken", "empty", "accepted")
        things.forEach { client.get("/api/v1/things/$it") }
        listOf("", "?as=json", "?as=empty", "?as=error").forEach { client.post("/api/v1/things$it") }
        client.get("/api/v1/things/export")
        client.delete("/api/v1/things/a")
        client.get("/health")

        val failure = assertFailsWith<AssertionError> { observer.finish() }.message.orEmpty()
        val expected = listOf(
            "getThing 200 $.id MISSING",
            "getThing 200 $.name UNDECLARED",
            "getThing 500 $.error MISSING",
            "getThing 200 $ BODY",
            "getThing 202 $ STATUS",
            "createThing 204 $ BODY",
        )
        assertEquals(expected, failure.lines().drop(1).map { it.substringBefore("  (") }, failure)
        val written = report(reports)
        assertFalse("a body that is not JSON" in failure, "One line per drift: the first answer's")
        assertTrue("a body that is not JSON" in written, "Every answer's drift is written")
        val lines = written.lines()
        assertTrue("seen listThings" in lines && "seen getThing" in lines, written)
        assertFalse(lines.any { it.startsWith("seen createThing") }, "A 204 is no JSON success")
        assertTrue(lines.any { it.startsWith("drift getThing 418 $ STATUS | ") }, "A known drift is written too")
        assertTrue(lines.any { "no JSON body where the contract declares one" in it })
        assertTrue(lines.any { "JSON where the contract declares none" in it })
    }

    @Test
    fun answersThatFollowTheContractPass() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(ContractFixtures.contract, emptySet(), reports.toString())
        things(observer)
        client.get("/api/v1/things")
        // A 404 that declares plain text and JSON: either is one of its forms.
        listOf("missing", "missing-json").forEach { client.get("/api/v1/things/export?as=$it") }
        observer.finish()
        assertEquals("seen listThings\n", report(reports))
    }

    @Test
    fun aStreamedAnswerIsReadToItsEndAndEachEventChecked() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(ContractFixtures.contract, emptySet(), reports.toString())
        things(observer)
        assertEquals("data: [DONE]", client.get("/api/v1/things/stream").bodyAsText().lines().dropLast(2).last())
        assertEquals("""{"items": [{"id": "a"}]}""", client.get("/api/v1/things?as=streamed").bodyAsText())
        client.get("/api/v1/things/stream?as=json")
        // A status that declares events only: they are read too, and this one lacks its id.
        client.get("/api/v1/things/stream?as=accepted")
        listOf("drift", "teapot").forEach { client.get("/api/v1/things/stream?as=$it") }
        client.get("/api/v1/things?as=events")
        client.delete("/api/v1/things/a?as=streamed")
        client.get("/streamed/elsewhere")

        val failure = assertFailsWith<AssertionError> { observer.finish() }.message.orEmpty()
        val expected = listOf(
            "streamThings 202 $.id MISSING",
            "streamThings 200 $.id MISSING",
            "streamThings 200 $.name UNDECLARED",
            "streamThings 200 $ BODY",
            "streamThings 418 $ STATUS",
            "listThings 200 $ BODY",
        )
        assertEquals(expected, failure.lines().drop(1).map { it.substringBefore("  (") }, failure)
        assertTrue("a data: that is not JSON" in failure, failure)
        val seen = report(reports).lines().filter { it.startsWith("seen") }.sorted()
        assertEquals(listOf("seen listThings", "seen streamThings"), seen, "Each answer is read to its end")
    }

    @Test
    fun anUpgradeIsNeitherCopiedNorObserved() {
        val upgrade = object : OutgoingContent.ProtocolUpgrade() {
            override suspend fun upgrade(
                input: ByteReadChannel,
                output: ByteWriteChannel,
                engineContext: CoroutineContext,
                userContext: CoroutineContext,
            ): Job = Job()
        }
        assertSame(upgrade, copied(upgrade, CoroutineScope(Job())) { fail("Never observed") })
    }

    @Test
    fun withoutAContractNothingIsWatchedNorWritten() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(null, emptySet(), reports.toString())
        things(observer)
        client.get("/api/v1/things/drift")
        client.get("/api/v1/things/stream?as=drift")
        client.get("/api/v1/things?as=streamed")
        observer.finish()
        assertTrue(SystemFileSystem.list(reports).isEmpty())
        ContractObserver(null).finish()
    }

    @Test
    fun theContractComesFromTheEnvironmentReadOncePerFile() {
        assertNull(Contract.from(MapConfigSource(emptyMap())), "Skipped without a contract")
        val error = assertFailsWith<IllegalStateException> {
            Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_REQUIRED" to "true")))
        }
        assertEquals("OSTROM_CONTRACT_REQUIRED is true but OSTROM_CONTRACT_OPENAPI is not set.", error.message)
        val file = ContractFixtures.file()
        val first = Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_OPENAPI" to file)))
        assertEquals(ContractFixtures.contract.operations.all, first?.operations?.all)
        assertSame(first, Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_OPENAPI" to file))), "Read once")
        val other = Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_OPENAPI" to ContractFixtures.file())))
        assertTrue(other !== first, "Another file is read")
    }
}

private val json = ContentType.Application.Json

// The list, in one piece (its status set on the response, not the content), written as it goes (a
// WriteChannelContent), or as events where its contract declares none.
private suspend fun list(call: RoutingCall) = when (call.request.queryParameters["as"]) {
    "streamed" -> call.respondBytesWriter(json) { writeStringUtf8("""{"items": [{"id": "a"}]}""") }
    "events" -> call.respond(events("data: {}\n\n"))
    else -> {
        call.response.status(HttpStatusCode.OK)
        call.respondText("""{"items": [{"id": "a"}]}""", json)
    }
}

// A planned operation, in one piece or written as it goes: neither is checked.
private suspend fun remove(call: RoutingCall) = when (call.request.queryParameters["as"]) {
    "streamed" -> call.respondBytesWriter(json) { writeStringUtf8("""{"x": 1}""") }
    else -> call.respondText("""{"x": 1}""", json)
}

// A Thing as JSON, or as server-sent events from a channel (a ReadChannelContent) whose each `data:` follows Thing,
// or not, or with a status the contract does not declare.
private suspend fun stream(call: RoutingCall) = when (call.request.queryParameters["as"]) {
    "json" -> call.respondText("""{"id": "a"}""", json)
    "drift" -> call.respond(events("data: {\"name\": 1}\n\ndata: {oops\n\n"))
    "teapot" -> call.respond(events("data: {}\n\n", HttpStatusCode(418, "Teapot")))
    "accepted" -> call.respond(events("data: {}\n\n", HttpStatusCode.Accepted))
    else -> call.respond(events("event: thing\ndata: {\"id\": \"a\"}\n\n: heartbeat\n\ndata: [DONE]\n\n"))
}

private fun events(text: String, code: HttpStatusCode? = null) = object : OutgoingContent.ReadChannelContent() {
    override val contentType = ContentType.Text.EventStream
    override val status = code

    override fun readFrom() = ByteReadChannel(text)
}

// The export: a CSV, or a 404 in plain text or in JSON, which the contract both declares.
private suspend fun export(call: RoutingCall) = when (call.request.queryParameters["as"]) {
    "missing" -> call.respondText("Not found", status = HttpStatusCode.NotFound)
    "missing-json" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.NotFound)
    else -> call.respondBytesWriter(ContentType.Text.CSV) { writeStringUtf8("a") }
}
