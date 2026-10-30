// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.domain

import dev.ostrom.server.sharing.domain.AccountLease
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the request each provider expects. Only the agent headers the provider needs are passed on, never the
 * agent's own credential.
 *
 * @param endpoints The provider URLs.
 */
class UpstreamRequests(private val endpoints: ProviderEndpoints) {
    /** A call served by a shared subscription account: Claude for Messages, Codex for Responses. */
    fun shared(call: InferenceCall, body: RequestBody, lease: AccountLease): InferencePlan.Ready {
        val claude = call.protocol == WireProtocol.MESSAGES
        val request = if (claude) claude(call, body, lease) else codex(call, body, lease)
        val provider = if (claude) InferenceProvider.CLAUDE_SHARED else InferenceProvider.CODEX_SHARED
        return InferencePlan.Ready(request, provider, lease, body.model, body.stream)
    }

    /** A call served by the API key of [provider]. */
    fun byKey(provider: InferenceProvider, call: InferenceCall, body: RequestBody, key: String): InferencePlan.Ready {
        val request = if (provider == InferenceProvider.ANTHROPIC) {
            UpstreamRequest(
                "${endpoints.anthropic}/v1/messages",
                anthropicHeaders(call.headers, call.headers[ANTHROPIC_BETA]) + ("x-api-key" to key),
                body.render(),
            )
        } else {
            val base = if (provider == InferenceProvider.OPENROUTER) endpoints.openrouter else endpoints.openai
            val path = if (call.protocol == WireProtocol.RESPONSES) "/v1/responses" else "/v1/chat/completions"
            val headers = listOfNotNull(
                CONTENT_TYPE,
                IDENTITY,
                call.headers["accept"]?.let { "accept" to it },
                call.headers[OPENAI_BETA]?.let { OPENAI_BETA to it },
                "authorization" to "Bearer $key",
            )
            UpstreamRequest(base + path, headers, body.render())
        }
        return InferencePlan.Ready(request, provider, null, body.model, body.stream)
    }

    /**
     * Claude with a subscription token: the OAuth beta flag, and `metadata.user_id` naming the account whose token
     * the request carries, as Claude Code does.
     */
    private fun claude(call: InferenceCall, body: RequestBody, lease: AccountLease): UpstreamRequest {
        val metadata = body.fields.ranges[RequestBody.METADATA]
        val rewritten = metadata?.let { range ->
            ClaudeMetadata.withAccount(body.text.substring(range), lease.account.providerAccountId)?.let { range to it }
        }
        val asked = call.headers[ANTHROPIC_BETA].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val betas = if (OAUTH_BETA in asked) asked else asked + OAUTH_BETA
        return UpstreamRequest(
            "${endpoints.anthropic}/v1/messages",
            anthropicHeaders(call.headers, betas.joinToString(",")) +
                ("authorization" to "Bearer ${lease.credentials.accessToken}"),
            body.render(listOfNotNull(rewritten).toMap()),
        )
    }

    /** Codex with a ChatGPT token: its backend, the account header, and nothing stored by OpenAI. */
    private fun codex(call: InferenceCall, body: RequestBody, lease: AccountLease): UpstreamRequest {
        val store = body.fields.ranges[RequestBody.STORE]
        val rendered = if (store == null) {
            body.render(members = listOf("\"${RequestBody.STORE}\":false"))
        } else {
            body.render(mapOf(store to "false"))
        }
        val headers = listOfNotNull(
            CONTENT_TYPE,
            "accept" to "text/event-stream",
            IDENTITY,
            "originator" to "codex_cli_rs",
            "chatgpt-account-id" to lease.account.providerAccountId,
            call.headers[OPENAI_BETA]?.let { OPENAI_BETA to it },
            "authorization" to "Bearer ${lease.credentials.accessToken}",
        )
        return UpstreamRequest("${endpoints.codex}/responses", headers, rendered)
    }

    private fun anthropicHeaders(headers: Map<String, String>, beta: String?): List<Pair<String, String>> =
        listOfNotNull(
            CONTENT_TYPE,
            IDENTITY,
            "anthropic-version" to (headers["anthropic-version"] ?: DEFAULT_ANTHROPIC_VERSION),
            beta?.takeIf { it.isNotEmpty() }?.let { ANTHROPIC_BETA to it },
            headers["accept"]?.let { "accept" to it },
        )

    /** Headers and versions. */
    companion object {
        /** The beta flag Anthropic requires for OAuth (subscription) tokens. */
        const val OAUTH_BETA = "oauth-2025-04-20"

        /** Sent when the agent names no API version. */
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"

        private const val ANTHROPIC_BETA = "anthropic-beta"
        private const val OPENAI_BETA = "openai-beta"
        private val CONTENT_TYPE = "content-type" to "application/json"

        // The meter reads usage from the bytes as they pass: they must not be compressed.
        private val IDENTITY = "accept-encoding" to "identity"
    }
}

/**
 * Claude Code names the signed-in account in `metadata.user_id`: a JSON string with `account_uuid`, or the older
 * `user_<hash>_account_<uuid>_session_<uuid>`. With a shared account, the request must name the account whose token
 * it carries.
 */
internal object ClaudeMetadata {
    private val LEGACY_ACCOUNT = Regex("_account_[0-9a-fA-F-]+_session_")

    /** The rewritten `metadata` value, or null when there is nothing to rewrite. */
    fun withAccount(metadata: String, accountId: String): String? = parseObject(metadata)?.let { json ->
        (json["user_id"] as? JsonPrimitive)?.takeIf { it.isString }
            ?.let { replaceAccount(it.content, accountId) }
            ?.let { JsonObject(json + ("user_id" to JsonPrimitive(it))).toString() }
    }

    private fun replaceAccount(userId: String, accountId: String): String? {
        val inner = parseObject(userId)
        return when {
            inner != null -> inner.takeIf { "account_uuid" in it }
                ?.let { JsonObject(it + ("account_uuid" to JsonPrimitive(accountId))).toString() }
            LEGACY_ACCOUNT.containsMatchIn(userId) ->
                LEGACY_ACCOUNT.replace(userId, Regex.escapeReplacement("_account_${accountId}_session_"))
            else -> null
        }
    }

    private fun parseObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}
