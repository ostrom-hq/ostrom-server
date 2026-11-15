// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The limits the server enforces, as its OpenAPI document must show them (second security audit of the contract,
 * 03/11/2026, findings F4 and F5; this replaces ostrom-core's `check-limits.mjs`, which read the hand-written YAML): a
 * page size with its bounds, a route the server rate-limits with its 429, a costly read it throttles with its 429 and
 * `Retry-After`, and a governance write a person's API token never applies with its 202 and its 429. A route that gets
 * a new limit joins its list here.
 */
object ApiLimits {
    /** Bounded per caller by ReadThrottle (kernel `http/ReadThrottle.kt`), which answers 429 with `Retry-After`. */
    val THROTTLED = listOf(
        "getGate", "listApprovals", "listAudit", "listSessions", "getSession", "listReceipts", "getReceipt",
        "verifyReceipt", "getSpending", "listActivity",
    )

    /**
     * Limited per address, person, organization or automation (the identity module's RateLimiter, the
     * organizations', the automations' and the usage ledger's), which answer 429 `rate_limited`.
     */
    val LIMITED = listOf(
        "mobileToken", "unlockMobileApp", "startDeviceLogin", "getDeviceLogin", "answerDeviceLogin", "launcherLogin",
        "requestSignInEmail", "exchangeSignInLink", "createSignInLink", "linkSignInProvider", "createOrg",
        "triggerAutomation", "recordUsageEvents",
    )

    /**
     * The governance writes a person's API token never applies: each opens a confirmation (202), at most 10 waiting
     * per person (429 `confirmation_limit`); the invitations are also limited per organization. `setBudget`, in
     * core's list, is not held by the server (nor by TeamCode), so it is not here.
     */
    val HELD = listOf(
        "setGatePolicy", "createRule", "updateRule", "deleteRule", "bulkUpdateRules", "createWorkflow", "saveWorkflow",
        "deleteWorkflow", "setModelPolicy", "updateOrgMember", "removeOrgMember", "inviteOrgMembers",
        "createInvitationLink",
    )

    private const val PENDING_SCHEMA = "#/components/schemas/GovernanceConfirmationPending"
    private val METHODS = setOf("get", "put", "post", "delete", "patch")

    /** The problems of [document], empty when every limit is shown. */
    fun problems(document: JsonObject): List<String> {
        val operations = operations(document)
        val problems = mutableListOf<String>()
        operations.forEach { (id, operation) -> problems += pageProblems(id, operation) }
        LIMITED.forEach { id -> problems += tooMany(operations, id) }
        THROTTLED.forEach { id ->
            problems += tooMany(operations, id)
            val answer = responses(operations[id])?.get("429")?.jsonObject
            if (answer != null && answer["headers"]?.jsonObject?.containsKey("Retry-After") != true) {
                problems += "$id: its 429 has no Retry-After"
            }
        }
        HELD.forEach { id ->
            problems += tooMany(operations, id)
            if (operations[id] != null && !isPending(operations.getValue(id))) {
                problems += "$id: a token's write is held, but no 202 GovernanceConfirmationPending"
            }
        }
        operations.filter { (id, operation) -> id !in HELD && isPending(operation) }.keys.forEach { id ->
            problems += "$id: a token's write is held (202), but it is not in HELD"
        }
        return problems
    }

    private fun operations(document: JsonObject): Map<String, JsonObject> =
        document.getValue("paths").jsonObject.values.flatMap { item ->
            item.jsonObject.filterKeys { it in METHODS }.values.map { it.jsonObject }
        }.associateBy { it.getValue("operationId").jsonPrimitive.content }

    private fun pageProblems(id: String, operation: JsonObject): List<String> {
        val limits = operation["parameters"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.text("in") == "query" && it.text("name") == "limit" }
        return limits.flatMap { parameter ->
            val schema = parameter["schema"]?.jsonObject.orEmpty()
            listOfNotNull(
                "$id: the limit parameter has no minimum".takeUnless { "minimum" in schema },
                "$id: the limit parameter has no maximum".takeUnless { "maximum" in schema },
            )
        }
    }

    private fun tooMany(operations: Map<String, JsonObject>, id: String): List<String> = when {
        id !in operations -> listOf("$id: no such operation")
        responses(operations[id])?.containsKey("429") != true -> listOf("$id: no 429")
        else -> emptyList()
    }

    private fun isPending(operation: JsonObject): Boolean =
        responses(operation)?.get("202")?.jsonObject?.get("content")?.jsonObject?.values?.any { media ->
            media.jsonObject["schema"]?.jsonObject?.text("\$ref") == PENDING_SCHEMA
        } == true

    private fun responses(operation: JsonObject?): JsonObject? = operation?.get("responses")?.jsonObject

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content
}
