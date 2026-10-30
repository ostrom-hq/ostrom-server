// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretUnreadableException
import dev.ostrom.server.sharing.domain.LeaseResult
import dev.ostrom.server.sharing.domain.SharedAccountPool
import kotlin.time.Instant

/**
 * Decides where a member's model call goes and builds it: a valid body, an allowed model, the budgets, then the
 * credential — a shared subscription account first (when the org uses them), else the provider key of the instance
 * configuration, else the org's key secret.
 *
 * @param settings The org's settings and model policy.
 * @param pool The org's shared accounts.
 * @param revealSecrets Opens the org's key secrets.
 * @param spend The budgets.
 * @param keys The instance's provider keys.
 * @param requests Builds the upstream requests.
 */
class InferencePlanner(
    private val settings: GatewaySettingsRepository,
    private val pool: SharedAccountPool,
    private val revealSecrets: RevealSecretsUseCase,
    private val spend: SpendGuard,
    private val keys: InstanceProviderKeys,
    private val requests: UpstreamRequests,
) {
    /**
     * Plans a call.
     *
     * @param caller The member.
     * @param call The call as the agent sent it.
     */
    suspend fun plan(caller: Caller, call: InferenceCall): InferencePlan = plan(caller, call, configOf(caller))

    /** What [plan] needs of the caller's org: one read, kept over the tries of a call. */
    suspend fun configOf(caller: Caller): GatewayOrgConfig = settings.forCall(caller.orgId)

    /**
     * Plans a try of a call.
     *
     * @param caller The member.
     * @param call The call as the agent sent it.
     * @param org The org's settings and policy ([configOf]).
     */
    suspend fun plan(caller: Caller, call: InferenceCall, org: GatewayOrgConfig): InferencePlan {
        val body = RequestBody.read(call.body, org.policy.default)
            ?: return refused(RefusalKind.INVALID, "The request body must be a JSON object naming a model.", "")
        return admit(caller, body, org.policy) ?: route(caller, call, body, org.settings)
    }

    /** The refusal of a model the org does not allow, or of a member over budget; null when the call may go. */
    private suspend fun admit(caller: Caller, body: RequestBody, policy: ModelPolicy): InferencePlan.Refused? {
        if (!policy.allows(caller.memberId, body.model)) {
            return refused(
                RefusalKind.MODEL_NOT_ALLOWED,
                "Your organization does not allow the model ${body.model}.",
                body.model,
            )
        }
        val decision = spend.check(caller.orgId, caller.memberId)
        return (decision as? SpendDecision.Exhausted)
            ?.let { refused(RefusalKind.BUDGET_EXHAUSTED, it.message, body.model, it.resetsAt) }
    }

    private suspend fun route(
        caller: Caller,
        call: InferenceCall,
        body: RequestBody,
        org: GatewaySettings,
    ): InferencePlan {
        val lease = call.protocol.shared?.takeIf { org.useSharedAccounts }
            ?.let { pool.lease(caller.orgId, caller.memberId, it, call.sessionId) }
        return when (lease) {
            is LeaseResult.Leased -> requests.shared(call, body, lease.lease)
            is LeaseResult.Refused -> byKey(caller, call, body, org, lease.refusal.retryAt)
            null -> byKey(caller, call, body, org, null)
        }
    }

    /** The call on an API key; without one, busy shared accounts answer 429 and none at all 503. */
    private suspend fun byKey(
        caller: Caller,
        call: InferenceCall,
        body: RequestBody,
        org: GatewaySettings,
        busyUntil: Instant?,
    ): InferencePlan {
        val provider = call.protocol.keyProvider(body.model)
        val key = keys.of(provider) ?: org.keySecret(provider)?.let { reveal(caller, it) }
        return when {
            key != null -> requests.byKey(provider, call, body, key)
            busyUntil != null -> refused(
                RefusalKind.NO_CAPACITY,
                "Every shared account of your organization is busy or at its limit.",
                body.model,
                busyUntil,
            )
            else -> refused(
                RefusalKind.NO_CREDENTIAL,
                "Your organization has no shared account or ${provider.label} key for this request.",
                body.model,
            )
        }
    }

    /** The key held by the org secret [name]; null when it is gone or no longer opens. */
    private suspend fun reveal(caller: Caller, name: String): String? = try {
        revealSecrets.byName(caller.orgId, listOf(name))[name]?.reveal()
    } catch (_: SecretUnreadableException) {
        null
    }

    private fun refused(kind: RefusalKind, message: String, model: String, retryAt: Instant? = null) =
        InferencePlan.Refused(InferenceRefusal(kind, message, retryAt), model)
}
