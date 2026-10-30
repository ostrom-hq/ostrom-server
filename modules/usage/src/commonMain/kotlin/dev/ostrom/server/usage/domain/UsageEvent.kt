// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a usage event measures. Only the server records [MODEL] (the gateway knows the tokens and the price); clients
 * declare the other kinds, which never carry a cost.
 */
enum class UsageKind { MODEL, MCP, SKILL, HOOK, SESSION }

/**
 * One line of the ledger. This type is a contract other modules (gateway, sharing, mcp-relay) record with: its names
 * and fields do not change.
 *
 * @property id The event.
 * @property orgId The org that pays; every query is scoped by it.
 * @property memberId The member it was spent for, or null for org-level work (an automation).
 * @property kind What was used.
 * @property name The model id, `"server/tool"`, the skill name, the hook event, or the session id.
 * @property provider The provider (anthropic, openai, openrouter, claude-max, codex…), when there is one.
 * @property accountId The pooled personal account used, if any (personal account sharing).
 * @property sessionId The agent session, when known.
 * @property inputTokens Input tokens billed.
 * @property outputTokens Output tokens billed.
 * @property cacheReadTokens Tokens read from the provider's prompt cache.
 * @property cacheWriteTokens Tokens written to the provider's prompt cache.
 * @property costMicros The cost in millionths of a US dollar.
 * @property durationMillis How long it took.
 * @property outcome `ok`, or a short error key.
 * @property at When it happened.
 */
data class UsageEvent(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid?,
    val kind: UsageKind,
    val name: String,
    val provider: String? = null,
    val accountId: Uuid? = null,
    val sessionId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val costMicros: Long = 0,
    val durationMillis: Long = 0,
    val outcome: String = "ok",
    val at: Instant,
)
