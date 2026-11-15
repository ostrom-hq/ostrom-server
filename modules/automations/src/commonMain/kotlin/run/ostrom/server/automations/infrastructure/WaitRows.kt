// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitResolver
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitTimer
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.ResultRow

/** The columns of `automations_waits` a wait is read from. */
internal val WAIT_COLUMNS = AutomationsWaits.let { w ->
    listOf(
        w.id, w.orgId, w.kind, w.status, w.sessionId, w.runId, w.assignee, w.createdBy, w.question, w.answerAction,
        w.answerValues, w.answeredBy, w.answeredAt, w.createdAt, w.resolvedAt, w.idempotencyKey, w.resolver, w.dueAt,
        w.timeoutAt, w.eventSource, w.resumeMessage, w.eventSecretHash,
    )
}

/** A wait read from a row of [WAIT_COLUMNS]. */
internal fun ResultRow.toWait(): Wait {
    val w = AutomationsWaits
    return Wait(
        id = this[w.id],
        orgId = this[w.orgId],
        kind = this[w.kind],
        status = WaitStatus.valueOf(this[w.status].uppercase()),
        sessionId = this[w.sessionId],
        runId = this[w.runId],
        assignee = this[w.assignee],
        createdBy = this[w.createdBy],
        question = OstromJson.decodeFromString(WaitSpec.serializer(), this[w.question]),
        answer = this[w.answerAction]?.let { action ->
            WaitAnswer(
                action = action,
                values = OstromJson.parseToJsonElement(present(w.answerValues, w.answerAction)).jsonObject,
                author = present(w.answeredBy, w.answerAction),
                answeredAt = present(w.answeredAt, w.answerAction),
            )
        },
        createdAt = this[w.createdAt],
        resolvedAt = this[w.resolvedAt],
        idempotencyKey = this[w.idempotencyKey],
        timer = resolverOf(this[w.resolver]).takeIf { it != WaitResolver.HUMAN }?.let { resolver ->
            WaitTimer(
                resolver = resolver,
                at = this[w.dueAt] ?: present(w.timeoutAt, w.resolver),
                source = this[w.eventSource],
                resumeMessage = this[w.resumeMessage],
                secretHash = this[w.eventSecretHash],
            )
        },
    )
}

/** The resolver a column holds. */
internal fun resolverOf(wire: String): WaitResolver = WaitResolver.entries.first { it.wire == wire }
