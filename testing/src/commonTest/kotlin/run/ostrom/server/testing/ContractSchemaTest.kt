// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ContractSchema] catches each way an answer can drift from the contract, on a small schema written here. */
class ContractSchemaTest {
    private val contract = ContractSchema(
        Json.parseToJsonElement(
            """
            {"openapi": "3.1.0", "components": {"schemas": {
              "Catalog": {
                "type": "object",
                "properties": {
                  "actions": {"type": "array", "items": {"${'$'}ref": "#/components/schemas/Action"}},
                  "failMode": {"type": "string", "enum": ["closed"]},
                  "waitSeconds": {"type": "integer"},
                  "labels": {"type": "object", "additionalProperties": {"type": "string"}},
                  "extra": {"type": "object", "additionalProperties": true},
                  "closed": {"type": "object", "additionalProperties": false},
                  "both": {"allOf": [{"${'$'}ref": "#/components/schemas/Ref"}, {"type": "object"}]},
                  "odd": {"type": "file"}
                },
                "required": ["actions"]
              },
              "Action": {
                "type": "object",
                "properties": {
                  "class": {"type": "string"},
                  "enforce": {"type": "boolean"},
                  "weight": {"type": ["number", "null"]},
                  "gate": {"oneOf": [{"${'$'}ref": "#/components/schemas/Ref"}, {"type": "null"}]},
                  "loose": {"description": "any value"}
                },
                "required": ["class"]
              },
              "Ref": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
              "Twice": {"oneOf": [{"type": "string"}, {"type": "string"}]},
              "Check": {"oneOf": [
                {"type": "object", "required": ["type"],
                  "properties": {"type": {"type": "string", "const": "pr_merged"}}},
                {"type": "object", "required": ["type"],
                  "properties": {"type": {"type": "string", "const": "attested"}}}
              ]},
              "Either": {"anyOf": [{"type": "string"}, {"type": "string"}, {"type": "integer"}]}
            }}}
            """,
        ).jsonObject,
    )

    private fun violations(json: String) = contract.violations("Catalog", Json.parseToJsonElement(json))

    @Test
    fun anAnswerThatFollowsTheContractMatches() {
        val answer = """
            {"actions": [
              {"class": "push-main", "enforce": true, "weight": 1.5, "gate": {"id": "g"}, "loose": [1]},
              {"class": "deploy", "weight": null, "gate": null}
            ], "failMode": "closed", "waitSeconds": 300, "labels": {"a": "b"}, "extra": {"any": 1},
              "closed": {}, "both": {"id": "r"}}
        """
        assertEquals(emptyList(), violations(answer))
        contract.assertMatches("Catalog", Json.parseToJsonElement(answer))
    }

    @Test
    fun eachDriftIsNamedWithItsPathAndKind() {
        val found = violations(
            """
            {"actions": [
              {"enforce": "yes", "weight": "1", "gate": {"name": "g"}, "repos": []},
              {"class": 3, "gate": 1, "enforce": null, "weight": []}
            ], "failMode": "open", "waitSeconds": 1.5, "labels": {"a": 1, "b": {}}, "closed": {"x": 1}, "both": {},
              "odd": "x", "keywords": []}
            """,
        )
        val expected = listOf(
            "$.actions[0].class MISSING",
            "$.actions[0].enforce TYPE",
            "$.actions[0].weight TYPE",
            "$.actions[0].gate TYPE",
            "$.actions[0].repos UNDECLARED",
            "$.actions[1].class TYPE",
            "$.actions[1].gate TYPE",
            "$.actions[1].enforce TYPE",
            "$.actions[1].weight TYPE",
            "$.failMode TYPE",
            "$.waitSeconds TYPE",
            "$.labels.a TYPE",
            "$.labels.b TYPE",
            "$.closed.x UNDECLARED",
            "$.both.id MISSING",
            "$.odd TYPE",
            "$.keywords UNDECLARED",
        )
        val shown = found.map { "${it.path} ${it.kind}" }
        for (line in expected) assertTrue(line in shown, "$line in\n${found.joinToString("\n")}")
        assertEquals(expected.size, found.size, found.joinToString("\n"))
        assertEquals("$.actions[*].class MISSING", found.first { it.kind == DriftKind.MISSING }.key)
        val failMode = found.first { it.path == "$.failMode" }
        assertEquals("$.failMode: \"open\" is not one of [\"closed\"]", failMode.toString())
        assertEquals("$.actions MISSING", violations("""{"labels": {}}""").single().key)
        assertEquals("$.waitSeconds TYPE", violations("""{"actions": [], "waitSeconds": "300"}""").single().key)
        assertEquals("$: [] is not object", violations("[]").single().toString())
    }

    @Test
    fun aValueTwoBranchesAcceptIsRefused() {
        val twice = contract.violations("Twice", Json.parseToJsonElement("\"x\""))
        assertEquals("$: matches more than one branch of oneOf", twice.single().toString())
    }

    @Test
    fun aConstTellsTheBranchesOfAOneOfApart() {
        assertEquals(emptyList(), contract.violations("Check", Json.parseToJsonElement("""{"type": "attested"}""")))
        val other = contract.violations("Check", Json.parseToJsonElement("""{"type": "command"}""")).single()
        assertTrue(other.toString().startsWith("$: matches no branch of oneOf"), other.toString())
    }

    @Test
    fun anyOfTakesOneBranchOrMore() {
        assertEquals(emptyList(), contract.violations("Either", Json.parseToJsonElement("\"x\"")), "Two branches")
        assertEquals(emptyList(), contract.violations("Either", Json.parseToJsonElement("1")), "One branch")
        val none = contract.violations("Either", Json.parseToJsonElement("true")).single()
        assertTrue(none.toString().startsWith("$: matches no branch of anyOf"), none.toString())
    }

    @Test
    fun aReferenceToNothingFails() {
        assertFailsWith<IllegalStateException> { contract.violations("Missing", JsonObject(emptyMap())) }
        assertFailsWith<IllegalStateException> { contract.resolve("#/openapi/version") }
        assertEquals(setOf("type", "properties", "required"), contract.resolve("#/components/schemas/Ref").keys)
    }

    @Test
    fun aMismatchFailsTheTestWithEveryDifference() {
        val failure = assertFailsWith<AssertionError> {
            contract.assertMatches("Catalog", Json.parseToJsonElement("{}"))
        }
        assertTrue("does not match the contract's Catalog" in failure.message.orEmpty())
        assertTrue("$.actions: required by the contract" in failure.message.orEmpty())
    }
}
