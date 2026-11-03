// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import kotlinx.serialization.json.Json
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
            {
              "Catalog": {
                "type": "object",
                "properties": {
                  "actions": {"type": "array", "items": {"${'$'}ref": "#/components/schemas/Action"}},
                  "failMode": {"type": "string", "enum": ["closed"]},
                  "waitSeconds": {"type": "integer"},
                  "labels": {"type": "object", "additionalProperties": {"type": "string"}},
                  "extra": {"type": "object", "additionalProperties": true},
                  "both": {"allOf": [{"${'$'}ref": "#/components/schemas/Ref"}, {"type": "object"}]}
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
              "Twice": {"oneOf": [{"type": "string"}, {"type": "string"}]}
            }
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
              "both": {"id": "r"}}
        """
        assertEquals(emptyList(), violations(answer))
        contract.assertMatches("Catalog", Json.parseToJsonElement(answer))
    }

    @Test
    fun eachDriftIsNamedWithItsPath() {
        val found = violations(
            """
            {"actions": [
              {"enforce": "yes", "weight": "1", "gate": {"name": "g"}, "repos": []},
              {"class": 3, "gate": 1}
            ], "failMode": "open", "waitSeconds": 1.5, "labels": {"a": 1}, "both": {}, "keywords": []}
            """,
        )
        val expected = listOf(
            "$.actions[0].class: required",
            "$.actions[0].enforce:",
            "$.actions[0].weight:",
            "$.actions[0].gate: matches no branch",
            "$.actions[0].repos: sent by the server, not declared",
            "$.actions[1].class:",
            "$.actions[1].gate: matches no branch",
            "$.failMode: \"open\" is not one of",
            "$.waitSeconds:",
            "$.labels.a:",
            "$.both.id: required",
            "$.keywords: sent by the server, not declared",
        )
        for (line in expected) assertTrue(found.any { it.startsWith(line) }, "$line in\n${found.joinToString("\n")}")
        assertTrue(violations("""{"labels": {}}""").single().startsWith("$.actions: required"))
        assertTrue(violations("""[]""").single().startsWith("$: [] is not object"))
    }

    @Test
    fun aValueTwoBranchesAcceptIsRefusedAndAnUnknownSchemaFails() {
        val twice = contract.violations("Twice", Json.parseToJsonElement("\"x\""))
        assertEquals(listOf("$: matches more than one branch of oneOf"), twice)
        assertFailsWith<IllegalStateException> { contract.violations("Missing", Json.parseToJsonElement("{}")) }
        val failure = assertFailsWith<AssertionError> {
            contract.assertMatches("Catalog", Json.parseToJsonElement("{}"))
        }
        assertTrue("does not match the contract's Catalog" in failure.message.orEmpty())
    }
}
