// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.PathStep
import run.ostrom.server.automations.domain.PayloadPaths
import run.ostrom.server.automations.domain.PayloadProblem
import run.ostrom.server.automations.domain.Prompts
import run.ostrom.server.automations.domain.VariableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PayloadsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun pathsReadKeysQuotedKeysAndIndexes() {
        assertEquals(emptyList(), PayloadPaths.parse("$"))
        assertEquals(
            listOf(PathStep.Key("a"), PathStep.Key("key with spaces"), PathStep.Index(2), PathStep.Key("b-c_d")),
            PayloadPaths.parse("$.a['key with spaces'][2].b-c_d"),
        )
        val payload = json("""{"a":{"key with spaces":[0,1,{"b-c_d":"x"}]}}""")
        val found = PayloadPaths.read(payload, PayloadPaths.parse("$.a['key with spaces'][2].b-c_d"))
        assertEquals("\"x\"", found.toString())
        assertNull(PayloadPaths.read(payload, PayloadPaths.parse("$.a.missing")))
        assertNull(PayloadPaths.read(payload, PayloadPaths.parse("$[0]")))
        assertNull(PayloadPaths.read(payload, PayloadPaths.parse("$.a['key with spaces'][9]")))
    }

    @Test
    fun malformedOrTooLongPathsAreRefused() {
        for (path in listOf("a.b", "$.", "$..a", "$[x]", "$['']", "$['a", "$.a b")) {
            assertFailsWith<InvalidAutomationException>(path) { PayloadPaths.parse(path) }
        }
        assertEquals(16, PayloadPaths.parse("$" + ".a".repeat(16)).size)
        assertFailsWith<InvalidAutomationException> { PayloadPaths.parse("$" + ".a".repeat(17)) }
    }

    @Test
    fun variablesAreTypedAndChecked() {
        val payload = json(
            """{"s":"text","i":42,"is":"42","n":1.5,"b":true,"bs":"true","e":"high","o":{},"z":null,""" +
                """"long":"abcdef","big":1e999}""",
        )
        val read = Prompts.read(payload, typedVariables())
        assertEquals(
            mapOf(
                "s" to "text",
                "i" to "42",
                "int" to "42",
                "n" to "1.5",
                "b" to "true",
                "e" to "high",
                "optional" to "",
            ),
            read.values,
        )
        assertEquals(
            listOf(
                PayloadProblem("$.is", "type:int"),
                PayloadProblem("$.n", "type:int"),
                PayloadProblem("$.s", "type:number"),
                PayloadProblem("$.b", "type:number"),
                PayloadProblem("$.big", "type:number"),
                PayloadProblem("$.bs", "type:boolean"),
                PayloadProblem("$.i", "type:boolean"),
                PayloadProblem("$.s", "enum"),
                PayloadProblem("$.o", "not_scalar"),
                PayloadProblem("$.z", "required"),
                PayloadProblem("$.missing", "required"),
                PayloadProblem("$.long", "max_length"),
            ),
            read.problems,
        )
    }

    @Test
    fun aPromptIsRenderedOnceWithoutRescanningValues() {
        val prompt = "Ticket {{ ticket }} by {{run.id}}, {{absent}}, {{ticket}}."
        assertEquals(setOf("ticket", "run.id", "absent"), Prompts.placeholders(prompt))
        assertEquals(
            "Ticket {{run.id}} by 7, , {{run.id}}.",
            Prompts.render(prompt, mapOf("ticket" to "{{run.id}}", "run.id" to "7")),
        )
        assertEquals("no {placeholder} here", Prompts.render("no {placeholder} here", emptyMap()))
    }

    private fun variable(path: String, type: VariableType, required: Boolean = true, maxLength: Int = 256) =
        AutomationVariable(path.removePrefix("$."), path, type, listOf("low", "high"), maxLength, required)

    private fun typedVariables() = listOf(
        variable("$.s", VariableType.STRING),
        variable("$.i", VariableType.STRING),
        variable("$.i", VariableType.INT).copy(name = "int"),
        variable("$.is", VariableType.INT),
        variable("$.n", VariableType.INT).copy(name = "nAsInt"),
        variable("$.n", VariableType.NUMBER),
        variable("$.s", VariableType.NUMBER).copy(name = "notNumber"),
        variable("$.b", VariableType.NUMBER).copy(name = "bAsNumber"),
        variable("$.big", VariableType.NUMBER),
        variable("$.b", VariableType.BOOLEAN),
        variable("$.bs", VariableType.BOOLEAN),
        variable("$.i", VariableType.BOOLEAN).copy(name = "iAsBoolean"),
        variable("$.e", VariableType.ENUM),
        variable("$.s", VariableType.ENUM).copy(name = "notEnum"),
        variable("$.o", VariableType.STRING),
        variable("$.z", VariableType.STRING),
        variable("$.missing", VariableType.STRING),
        variable("$.optional", VariableType.STRING, required = false),
        variable("$.long", VariableType.STRING, maxLength = 3),
    )
}
