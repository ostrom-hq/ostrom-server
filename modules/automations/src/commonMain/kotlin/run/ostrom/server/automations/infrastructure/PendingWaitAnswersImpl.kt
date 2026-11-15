// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.sessions.PendingWaitAnswers
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [PendingWaitAnswers] on the waits: one indexed read through the partial index of the answers not handed over
 * (`automations_waits_wake`), confined to the organization.
 *
 * @param database The serving role's pool.
 */
internal class PendingWaitAnswersImpl(private val database: JdbcDatabase) : PendingWaitAnswers {
    override suspend fun answeredSince(orgId: Uuid, sessionId: String, since: Instant): Boolean =
        database.inOrg(orgId) {
            val w = AutomationsWaits
            !w.select(w.id)
                .where {
                    (w.orgId eq orgId) and (w.sessionId eq sessionId) and w.wakePending and
                        (w.resolvedAt greater since)
                }
                .empty()
        }
}
