// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.domain

import run.ostrom.server.kernel.settings.InstanceAdministrators
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The caller of the instance's cloud administration.
 *
 * @property userId The account, or null for a credential that is no account's.
 * @property person Whether the credential is the person's own, in the browser or the unlocked app.
 */
data class CloudAdminCaller(val userId: Uuid?, val person: Boolean)

/**
 * Lets in a person who administers the instance only (the admin module's administrators), never a key.
 *
 * @param administrators Who administers the instance.
 */
class CloudAdminGate(private val administrators: InstanceAdministrators) {
    /**
     * Refuses anyone else.
     *
     * @param caller The caller.
     *
     * @return The administrator's account.
     *
     * @throws CloudAdminRequiredException Otherwise.
     */
    suspend fun require(caller: CloudAdminCaller): Uuid {
        val userId = caller.userId?.takeIf { caller.person && administrators.isAdmin(it) }
        return userId ?: throw CloudAdminRequiredException()
    }
}

/** Now, to the millisecond PostgreSQL keeps, so an optimistic save compares what a read returned. */
internal fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())
