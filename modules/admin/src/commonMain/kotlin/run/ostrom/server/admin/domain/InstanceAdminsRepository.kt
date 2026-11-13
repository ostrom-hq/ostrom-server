// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The instance's administrators, removed ones kept so an import never brings them back. */
interface InstanceAdminsRepository {
    /**
     * The current administrators.
     *
     * @return They, oldest first.
     */
    suspend fun list(): List<InstanceAdmin>

    /**
     * Whether a person is a current administrator.
     *
     * @param userId The person.
     *
     * @return True when they are.
     */
    suspend fun isAdmin(userId: Uuid): Boolean

    /**
     * Records an administrator unless the person is one or was one (a removed administrator stays removed): for the
     * setup link's person and the import from the environment.
     *
     * @param admin The administrator.
     *
     * @return True when recorded.
     */
    suspend fun addIfNeverSeen(admin: InstanceAdmin): Boolean

    /**
     * Makes a person an administrator again or for the first time (an administrator's decision).
     *
     * @param admin The administrator.
     *
     * @return True when they were not one.
     */
    suspend fun add(admin: InstanceAdmin): Boolean

    /**
     * Removes an administrator, unless they are the last one.
     *
     * @param userId The person.
     * @param by Who removes them.
     * @param at When.
     *
     * @return What happened.
     */
    suspend fun remove(userId: Uuid, by: Uuid, at: Instant): RemoveOutcome
}

/** The outcome of [InstanceAdminsRepository.remove]. */
enum class RemoveOutcome {
    /** Removed. */
    REMOVED,

    /** They are not a current administrator. */
    NOT_FOUND,

    /** They are the last one: kept. */
    LAST,
}
