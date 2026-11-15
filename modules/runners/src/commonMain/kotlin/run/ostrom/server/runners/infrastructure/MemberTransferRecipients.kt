// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.runners.domain.MemberTransferPolicy
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.domain.TransferRecipients
import kotlin.uuid.Uuid

/**
 * [TransferRecipients] on the organization's members: the picker lists every member (the caller included: an
 * administrator may take a session over), a recipient is named by id or login, and a member who left is no longer
 * eligible.
 *
 * @param members The members' roles and ids.
 * @param logins The members' logins.
 */
class MemberTransferRecipients(
    private val members: MemberDirectory,
    private val logins: MemberLogins,
) : TransferRecipients {
    private val administrators = setOf(OrgRole.OWNER, OrgRole.ADMIN)

    override suspend fun list(orgId: Uuid, limit: Int): List<TransferRecipient> {
        val roles = members.roles(orgId, limit) ?: return emptyList()
        val names = logins.loginsOf(orgId, roles.keys.mapNotNull(Uuid::parseOrNull))
        return roles.mapNotNull { (id, role) ->
            names[Uuid.parse(id)]?.let { TransferRecipient(id, it, role in administrators) }
        }.sortedBy { it.login.lowercase() }
    }

    override suspend fun find(orgId: Uuid, reference: String): String? {
        val id = Uuid.parseOrNull(reference)
        if (id != null) return id.toString()
        return logins.idsOf(orgId, listOf(reference)).values.firstOrNull()?.toString()
    }

    override suspend fun eligible(orgId: Uuid, memberId: String): Boolean = members.isActiveMember(orgId, memberId)
}

/**
 * [MemberTransferPolicy] on the instance's cloud runtime (`memberTransfers`, set in the console): `offer` lets
 * members offer their sessions, `disabled` leaves transfers to administrators.
 *
 * @param runtime The cloud runtime; null on a server without the cloud module, where members offer.
 */
class RuntimeMemberTransferPolicy(private val runtime: CloudRuntimeSource?) : MemberTransferPolicy {
    override suspend fun membersMayOffer(): Boolean =
        runtime == null || runtime.current().memberTransfers == CloudRuntime.TRANSFERS_OFFER
}
