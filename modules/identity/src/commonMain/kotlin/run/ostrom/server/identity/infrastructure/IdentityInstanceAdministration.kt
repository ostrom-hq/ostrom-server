// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.identity.domain.SetupLinksRepository
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.kernel.settings.InstanceAccount
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [InstanceAccounts] on the identity module's accounts and setup links, for the instance's administration.
 *
 * @param users The accounts.
 * @param setupLinks The setup links.
 */
class IdentityInstanceAccounts(
    private val users: UsersRepository,
    private val setupLinks: SetupLinksRepository,
) : InstanceAccounts {
    override suspend fun byVerifiedEmail(email: String): InstanceAccount? =
        users.findByVerifiedEmail(email.trim().lowercase())?.toAccount()

    override suspend fun describe(userIds: Collection<Uuid>): Map<Uuid, InstanceAccount> =
        if (userIds.isEmpty()) emptyMap() else users.getMany(userIds).associate { it.id to it.toAccount() }

    override suspend fun setupOwners(): Map<Uuid, Instant> = setupLinks.owners()

    private fun User.toAccount() = InstanceAccount(id, login, primaryEmail)
}

/**
 * [InstanceAuditTrail] on the signed audit log, in the instance's chain (no organization): the changes of the
 * instance's administrators and settings, read by the administrator who made them.
 *
 * @param log The audit log.
 */
class IdentityInstanceAuditTrail(private val log: AuditLog) : InstanceAuditTrail {
    override suspend fun record(record: InstanceAuditRecord) {
        val details = if (record.actorId == null) record.details + ("actor_kind" to "server") else record.details
        log.record(AuditEntry(null, record.actorId, record.event, record.subjectType, record.subjectId, details))
    }
}
