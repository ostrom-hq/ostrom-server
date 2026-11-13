// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitResolver
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitTimer
import run.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.jsonObject

/** The columns of `automations_waits` a wait is read from. */
internal const val WAIT_COLUMNS = """
    id, org_id, kind, status, session_id, run_id, assignee, created_by, question, answer_action,
    answer_values, answered_by, answered_at, created_at, resolved_at, idempotency_key, resolver, due_at,
    timeout_at, event_source, resume_message, event_secret_hash
"""

/** A wait read from a row of [WAIT_COLUMNS]. */
internal fun ResultSet.Row.toWait(): Wait = Wait(
    id = get("id").asUuid(),
    orgId = get("org_id").asUuid(),
    kind = get("kind").asString(),
    status = WaitStatus.valueOf(get("status").asString().uppercase()),
    sessionId = get("session_id").asStringOrNull(),
    runId = get("run_id").asStringOrNull(),
    assignee = get("assignee").asString(),
    createdBy = get("created_by").asString(),
    question = OstromJson.decodeFromString(WaitSpec.serializer(), get("question").asString()),
    answer = get("answer_action").asStringOrNull()?.let { action ->
        WaitAnswer(
            action = action,
            values = OstromJson.parseToJsonElement(get("answer_values").asString()).jsonObject,
            author = get("answered_by").asString(),
            answeredAt = get("answered_at").asInstant(),
        )
    },
    createdAt = get("created_at").asInstant(),
    resolvedAt = get("resolved_at").asInstantOrNull(),
    idempotencyKey = get("idempotency_key").asStringOrNull(),
    timer = resolverOf(get("resolver").asString()).takeIf { it != WaitResolver.HUMAN }?.let { resolver ->
        WaitTimer(
            resolver = resolver,
            at = get("due_at").asInstantOrNull() ?: get("timeout_at").asInstant(),
            source = get("event_source").asStringOrNull(),
            resumeMessage = get("resume_message").asStringOrNull(),
            secretHash = get("event_secret_hash").asStringOrNull(),
        )
    },
)

/** The resolver a column holds. */
internal fun resolverOf(wire: String): WaitResolver = WaitResolver.entries.first { it.wire == wire }
