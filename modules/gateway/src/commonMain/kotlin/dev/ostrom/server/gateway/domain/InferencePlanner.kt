package dev.ostrom.server.gateway.domain

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.SecretUnreadableException
import dev.ostrom.server.sharing.domain.AccountLease
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.LeaseResult
import dev.ostrom.server.sharing.domain.PoolRefusal
import dev.ostrom.server.sharing.domain.SharedAccountPool
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
 */
data class InstanceProviderKeys(val anthropic: String? = null, val openai: String? = null, val openrouter: String? = null) {
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

/** Why the gateway answers itself instead of a provider. */
enum class RefusalKind(val status: Int) {
    /** The body is not a valid request. */
    INVALID(400),

    /** The token is not a member's. */
    UNAUTHENTICATED(401),

    /** The org does not allow this model. */
    MODEL_NOT_ALLOWED(403),

    /** A budget is exhausted. */
    BUDGET_EXHAUSTED(429),

    /** Every shared account is busy or at its limit. */
    NO_CAPACITY(429),

    /** The org has neither a usable account nor a key for this provider. */
    NO_CREDENTIAL(503),

    /** The provider could not be reached. */
    UPSTREAM_UNREACHABLE(502),
}

/**
 * The gateway's own answer.
 *
 * @property message Safe for the agent: no credential, no other member's data.
 * @property retryAt When retrying makes sense.
 */
data class InferenceRefusal(val kind: RefusalKind, val message: String, val retryAt: Instant? = null)

/** What [InferencePlanner] decides. */
sealed interface InferencePlan {
    /**
     * Send it.
     *
     * @property lease The shared account used, to release after the answer.
     */
    data class Ready(
        val request: UpstreamRequest,
        val provider: InferenceProvider,
        val lease: AccountLease?,
        val model: String,
        val stream: Boolean,
    ) : InferencePlan

    /**
     * Do not send it.
     *
     * @property model The model asked for, when the body named one.
     */
    data class Refused(val refusal: InferenceRefusal, val model: String) : InferencePlan
}

/**
 * Decides where a member's model call goes and builds it: allowed model, budgets, then the credential — a shared
 * subscription account first (when the org uses them), else the provider key of the instance configuration, else the
 * org's key secret.
 */
class InferencePlanner(
    private val settings: GatewaySettingsRepository,
    private val pool: SharedAccountPool,
    private val revealSecrets: RevealSecretsUseCase,
    private val spend: SpendGuard,
    private val keys: InstanceProviderKeys,
    private val endpoints: ProviderEndpoints,
) {
    /**
     * Plans a call.
     *
     * @param caller The member.
     * @param protocol The API shape.
     * @param body The request body as sent by the agent.
     * @param clientHeaders The agent's headers, names in lowercase.
     * @param sessionId The agent session, for account affinity.
     */
    suspend fun plan(caller: Caller, protocol: WireProtocol, body: String, clientHeaders: Map<String, String>, sessionId: String?): InferencePlan {
        val scanned = TopLevelJson.scan(body, FIELDS) ?: return refuse(RefusalKind.INVALID, "The request body must be a JSON object.", "")
        val policy = settings.policy(caller.orgId)
        val named = TopLevelJson.string(body, scanned.ranges["model"])
        val defaulted = named == null && scanned.ranges["model"] == null && policy.default != null
        val model = if (defaulted) policy.default else named
        if (model == null || model.isEmpty() || model.length > MAX_MODEL || model.any { it.isWhitespace() || it.isISOControl() }) {
            return refuse(RefusalKind.INVALID, "The request must name a model.", "")
        }
        // The org's default model goes into a request that names none.
        val text = if (defaulted) TopLevelJson.rewrite(body, scanned, emptyMap(), listOf("\"model\":" + JsonPrimitive(model))) else body
        val fields = if (defaulted) TopLevelJson.scan(text, FIELDS)!! else scanned
        val stream = TopLevelJson.boolean(text, fields.ranges["stream"]) ?: false
        if (!policy.allows(caller.memberId, model)) return refuse(RefusalKind.MODEL_NOT_ALLOWED, "Your organization does not allow the model $model.", model)
        val org = settings.get(caller.orgId)
        when (val decision = spend.check(caller.orgId, caller.memberId)) {
            is SpendDecision.Exhausted -> return InferencePlan.Refused(InferenceRefusal(RefusalKind.BUDGET_EXHAUSTED, decision.message, decision.resetsAt), model)
            SpendDecision.Allowed -> Unit
        }
        val shared = when (protocol) {
            WireProtocol.MESSAGES -> AccountProvider.CLAUDE
            WireProtocol.RESPONSES -> AccountProvider.CODEX
            WireProtocol.CHAT -> null
        }
        var poolRefusal: PoolRefusal? = null
        if (shared != null && org.useSharedAccounts) {
            when (val lease = pool.lease(caller.orgId, caller.memberId, shared, sessionId)) {
                is LeaseResult.Leased -> return ready(protocol, text, fields, clientHeaders, lease.lease, model, stream)
                is LeaseResult.Refused -> poolRefusal = lease.refusal
            }
        }
        val provider = when (protocol) {
            WireProtocol.MESSAGES -> InferenceProvider.ANTHROPIC
            WireProtocol.RESPONSES -> InferenceProvider.OPENAI
            WireProtocol.CHAT -> if ('/' in model) InferenceProvider.OPENROUTER else InferenceProvider.OPENAI
        }
        val key = keyFor(provider, caller.orgId, org)
        if (key == null) {
            val refusal = poolRefusal
            return if (refusal?.retryAt != null) {
                refuse(RefusalKind.NO_CAPACITY, "Every shared account of your organization is busy or at its limit.", model, refusal.retryAt)
            } else {
                refuse(RefusalKind.NO_CREDENTIAL, "Your organization has no shared account or ${provider.label} key for this request.", model)
            }
        }
        return InferencePlan.Ready(byok(provider, protocol, text, clientHeaders, key), provider, null, model, stream)
    }

    private suspend fun keyFor(provider: InferenceProvider, orgId: Uuid, org: GatewaySettings): String? {
        val (instance, secret) = when (provider) {
            InferenceProvider.ANTHROPIC -> keys.anthropic to org.anthropicKeySecret
            InferenceProvider.OPENAI -> keys.openai to org.openaiKeySecret
            else -> keys.openrouter to org.openrouterKeySecret
        }
        if (instance != null) return instance
        if (secret == null) return null
        return try {
            revealSecrets.byName(orgId, listOf(secret))[secret]?.reveal()
        } catch (_: SecretUnreadableException) {
            null
        }
    }

    private fun ready(
        protocol: WireProtocol,
        body: String,
        fields: TopLevelJson.Fields,
        clientHeaders: Map<String, String>,
        lease: AccountLease,
        model: String,
        stream: Boolean,
    ): InferencePlan.Ready {
        val token = lease.credentials.accessToken
        val request = if (protocol == WireProtocol.MESSAGES) {
            val metadata = fields.ranges["metadata"]
            val rewritten = metadata?.let { range -> withAccount(body.substring(range.first, range.last + 1), lease.account.providerAccountId)?.let { range to it } }
            val beta = (clientHeaders["anthropic-beta"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList())
                .let { if (OAUTH_BETA in it) it else it + OAUTH_BETA }
            UpstreamRequest(
                "${endpoints.anthropic}/v1/messages",
                anthropicHeaders(clientHeaders, beta.joinToString(",")) + ("authorization" to "Bearer $token"),
                TopLevelJson.rewrite(body, fields, listOfNotNull(rewritten).toMap()),
            )
        } else {
            val store = fields.ranges["store"]
            val rewritten = if (store != null) {
                TopLevelJson.rewrite(body, fields, mapOf(store to "false"))
            } else {
                TopLevelJson.rewrite(body, fields, emptyMap(), listOf("\"store\":false"))
            }
            UpstreamRequest(
                "${endpoints.codex}/responses",
                listOfNotNull(
                    "content-type" to "application/json",
                    "accept" to "text/event-stream",
                    "accept-encoding" to "identity",
                    "originator" to "codex_cli_rs",
                    "chatgpt-account-id" to lease.account.providerAccountId,
                    clientHeaders["openai-beta"]?.let { "openai-beta" to it },
                    "authorization" to "Bearer $token",
                ),
                rewritten,
            )
        }
        return InferencePlan.Ready(request, if (protocol == WireProtocol.MESSAGES) InferenceProvider.CLAUDE_SHARED else InferenceProvider.CODEX_SHARED, lease, model, stream)
    }

    private fun byok(provider: InferenceProvider, protocol: WireProtocol, body: String, clientHeaders: Map<String, String>, key: String): UpstreamRequest = when (provider) {
        InferenceProvider.ANTHROPIC -> UpstreamRequest(
            "${endpoints.anthropic}/v1/messages",
            anthropicHeaders(clientHeaders, clientHeaders["anthropic-beta"]) + ("x-api-key" to key),
            body,
        )
        else -> {
            val base = if (provider == InferenceProvider.OPENROUTER) endpoints.openrouter else endpoints.openai
            val path = if (protocol == WireProtocol.RESPONSES) "/v1/responses" else "/v1/chat/completions"
            UpstreamRequest(
                base + path,
                listOfNotNull(
                    "content-type" to "application/json",
                    "accept-encoding" to "identity",
                    clientHeaders["accept"]?.let { "accept" to it },
                    clientHeaders["openai-beta"]?.let { "openai-beta" to it },
                    "authorization" to "Bearer $key",
                ),
                body,
            )
        }
    }

    private fun anthropicHeaders(clientHeaders: Map<String, String>, beta: String?): List<Pair<String, String>> = listOfNotNull(
        "content-type" to "application/json",
        "accept-encoding" to "identity",
        "anthropic-version" to (clientHeaders["anthropic-version"] ?: DEFAULT_ANTHROPIC_VERSION),
        beta?.takeIf { it.isNotEmpty() }?.let { "anthropic-beta" to it },
        clientHeaders["accept"]?.let { "accept" to it },
    )

    private fun refuse(kind: RefusalKind, message: String, model: String, retryAt: Instant? = null) =
        InferencePlan.Refused(InferenceRefusal(kind, message, retryAt), model)

    companion object {
        /** The beta flag Anthropic requires for OAuth (subscription) tokens. */
        const val OAUTH_BETA = "oauth-2025-04-20"

        /** Sent when the agent names no API version. */
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"

        private const val MAX_MODEL = 200
        private val FIELDS = setOf("model", "stream", "metadata", "store")
        private val LEGACY_ACCOUNT = Regex("_account_[0-9a-fA-F-]+_session_")

        private val InferenceProvider.label: String
            get() = when (this) {
                InferenceProvider.ANTHROPIC, InferenceProvider.CLAUDE_SHARED -> "Anthropic"
                InferenceProvider.OPENAI, InferenceProvider.CODEX_SHARED -> "OpenAI"
                InferenceProvider.OPENROUTER -> "OpenRouter"
            }

        /**
         * Claude Code names the signed-in account in `metadata.user_id` (a JSON string with `account_uuid`, or the
         * older `user_<hash>_account_<uuid>_session_<uuid>`). With a shared account, the request must name the account
         * whose token it carries, as TeamCode does.
         *
         * @return The rewritten `metadata` value, or null when there is nothing to rewrite.
         */
        internal fun withAccount(metadata: String, accountId: String): String? {
            val json = try {
                Json.parseToJsonElement(metadata) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            val userId = (json["user_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            val replaced = try {
                val inner = Json.parseToJsonElement(userId).jsonObject
                if ("account_uuid" !in inner) return null
                JsonObject(inner + ("account_uuid" to JsonPrimitive(accountId))).toString()
            } catch (_: SerializationException) {
                if (!LEGACY_ACCOUNT.containsMatchIn(userId)) return null
                LEGACY_ACCOUNT.replace(userId, Regex.escapeReplacement("_account_${accountId}_session_"))
            } catch (_: IllegalArgumentException) {
                return null
            }
            return JsonObject(json + ("user_id" to JsonPrimitive(replaced))).toString()
        }
    }
}
