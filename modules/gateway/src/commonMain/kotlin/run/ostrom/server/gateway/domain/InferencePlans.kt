// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway.domain

import run.ostrom.server.sharing.domain.AccountLease
import kotlin.time.Instant

/**
 * Base URLs of the providers. Defaults are the public APIs; an operator (or a test) points them elsewhere.
 *
 * @property anthropic Anthropic API (`/v1/messages`).
 * @property openai OpenAI API (`/v1/responses`, `/v1/chat/completions`).
 * @property openrouter OpenRouter API (`/v1/chat/completions`).
 * @property codex ChatGPT's Codex backend (`/responses`), for shared ChatGPT accounts.
 */
data class ProviderEndpoints(
    val anthropic: String = "https://api.anthropic.com",
    val openai: String = "https://api.openai.com",
    val openrouter: String = "https://openrouter.ai/api",
    val codex: String = "https://chatgpt.com/backend-api/codex",
)

/**
 * Provider keys set in the instance configuration (Docker environment, Helm values). They come before the org's
 * settings: an operator who sets one decides for every org of the instance.
 *
 * @property anthropic Anthropic API key.
 * @property openai OpenAI API key.
 * @property openrouter OpenRouter key.
 */
class InstanceProviderKeys(val anthropic: String? = null, val openai: String? = null, val openrouter: String? = null) {
    /** The instance key of [provider], if the operator set one. */
    fun of(provider: InferenceProvider): String? = if (provider.family == InferenceProvider.ANTHROPIC.family) {
        anthropic
    } else if (provider == InferenceProvider.OPENROUTER) {
        openrouter
    } else {
        openai
    }

    override fun toString(): String = "InstanceProviderKeys([REDACTED])"
}

/**
 * A request ready to go upstream. Holds a credential: never logged.
 *
 * @property url Where.
 * @property headers What to send, credential included.
 * @property body The body, rewritten where the provider needs it.
 */
class UpstreamRequest(val url: String, val headers: List<Pair<String, String>>, val body: String) {
    override fun toString(): String = "UpstreamRequest($url)"
}

private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val TOO_MANY_REQUESTS = 429
private const val BAD_GATEWAY = 502
private const val SERVICE_UNAVAILABLE = 503

/**
 * Why the gateway answers itself instead of a provider, with the error type each API uses for it.
 *
 * @property status The HTTP status.
 * @property anthropicType `error.type` of an Anthropic error.
 * @property openAiType `error.type` of an OpenAI error.
 */
enum class RefusalKind(val status: Int, val anthropicType: String, val openAiType: String) {
    /** The body is not a valid request. */
    INVALID(BAD_REQUEST, "invalid_request_error", "invalid_request_error"),

    /** The token is not a member's. */
    UNAUTHENTICATED(UNAUTHORIZED, "authentication_error", "authentication_error"),

    /** The credential may not call models (a device, or a token without the scope). */
    CREDENTIAL_NOT_ALLOWED(FORBIDDEN, "permission_error", "permission_error"),

    /** The org does not allow this model. */
    MODEL_NOT_ALLOWED(FORBIDDEN, "permission_error", "permission_error"),

    /** A monthly budget is reached. */
    BUDGET_EXHAUSTED(TOO_MANY_REQUESTS, "rate_limit_error", "insufficient_quota"),

    /** What is left of a budget does not cover what this call may cost. */
    BUDGET_INSUFFICIENT(TOO_MANY_REQUESTS, "rate_limit_error", "insufficient_quota"),

    /** Every shared account is busy or at its limit. */
    NO_CAPACITY(TOO_MANY_REQUESTS, "rate_limit_error", "rate_limit_exceeded"),

    /** The org has neither a usable account nor a key for this provider. */
    NO_CREDENTIAL(SERVICE_UNAVAILABLE, "api_error", "server_error"),

    /** The provider could not be reached. */
    UPSTREAM_UNREACHABLE(BAD_GATEWAY, "api_error", "server_error"),

    /** This replica already holds as many calls as its memory allows (`OSTROM_MAX_HELD_CALLS`). */
    OVERLOADED(SERVICE_UNAVAILABLE, "overloaded_error", "server_error"),

    /** `x-ostrom-workload` is neither `interactive` nor `background`, or is sent twice. */
    INVALID_WORKLOAD(BAD_REQUEST, "invalid_request_error", "invalid_request_error"),

    /** `x-ostrom-client-request-id` is not one lowercase UUID. */
    INVALID_REQUEST_ID(BAD_REQUEST, "invalid_request_error", "invalid_request_error"),

    /**
     * The org's admission queue, or the member's share of it, is full. Never `overloaded_error`: Claude Code retries it
     * whatever the headers say.
     */
    QUEUE_FULL(SERVICE_UNAVAILABLE, "api_error", "server_error"),

    /** The call waited in the admission queue as long as it may without its turn. */
    QUEUE_TIMEOUT(SERVICE_UNAVAILABLE, "api_error", "server_error"),

    /** The call's place in the admission queue expired (its lease was not renewed). */
    QUEUE_EXPIRED(SERVICE_UNAVAILABLE, "api_error", "server_error"),
    ;

    /** The code of the refusal, in `x-ostrom-error` and an OpenAI error's `code`. */
    val code: String get() = name.lowercase()
}

/**
 * The gateway's own answer.
 *
 * @property kind Why.
 * @property message Safe for the agent: no credential, no other member's data.
 * @property retryAt When retrying makes sense.
 */
data class InferenceRefusal(val kind: RefusalKind, val message: String, val retryAt: Instant? = null)

/** What [InferencePlanner] decides. */
sealed interface InferencePlan {
    /** The model asked for, empty when the body named none. */
    val model: String

    /**
     * Send it.
     *
     * @property request The request.
     * @property provider Where it goes.
     * @property lease The shared account used, to release after the answer.
     * @property stream Whether the agent asked for a stream.
     */
    class Ready(
        val request: UpstreamRequest,
        val provider: InferenceProvider,
        val lease: AccountLease?,
        override val model: String,
        val stream: Boolean,
    ) : InferencePlan

    /**
     * Do not send it.
     *
     * @property refusal What to answer.
     */
    class Refused(val refusal: InferenceRefusal, override val model: String) : InferencePlan

    /**
     * Not now: every shared account the call may use is busy, and no key takes it; its budget hold is dropped. The
     * admission queue makes it wait; without one, [refusal] is answered.
     *
     * @property refusal The 429 `no_capacity` answered when the call cannot wait.
     */
    class Busy(val refusal: InferenceRefusal, override val model: String) : InferencePlan
}
