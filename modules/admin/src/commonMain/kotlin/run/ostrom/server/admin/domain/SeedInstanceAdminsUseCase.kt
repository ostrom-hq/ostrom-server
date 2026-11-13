// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.domain

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAdminGrants
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Seeds the instance's administrators at start: the people who used the setup link, then, once, the former
 * `OSTROM_INSTANCE_ADMINS`. A person removed by an administrator is never brought back by either.
 */
interface SeedInstanceAdminsUseCase {
    /**
     * Seeds.
     *
     * @param legacyAdmins The value of the former `OSTROM_INSTANCE_ADMINS` (user ids separated by commas), or null.
     */
    suspend operator fun invoke(legacyAdmins: String?)
}

/**
 * [SeedInstanceAdminsUseCase], also the [InstanceAdminGrants] the identity module calls when the setup link is used,
 * and the [InstanceAdministrators] other modules ask.
 *
 * @param admins The administrators.
 * @param accounts The accounts and the setup link's people.
 * @param audit The instance's audit log.
 * @param logger Where the deprecation of the variable is said.
 * @param clock The clock.
 */
class SeedInstanceAdminsUseCaseImpl(
    private val admins: InstanceAdminsRepository,
    private val accounts: InstanceAccounts,
    private val audit: InstanceAuditTrail,
    private val logger: Logger,
    private val clock: Clock,
) : SeedInstanceAdminsUseCase, InstanceAdminGrants, InstanceAdministrators {
    override suspend fun invoke(legacyAdmins: String?) {
        accounts.setupOwners().forEach { (userId, at) -> grantSetupOwner(userId, at) }
        if (legacyAdmins == null) return
        // Only an account of this instance: an id of nobody would count as an administrator nobody can be.
        val entries = legacyAdmins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = entries.mapNotNull { Uuid.parseOrNull(it) }
        val known = accounts.describe(parsed)
        val ids = parsed.filter { it in known }
        val imported = ids.count { userId ->
            admins.addIfNeverSeen(InstanceAdmin(userId, AdminSource.ENVIRONMENT, clock.nowMillis())).also { added ->
                if (added) {
                    audit.record(InstanceAuditRecord(null, "instance.admin.imported", SUBJECT, userId.toString()))
                }
            }
        }
        logger.warn(
            "OSTROM_INSTANCE_ADMINS is deprecated: the instance's administrators are managed in the console " +
                "(Admin → Administrators). Remove the variable.",
            // What was left out (not a user id of an account here) is counted, so an operator sees nobody is lost.
            mapOf("imported" to imported, "ignored" to entries.size - ids.size),
        )
    }

    override suspend fun grantSetupOwner(userId: Uuid, at: Instant) {
        if (admins.addIfNeverSeen(InstanceAdmin(userId, AdminSource.SETUP, at))) {
            audit.record(InstanceAuditRecord(null, "instance.admin.setup", SUBJECT, userId.toString()))
        }
    }

    override suspend fun isAdmin(userId: Uuid): Boolean = admins.isAdmin(userId)

    private companion object {
        const val SUBJECT = "instance_admin"
    }
}
