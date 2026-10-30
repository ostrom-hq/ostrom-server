package dev.ostrom.server.gateway.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The API shape an agent speaks to the gateway. */
enum class WireProtocol {
    /** Anthropic Messages (`/v1/messages`): Claude Code. */
    MESSAGES,

    /** OpenAI Responses (`/v1/responses`): Codex. */
    RESPONSES,

    /** OpenAI Chat Completions (`/v1/chat/completions`): other agents, OpenRouter. */
    CHAT,
}

/**
 * Where a request is sent, and with what credential.
 *
 * @property wire The name in the usage ledger.
 */
enum class InferenceProvider(val wire: String) {
    /** A shared Claude Pro/Max account (OAuth). */
    CLAUDE_SHARED("claude"),

    /** A shared ChatGPT account for Codex (OAuth). */
    CODEX_SHARED("codex"),

    /** The org's Anthropic API key. */
    ANTHROPIC("anthropic"),

    /** The org's OpenAI API key. */
    OPENAI("openai"),

    /** The org's OpenRouter API key. */
    OPENROUTER("openrouter"),
}

/**
 * Tokens of one model call, as the provider reported them.
 *
 * @property input Uncached input tokens.
 * @property output Output tokens.
 * @property cacheRead Input read from the provider's cache.
 * @property cacheWrite Input written to the cache.
 */
data class TokenUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0) {
    /** All tokens. */
    val total: Long get() = input + output + cacheRead + cacheWrite
}

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

/** Receives every relayed call: bound to the usage ledger by the usage wiring; the default drops them. */
fun interface ModelUsageSink {
    /** Records it; must not throw (the gateway also guards). */
    suspend fun record(call: ModelCallRecord)
}

/** Receives every relayed call for tracing (Langfuse through OBS); the default does nothing. */
fun interface ModelCallObserver {
    /** Observes it. */
    suspend fun observe(call: ModelCallRecord)
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

/** Budgets as the gateway sees them; bound to the budgets module by the budgets wiring, allows all by default. */
fun interface SpendGuard {
    /** Decides for the member. */
    suspend fun check(orgId: Uuid, memberId: Uuid): SpendDecision
}

/**
 * The org's inference settings (an addition to the API contract, `/api/v1/inference/settings`).
 *
 * @property anthropicKeySecret Org secret (by name) holding an Anthropic API key, for Messages when no shared account serves.
 * @property openaiKeySecret Org secret holding an OpenAI API key, for Responses and Chat.
 * @property openrouterKeySecret Org secret holding an OpenRouter key, for Chat (models named `vendor/model`).
 * @property useSharedAccounts Whether shared subscription accounts serve first (on by default).
 */
data class GatewaySettings(
    val anthropicKeySecret: String? = null,
    val openaiKeySecret: String? = null,
    val openrouterKeySecret: String? = null,
    val useSharedAccounts: Boolean = true,
)

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
    /** Whether the member may use the model. */
    fun allows(memberId: Uuid, model: String): Boolean {
        val list = byMember[memberId.toString()] ?: allowed
        return list.isEmpty() || list.any { model.startsWith(it) }
    }
}

/** Storage of the org's settings and model policy. */
interface GatewaySettingsRepository {
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
