// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import run.ostrom.server.secrets.domain.SecretUnreadableException
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.PoolRefusal
import run.ostrom.server.sharing.domain.SharedAccountPool
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Decides where a member's model call goes and builds it: a valid body, an allowed model, the budgets, then the
 * credential — a shared subscription account first (when the org uses them), else the provider key of the instance
 * (set in the console), else the org's key secret.
 *
 * @param settings The org's settings and model policy.
 * @param pool The org's shared accounts.
 * @param revealSecrets Opens the org's key secrets.
 * @param spend The budgets, asked with what the call may cost.
 * @param instance The instance's provider keys and URLs, read once per call ([GatewayInstanceSettings]).
 */
class InferencePlanner(
    private val settings: GatewaySettingsRepository,
    private val pool: SharedAccountPool,
    private val revealSecrets: RevealSecretsUseCase,
    private val spend: BudgetCheck,
    private val instance: suspend () -> InstanceProviders,
) {
    /**
     * A planner on fixed providers.
     *
     * @param settings The org's settings and model policy.
     * @param pool The org's shared accounts.
     * @param revealSecrets Opens the org's key secrets.
     * @param spend The budgets.
     * @param keys The instance's provider keys.
     * @param requests Builds the upstream requests.
     */
    constructor(
        settings: GatewaySettingsRepository,
        pool: SharedAccountPool,
        revealSecrets: RevealSecretsUseCase,
        spend: BudgetCheck,
        keys: InstanceProviderKeys,
        requests: UpstreamRequests,
    ) : this(settings, pool, revealSecrets, spend, { InstanceProviders(keys, requests.endpoints) })

    /**
     * Plans a call.
     *
     * @param caller The member.
     * @param call The call as the agent sent it.
     */
    suspend fun plan(caller: Caller, call: InferenceCall): InferencePlan =
        plan(caller, call, configOf(caller), Uuid.random())

    /** What [plan] needs of the caller's org and of the instance: one read, kept over the tries of a call. */
    suspend fun configOf(caller: Caller): GatewayOrgConfig =
        settings.forCall(caller.orgId).copy(providers = instance())

    /**
     * Plans a try of a call.
     *
     * @param caller The member.
     * @param call The call as the agent sent it.
     * @param org The org's settings and policy ([configOf]).
     * @param callId The call, the same for each try: its budget hold is taken once.
     */
    suspend fun plan(caller: Caller, call: InferenceCall, org: GatewayOrgConfig, callId: Uuid): InferencePlan {
        val body = RequestBody.read(call.body, org.policy.default)
            ?: return refused(RefusalKind.INVALID, "The request body must be a JSON object naming a model.", "")
        val plan = admit(caller, call, body, org.policy, callId) ?: route(caller, call, body, org)
        // A call that waits for a busy account holds no budget meanwhile: it is taken again on its next try.
        if (plan is InferencePlan.Busy) spend.release(caller, callId)
        return plan
    }

    /** The refusal of a model the org does not allow, or of a member over budget; null when the call may go. */
    private suspend fun admit(
        caller: Caller,
        call: InferenceCall,
        body: RequestBody,
        policy: ModelPolicy,
        callId: Uuid,
    ): InferencePlan.Refused? {
        if (!policy.allows(caller.memberId, body.model)) {
            return refused(
                RefusalKind.MODEL_NOT_ALLOWED,
                modelNotAllowed(body.model),
                body.model,
            )
        }
        return when (val decision = spend.check(caller, callId, call.protocol, body)) {
            SpendDecision.Allowed -> null
            is SpendDecision.Exhausted ->
                refused(RefusalKind.BUDGET_EXHAUSTED, decision.message, body.model, decision.resetsAt)
            is SpendDecision.Insufficient -> refused(RefusalKind.BUDGET_INSUFFICIENT, decision.message, body.model)
        }
    }

    private suspend fun route(
        caller: Caller,
        call: InferenceCall,
        body: RequestBody,
        org: GatewayOrgConfig,
    ): InferencePlan {
        val lease = call.protocol.shared?.takeIf { org.settings.useSharedAccounts }
            ?.let { pool.lease(caller.orgId, caller.memberId, it, call.sessionId) }
        return when (lease) {
            is LeaseResult.Leased -> UpstreamRequests(org.providers.endpoints).shared(call, body, lease.lease)
            is LeaseResult.Refused -> byKey(caller, call, body, org, lease.refusal)
            null -> byKey(caller, call, body, org, null)
        }
    }

    /**
     * The call on an API key; without one, busy shared accounts make the call wait ([InferencePlan.Busy], its budget
     * hold dropped meanwhile), accounts at their limit answer 429 and none at all 503.
     */
    private suspend fun byKey(
        caller: Caller,
        call: InferenceCall,
        body: RequestBody,
        org: GatewayOrgConfig,
        pool: PoolRefusal?,
    ): InferencePlan {
        val provider = call.protocol.keyProvider(body.model)
        val key = org.providers.keys.of(provider) ?: org.settings.keySecret(provider)?.let { reveal(caller, it) }
        val busyUntil = pool?.retryAt
        return when {
            key != null -> UpstreamRequests(org.providers.endpoints).byKey(provider, call, body, key)
            pool?.busy == true -> InferencePlan.Busy(noCapacity(busyUntil), body.model)
            busyUntil != null -> InferencePlan.Refused(noCapacity(busyUntil), body.model)
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

    private fun noCapacity(busyUntil: Instant?) = InferenceRefusal(
        RefusalKind.NO_CAPACITY,
        "Every shared account of your organization is busy or at its limit.",
        busyUntil,
    )

    private fun refused(kind: RefusalKind, message: String, model: String, retryAt: Instant? = null) =
        InferencePlan.Refused(InferenceRefusal(kind, message, retryAt), model)
}
