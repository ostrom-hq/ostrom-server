// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.DueWait
import run.ostrom.server.automations.domain.TimedWaitsRepository
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitEventTarget
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [TimedWaitsRepository] on PostgreSQL. The clock's scan and an event address read `automations_waits` across
 * organizations only through the functions of [AutomationsMigrations] version 6, owned by `ostrom_router`, which
 * return ids, organizations and a secret's hash; each wait is then resolved confined to its organization ([inTenant]),
 * with its `cloud.wait.resolved` event in the same transaction.
 *
 * @param driver The database.
 * @param events The organization's event feed.
 */
internal class TimedWaitsRepositoryImpl(
    private val driver: Driver,
    private val events: OrgEventPublisher,
) : TimedWaitsRepository {
    override suspend fun due(now: Instant, limit: Int): List<DueWait> = driver.fetchAll(
        // The serving role alone runs the function; it returns ids and organizations, nothing of a wait's content.
        Statement.create(
            """
            SELECT id, org_id, resolver FROM automations_due_waits(CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER))
            """,
        ).bind("now", now).bind("limit", limit),
    ).getOrThrow().map { row ->
        DueWait(row.get("org_id").asUuid(), row.get("id").asUuid(), resolverOf(row.get("resolver").asString()))
    }

    override suspend fun eventTarget(id: Uuid): WaitEventTarget? = driver.fetchAll(
        Statement.create("""SELECT org_id, secret_hash FROM automations_wait_event(CAST(:id AS UUID))""")
            .bind("id", id),
    ).getOrThrow().firstOrNull()?.let { WaitEventTarget(it.get("org_id").asUuid(), it.get("secret_hash").asString()) }

    override suspend fun settle(orgId: Uuid, id: Uuid, answer: WaitAnswer, due: Boolean): Wait? {
        // The clock resolves a wait once its instant has come; an event, only a wait for an event. Either way the first
        // update on `status = 'open'` wins, and the answer and its wake are written at once.
        val condition = if (due) "COALESCE(due_at, timeout_at) <= :at" else "resolver = 'event'"
        return driver.changeInOrg(orgId, events, { it.event(OrgEventType.WAIT_RESOLVED, answer.action) }) {
            Statement.create(
                """
                UPDATE automations_waits SET status = 'resolved', answer_action = :action, answer_values = :values,
                answered_by = :author, answered_at = :at, resolved_at = :at, wake_pending = session_id IS NOT NULL
                WHERE id = :id AND org_id = :org_id AND status = 'open' AND resolver <> 'human' AND $condition
                RETURNING $WAIT_COLUMNS
                """,
            ).bind("action", answer.action)
                .bind("values", JsonObject(answer.values).toString())
                .bind("author", answer.author)
                .bind("at", answer.answeredAt)
                .bind("id", id)
                .bind("org_id", orgId)
        }.firstOrNull()
    }
}
