// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.kernel.sessions.PendingWaitAnswers
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [PendingWaitAnswers] on the waits: one indexed read through the partial index of the answers not handed over
 * (`automations_waits_wake`), confined to the organization.
 *
 * @param driver The database.
 */
internal class PendingWaitAnswersImpl(private val driver: Driver) : PendingWaitAnswers {
    override suspend fun answeredSince(orgId: Uuid, sessionId: String, since: Instant): Boolean = driver.fetchInOrg(
        orgId,
        Statement.create(
            """
            SELECT EXISTS (SELECT 1 FROM automations_waits WHERE org_id = :org_id AND session_id = :session_id AND
            wake_pending AND resolved_at > :since) AS pending
            """,
        ).bind("org_id", orgId).bind("session_id", sessionId).bind("since", since),
    ).getOrThrow().first().get("pending").asBoolean()
}
