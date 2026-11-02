// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.GovernancePreview
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.rules.domain.models.Decider
import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.RuleNotFoundException
import dev.ostrom.server.rules.domain.usecases.BulkUpdateRulesUseCase
import dev.ostrom.server.rules.domain.usecases.CreateRuleUseCase
import dev.ostrom.server.rules.domain.usecases.DeleteRuleUseCase
import dev.ostrom.server.rules.domain.usecases.GetGatePolicyUseCase
import dev.ostrom.server.rules.domain.usecases.GetRuleUseCase
import dev.ostrom.server.rules.domain.usecases.SetGatePolicyUseCase
import dev.ostrom.server.rules.domain.usecases.UpdateRuleUseCase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * The governance writes of the rules module, held for a confirmation when a token asks (audit CLI H1): a rule created,
 * edited or decided, deleted, decided in bulk, and the gate policy. The [GovernanceWrite.payload] holds the route's
 * path ids and body exactly as asked; [preview] shows the rule or policy as the read routes show it.
 *
 * @param dependencies The rules use cases.
 */
class RulesGovernanceChange(private val dependencies: RulesGovernanceDependencies) : GovernanceChange {
    override val kinds: Set<String> = RulesWrites.ALL

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview = with(dependencies) {
        val payload = write.payload
        when (write.kind) {
            RulesWrites.CREATE -> {
                val body = payload.request()
                body.toNewRule()
                GovernancePreview("Create the rule \"${body.statement}\"", null, payload.getValue(BODY))
            }
            RulesWrites.UPDATE -> {
                val patch = payload.getValue(BODY).jsonObject
                patch.toRulePatch()
                val current = encode(getRule(orgId, payload.id()).toDto()).jsonObject
                val before = JsonObject(patch.keys.associateWith { current[it] ?: JsonNull })
                GovernancePreview("Edit the rule \"${current.statement()}\"", before, patch)
            }
            RulesWrites.DELETE -> {
                val current = encode(getRule(orgId, payload.id()).toDto())
                GovernancePreview("Delete the rule \"${current.jsonObject.statement()}\"", current, null)
            }
            RulesWrites.BULK -> {
                val body = payload.bulk()
                val status = ruleStatusOf(body.status)
                // Bounded as the write is, before any rule is read.
                if (body.ids.size > BulkUpdateRulesUseCase.MAX_IDS) {
                    throw InvalidRuleException("At most ${BulkUpdateRulesUseCase.MAX_IDS} rules at once.")
                }
                val found = body.ids.distinct().mapNotNull { id -> rule(orgId, id) }
                val before = JsonArray(found.map { it.brief(it.status) })
                val after = JsonArray(found.map { it.brief(body.status) })
                GovernancePreview("Set ${found.size} rules to ${status.name.lowercase()}", before, after)
            }
            else -> {
                val policy = payload.policy()
                policy.toPolicy()
                val before = encode(getGatePolicy(orgId).toDto())
                GovernancePreview("Set self-approval to ${policy.selfApproval}", before, encode(policy))
            }
        }
    }

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement? =
        with(dependencies) {
            val decider = Decider(
                orgId,
                actors.resolve(confirmer.principal)?.personId ?: confirmer.member.memberId.toString(),
                confirmer.principal,
            )
            val payload = write.payload
            when (write.kind) {
                RulesWrites.CREATE -> encode(createRule(decider, payload.request().toNewRule()).toDto())
                RulesWrites.UPDATE -> {
                    val patch = payload.getValue(BODY).jsonObject.toRulePatch()
                    encode(updateRule(decider, payload.id(), patch).toDto())
                }
                RulesWrites.DELETE -> {
                    deleteRule(decider, payload.id())
                    null
                }
                RulesWrites.BULK -> {
                    val body = payload.bulk()
                    val updated = bulkUpdateRules(decider, body.ids, ruleStatusOf(body.status))
                    encode(BulkRulesResponse(updated))
                }
                else -> encode(setGatePolicy(decider, payload.policy().toPolicy()).toDto())
            }
        }

    private suspend fun RulesGovernanceDependencies.rule(orgId: Uuid, id: String): RuleDto? = try {
        getRule(orgId, id).toDto()
    } catch (_: RuleNotFoundException) {
        null
    }

    private fun RuleDto.brief(status: String) = buildJsonObject {
        put("id", id)
        put("statement", statement)
        put("status", status)
    }

    private fun JsonObject.statement(): String = getValue("statement").jsonPrimitive.content

    /** The payloads of the rules' governance writes. */
    companion object {
        private const val ID = "id"
        private const val BODY = "body"

        /**
         * The payload of a write on one rule.
         *
         * @param id The rule, as the path names it.
         * @param body The request's body, if any.
         *
         * @return The payload.
         */
        fun payload(id: String?, body: JsonElement?): JsonObject = buildJsonObject {
            id?.let { put(ID, JsonPrimitive(it)) }
            body?.let { put(BODY, it) }
        }

        private fun JsonObject.id(): String = getValue(ID).jsonPrimitive.content

        private fun JsonObject.request(): NewRuleRequest =
            OstromJson.decodeFromJsonElement(NewRuleRequest.serializer(), getValue(BODY))

        private fun JsonObject.bulk(): BulkRulesRequest =
            OstromJson.decodeFromJsonElement(BulkRulesRequest.serializer(), getValue(BODY))

        private fun JsonObject.policy(): GatePolicyDto =
            OstromJson.decodeFromJsonElement(GatePolicyDto.serializer(), getValue(BODY))

        private inline fun <reified T> encode(value: T): JsonElement = OstromJson.encodeToJsonElement(value)
    }
}

/** The kinds of the rules' governance writes (contract `GovernanceChangeKind`). */
object RulesWrites {
    /** `POST /rules`. */
    const val CREATE: String = "rules.create"

    /** `PATCH /rules/{id}`. */
    const val UPDATE: String = "rules.update"

    /** `DELETE /rules/{id}`. */
    const val DELETE: String = "rules.delete"

    /** `POST /rules/bulk`. */
    const val BULK: String = "rules.bulk"

    /** `PUT /gates/policy`. */
    const val GATE_POLICY: String = "gates.policy"

    /** All of them. */
    val ALL: Set<String> = setOf(CREATE, UPDATE, DELETE, BULK, GATE_POLICY)
}

/**
 * What the rules' governance writes need.
 *
 * @property actors Names the confirmer as the gates know them.
 * @property getRule Reads a rule.
 * @property createRule Creates one.
 * @property updateRule Edits or decides one.
 * @property deleteRule Deletes one.
 * @property bulkUpdateRules Decides several.
 * @property getGatePolicy Reads the gate policy.
 * @property setGatePolicy Sets it.
 */
data class RulesGovernanceDependencies(
    val actors: GateActorResolver,
    val getRule: GetRuleUseCase,
    val createRule: CreateRuleUseCase,
    val updateRule: UpdateRuleUseCase,
    val deleteRule: DeleteRuleUseCase,
    val bulkUpdateRules: BulkUpdateRulesUseCase,
    val getGatePolicy: GetGatePolicyUseCase,
    val setGatePolicy: SetGatePolicyUseCase,
)
