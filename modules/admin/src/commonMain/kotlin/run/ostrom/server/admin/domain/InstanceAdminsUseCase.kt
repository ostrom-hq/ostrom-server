// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.domain

import run.ostrom.server.kernel.settings.InstanceAccount
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Who calls the instance's administration.
 *
 * @property userId The account behind the credential.
 * @property person True for a person's own credential (a browser session or the unlocked app), never a token a
 *   program can present: the instance's settings are an interactive administrator's, as TeamCode's dashboard settings.
 */
data class AdminCaller(val userId: Uuid, val person: Boolean)

/**
 * An administrator as the console shows them.
 *
 * @property admin The administrator.
 * @property account Their account, when it still exists.
 */
data class InstanceAdminView(val admin: InstanceAdmin, val account: InstanceAccount?)

/** The instance's administrators, read and changed by an administrator. */
interface InstanceAdminsUseCase {
    /**
     * Refuses anyone but a person who administers the instance.
     *
     * @param caller The caller.
     *
     * @throws InstanceAdminRequiredException Otherwise.
     */
    suspend fun requireAdmin(caller: AdminCaller)

    /**
     * Lists the administrators.
     *
     * @param caller An administrator.
     *
     * @return They, oldest first.
     */
    suspend fun list(caller: AdminCaller): List<InstanceAdminView>

    /**
     * Makes the account of a verified e-mail an administrator.
     *
     * @param caller An administrator.
     * @param email The address.
     *
     * @return The administrators.
     *
     * @throws AccountNotFoundException When no account verified it.
     */
    suspend fun add(caller: AdminCaller, email: String): List<InstanceAdminView>

    /**
     * Removes an administrator.
     *
     * @param caller An administrator.
     * @param userId The one removed.
     *
     * @throws InstanceAdminNotFoundException When they are not one.
     * @throws LastInstanceAdminException When they are the last.
     */
    suspend fun remove(caller: AdminCaller, userId: Uuid)
}

/**
 * [InstanceAdminsUseCase] on the stored administrators; every change goes to the instance's audit log.
 *
 * @param admins The administrators.
 * @param accounts The accounts of the instance.
 * @param audit The instance's audit log.
 * @param clock The clock.
 */
class InstanceAdminsUseCaseImpl(
    private val admins: InstanceAdminsRepository,
    private val accounts: InstanceAccounts,
    private val audit: InstanceAuditTrail,
    private val clock: Clock,
) : InstanceAdminsUseCase {
    override suspend fun requireAdmin(caller: AdminCaller) {
        if (!caller.person || !admins.isAdmin(caller.userId)) throw InstanceAdminRequiredException()
    }

    override suspend fun list(caller: AdminCaller): List<InstanceAdminView> {
        requireAdmin(caller)
        return views()
    }

    override suspend fun add(caller: AdminCaller, email: String): List<InstanceAdminView> {
        requireAdmin(caller)
        val account = accounts.byVerifiedEmail(email.trim()) ?: throw AccountNotFoundException()
        val added = admins.add(InstanceAdmin(account.userId, AdminSource.DASHBOARD, clock.nowMillis(), caller.userId))
        if (added) {
            audit.record(InstanceAuditRecord(caller.userId, "instance.admin.added", SUBJECT, account.userId.toString()))
        }
        return views()
    }

    override suspend fun remove(caller: AdminCaller, userId: Uuid) {
        requireAdmin(caller)
        when (admins.remove(userId, caller.userId, clock.nowMillis())) {
            RemoveOutcome.NOT_FOUND -> throw InstanceAdminNotFoundException()
            RemoveOutcome.LAST -> throw LastInstanceAdminException()
            RemoveOutcome.REMOVED ->
                audit.record(InstanceAuditRecord(caller.userId, "instance.admin.removed", SUBJECT, userId.toString()))
        }
    }

    private suspend fun views(): List<InstanceAdminView> {
        val list = admins.list()
        val described = accounts.describe(list.map { it.userId })
        return list.map { InstanceAdminView(it, described[it.userId]) }
    }

    private companion object {
        const val SUBJECT = "instance_admin"
    }
}
