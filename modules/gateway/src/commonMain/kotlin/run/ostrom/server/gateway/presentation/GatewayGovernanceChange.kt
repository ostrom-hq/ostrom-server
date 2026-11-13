// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.presentation

import run.ostrom.server.gateway.domain.GatewayRules
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.gateway.domain.PutModelPolicyUseCase
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmer
import run.ostrom.server.kernel.auth.GovernancePreview
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgNotFoundException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.Uuid

/**
 * The model policy set with a person's token, held for a confirmation (audit CLI H1). An `inference:admin` service key
 * still sets it at once: it is a machine's, never on a developer's machine.
 *
 * @param repository Reads the policy in force.
 * @param callers Resolves the confirmer.
 * @param putPolicy Sets it.
 * @param audit The organization's audit log, which gets the change as the confirmer's, like the console's.
 */
class GatewayGovernanceChange(
    private val repository: GatewaySettingsRepository,
    private val callers: CallerResolver,
    private val putPolicy: PutModelPolicyUseCase,
    private val audit: OrgAuditTrail,
) : GovernanceChange {
    override val kinds: Set<String> = setOf(MODEL_POLICY)

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview {
        val after = GatewayRules.requirePolicy(write.payload.policy())
        val before = repository.policy(orgId)
        return GovernancePreview("Set the model policy", encode(before), encode(after))
    }

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement {
        val caller = callers.resolve(confirmer.principal, orgId) ?: throw OrgNotFoundException()
        val policy = putPolicy(caller, write.payload.policy())
        audit.record(policy.changedBy(orgId, confirmer.principal))
        return encode(policy)
    }

    /** The payload of the model policy's write. */
    companion object {
        /** `PUT /policy/models` (contract `GovernanceChangeKind`). */
        const val MODEL_POLICY: String = "models.policy"

        private const val BODY = "body"

        /**
         * The payload of a write.
         *
         * @param body The request's body.
         *
         * @return The payload.
         */
        fun payload(body: ModelPolicyDto): JsonObject =
            buildJsonObject { put(BODY, OstromJson.encodeToJsonElement(ModelPolicyDto.serializer(), body)) }

        private fun JsonObject.policy(): ModelPolicy {
            val dto = OstromJson.decodeFromJsonElement(ModelPolicyDto.serializer(), getValue(BODY))
            return ModelPolicy(dto.allowed, dto.defaultModel, dto.byMember)
        }

        private fun encode(policy: ModelPolicy): JsonElement = OstromJson.encodeToJsonElement(
            ModelPolicyDto.serializer(),
            ModelPolicyDto(policy.allowed, policy.default, policy.byMember),
        )
    }
}

/**
 * The audit line of a model policy set, by the console's session or by the confirmer of a held change.
 *
 * @param orgId The organization.
 * @param principal Who set it.
 *
 * @return The `inference.policy.changed` record.
 */
internal fun ModelPolicy.changedBy(orgId: Uuid, principal: AuthenticatedPrincipal): OrgAuditRecord {
    val details = auditDetails(
        "allowed" to allowed,
        "default_model" to default,
        "members_with_own_list" to byMember.keys,
    )
    return OrgAuditRecord(orgId, principal, "inference.policy.changed", "policy", "models", details)
}
