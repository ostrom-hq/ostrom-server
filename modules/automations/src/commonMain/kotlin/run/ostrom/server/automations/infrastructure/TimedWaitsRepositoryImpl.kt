// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.DueWait
import run.ostrom.server.automations.domain.TimedWaitsRepository
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitEventTarget
import run.ostrom.server.automations.domain.WaitResolver
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [TimedWaitsRepository] on PostgreSQL. The clock's scan and an event address read `automations_waits` across
 * organizations only through the functions of [AutomationsMigrations] version 6, owned by `ostrom_router`, which
 * return ids, organizations and a secret's hash; each wait is then resolved confined to its organization (`inOrg`),
 * with its `cloud.wait.resolved` event in the same transaction.
 *
 * @param database The serving role's pool.
 * @param events The organization's event feed.
 */
internal class TimedWaitsRepositoryImpl(
    private val database: JdbcDatabase,
    private val events: OrgEventPublisher,
) : TimedWaitsRepository {
    override suspend fun due(now: Instant, limit: Int): List<DueWait> = database.query {
        // The serving role alone runs the function; it returns ids and organizations, nothing of a wait's content.
        val d = AutomationsDueWaits
        d.of(now, limit).select(d.id, d.orgId, d.resolver)
            .map { DueWait(it[d.orgId], it[d.id], resolverOf(it[d.resolver])) }
    }

    override suspend fun eventTarget(id: Uuid): WaitEventTarget? = database.query {
        val e = AutomationsWaitEvent
        e.of(id).select(e.orgId, e.secretHash).firstOrNull()?.let { WaitEventTarget(it[e.orgId], it[e.secretHash]) }
    }

    override suspend fun settle(orgId: Uuid, id: Uuid, answer: WaitAnswer, due: Boolean): Wait? =
        database.changeInOrg(orgId, events, { it.event(OrgEventType.WAIT_RESOLVED, answer.action) }) {
            // The clock resolves a wait once its instant has come; an event, only a wait for an event. Either way the
            // first update on `status = 'open'` wins, and the answer and its wake are written at once.
            val w = AutomationsWaits
            val reached = if (due) {
                coalesce(w.dueAt, w.timeoutAt) lessEq answer.answeredAt
            } else {
                w.resolver eq WaitResolver.EVENT.wire
            }
            w.updateReturning(
                WAIT_COLUMNS,
                where = {
                    (w.id eq id) and (w.orgId eq orgId) and (w.status eq WaitStatus.OPEN.wire) and
                        (w.resolver neq WaitResolver.HUMAN.wire) and reached
                },
            ) {
                it[w.status] = WaitStatus.RESOLVED.wire
                it[w.answerAction] = answer.action
                it[w.answerValues] = JsonObject(answer.values).toString()
                it[w.answeredBy] = answer.author
                it[w.answeredAt] = answer.answeredAt
                it[w.resolvedAt] = answer.answeredAt
                it[w.wakePending] = w.sessionId.isNotNull()
            }.map { it.toWait() }
        }.firstOrNull()

    override suspend fun purgeSecrets(now: Instant, limit: Int): Int = database.query {
        // The serving role alone runs the function; it deletes one batch and returns how many, never their content.
        scalar(purgeWaitSecrets(now, limit)).toInt()
    }
}
