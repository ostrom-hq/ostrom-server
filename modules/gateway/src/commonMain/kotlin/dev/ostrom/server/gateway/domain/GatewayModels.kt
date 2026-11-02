// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.kernel.auth.ModelAccess
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.sharing.domain.AccountProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The API shape an agent speaks to the gateway.
 *
 * @property shared The subscription accounts that serve it, if any.
 */
enum class WireProtocol(val shared: AccountProvider?) {
    /** Anthropic Messages (`/v1/messages`): Claude Code. */
    MESSAGES(AccountProvider.CLAUDE),

    /** OpenAI Responses (`/v1/responses`): Codex. */
    RESPONSES(AccountProvider.CODEX),

    /** OpenAI Chat Completions (`/v1/chat/completions`): other agents, OpenRouter. */
    CHAT(null),
    ;

    /** The provider whose API key serves [model] when no shared account does. */
    fun keyProvider(model: String): InferenceProvider = when (this) {
        MESSAGES -> InferenceProvider.ANTHROPIC
        RESPONSES -> InferenceProvider.OPENAI
        CHAT -> if ('/' in model) InferenceProvider.OPENROUTER else InferenceProvider.OPENAI
    }
}

/**
 * Where a request is sent, and with what credential.
 *
 * @property wire The name in the usage ledger.
 * @property family The price family ([PriceTable]): shared accounts are priced as their API equivalent.
 * @property label The provider's name, for messages.
 */
enum class InferenceProvider(val wire: String, val family: String, val label: String) {
    /** A shared Claude Pro/Max account (OAuth). */
    CLAUDE_SHARED("claude", "anthropic", "Anthropic"),

    /** A shared ChatGPT account for Codex (OAuth). */
    CODEX_SHARED("codex", "openai", "OpenAI"),

    /** The org's Anthropic API key. */
    ANTHROPIC("anthropic", "anthropic", "Anthropic"),

    /** The org's OpenAI API key. */
    OPENAI("openai", "openai", "OpenAI"),

    /** The org's OpenRouter API key. */
    OPENROUTER("openrouter", "openrouter", "OpenRouter"),
}

/**
 * One model call as an agent sent it.
 *
 * @property protocol The API shape.
 * @property body The request body.
 * @property headers The agent's headers, names in lowercase, without its Ostrom credential.
 * @property sessionId The agent session, for account affinity, when the agent named it.
 */
class InferenceCall(
    val protocol: WireProtocol,
    val body: String,
    val headers: Map<String, String>,
    val sessionId: String?,
) {
    override fun toString(): String = "InferenceCall($protocol, ${body.length} chars)"
}

/**
 * Tokens of one model call, as the provider reported them.
 *
 * @property input Uncached input tokens.
 * @property output Output tokens.
 * @property cacheRead Input read from the provider's cache.
 * @property cacheWrite Input written to the cache.
 */
data class TokenUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0)

/**
 * One relayed model call, for the usage ledger, budgets, per-account limits and traces. Never holds prompt or output.
 *
 * @property id The call.
 * @property orgId The org.
 * @property memberId The member.
 * @property sessionId The agent session, when the agent named it.
 * @property protocol The API shape.
 * @property provider Where it went.
 * @property accountId The shared account used, if any.
 * @property model The model asked for.
 * @property usage Tokens, when the provider reported them.
 * @property costMicros Equivalent API cost, USD millionths (0 when the model has no price).
 * @property priced Whether a price row applied.
 * @property status The HTTP status answered to the agent.
 * @property outcome `ok`, `provider_error`, `cancelled`, `budget_exhausted`, `no_capacity`...
 * @property startedAt When the request arrived.
 * @property firstByteMillis Time to the first byte sent back, if any.
 * @property durationMillis Total time.
 */
data class ModelCallRecord(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid,
    val sessionId: String?,
    val protocol: WireProtocol,
    val provider: InferenceProvider?,
    val accountId: Uuid?,
    val model: String,
    val usage: TokenUsage?,
    val costMicros: Long,
    val priced: Boolean,
    val status: Int,
    val outcome: String,
    val startedAt: Instant,
    val firstByteMillis: Long?,
    val durationMillis: Long,
)

/** Receives every relayed call: bound to the usage ledger by the usage wiring. */
fun interface ModelUsageSink {
    /** Records it; may throw, the gateway guards. */
    suspend fun record(call: ModelCallRecord)
}

/** Drops every call: the default until the usage module binds its [ModelUsageSink]. */
object NoModelUsageSink : ModelUsageSink {
    override suspend fun record(call: ModelCallRecord) = Unit
}

/** Receives every relayed call for tracing (Langfuse through OBS). */
fun interface ModelCallObserver {
    /** Observes it. */
    suspend fun observe(call: ModelCallRecord)
}

/** Traces nothing: the default until OBS binds its [ModelCallObserver]. */
object NoModelCallObserver : ModelCallObserver {
    override suspend fun observe(call: ModelCallRecord) = Unit
}

/** Whether a member may spend now. */
sealed interface SpendDecision {
    /** Yes. */
    data object Allowed : SpendDecision

    /**
     * No: a budget is exhausted.
     *
     * @property message Safe for the agent.
     * @property resetsAt When it resets.
     */
    data class Exhausted(val message: String, val resetsAt: Instant?) : SpendDecision
}

/** Budgets as the gateway sees them; bound to the budgets module by the budgets wiring. */
fun interface SpendGuard {
    /** Decides for the member. */
    suspend fun check(orgId: Uuid, memberId: Uuid): SpendDecision
}

/** Allows every call: the default until the budgets module binds its [SpendGuard]. */
object AllowAllSpend : SpendGuard {
    override suspend fun check(orgId: Uuid, memberId: Uuid): SpendDecision = SpendDecision.Allowed
}

/**
 * The org's inference settings (an addition to the API contract, `/api/v1/inference/settings`).
 *
 * @property anthropicKeySecret Org secret (by name) holding an Anthropic API key, for Messages when no shared account
 *   serves.
 * @property openaiKeySecret Org secret holding an OpenAI API key, for Responses and Chat.
 * @property openrouterKeySecret Org secret holding an OpenRouter key, for Chat (models named `vendor/model`).
 * @property useSharedAccounts Whether shared subscription accounts serve first (on by default).
 */
data class GatewaySettings(
    val anthropicKeySecret: String? = null,
    val openaiKeySecret: String? = null,
    val openrouterKeySecret: String? = null,
    val useSharedAccounts: Boolean = true,
) {
    /** The secret naming the key of [provider], if the org set one. */
    fun keySecret(provider: InferenceProvider): String? = if (provider.family == InferenceProvider.ANTHROPIC.family) {
        anthropicKeySecret
    } else if (provider == InferenceProvider.OPENROUTER) {
        openrouterKeySecret
    } else {
        openaiKeySecret
    }
}

/**
 * Which models members may use (contract `ModelPolicy`). A model is allowed when it starts with one of the entries;
 * an empty list allows every model.
 *
 * @property allowed For every member.
 * @property default Sent when an agent names no model.
 * @property byMember Replaces [allowed] for a member (by member id).
 */
data class ModelPolicy(
    val allowed: List<String> = emptyList(),
    val default: String? = null,
    val byMember: Map<String, List<String>> = emptyMap(),
) {
    /** The list that applies to the member. */
    fun listFor(memberId: Uuid): List<String> = byMember[memberId.toString()] ?: allowed

    /** Whether the member may use the model. */
    fun allows(memberId: Uuid, model: String): Boolean {
        val list = listFor(memberId)
        return list.isEmpty() || list.any { model.startsWith(it) }
    }
}

/**
 * Why a member may not use a model: the gateway's refusal of a call, and a launch's (via [PolicyModelAccess]).
 *
 * @param model The model refused.
 *
 * @return The reason.
 */
fun modelNotAllowed(model: String): String = "Your organization does not allow the model $model."

/**
 * [ModelAccess] on the org's model policy, the same check as a model call: a launch is refused a model the gateway
 * would refuse, with the same reason.
 *
 * @param settings The org's settings and policy.
 */
class PolicyModelAccess(private val settings: GatewaySettingsRepository) : ModelAccess {
    override suspend fun refusal(orgId: Uuid, memberId: Uuid, model: String): String? =
        modelNotAllowed(model).takeUnless { settings.policy(orgId).allows(memberId, model) }
}

/**
 * What a model call needs of its org, read once per call.
 *
 * @property settings The gateway settings.
 * @property policy The model policy.
 */
data class GatewayOrgConfig(val settings: GatewaySettings = GatewaySettings(), val policy: ModelPolicy = ModelPolicy())

/** Storage of the org's settings and model policy, confined to the org. */
interface GatewaySettingsRepository {
    /** The settings and the policy in one read, for a model call (defaults for what was never set). */
    suspend fun forCall(orgId: Uuid): GatewayOrgConfig

    /** The settings, defaults when never set. */
    suspend fun get(orgId: Uuid): GatewaySettings

    /** Replaces them. */
    suspend fun put(orgId: Uuid, settings: GatewaySettings)

    /** The model policy, everything allowed when never set. */
    suspend fun policy(orgId: Uuid): ModelPolicy

    /** Replaces it. */
    suspend fun putPolicy(orgId: Uuid, policy: ModelPolicy)
}

/** A malformed setting. */
class InvalidGatewaySettingsException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
