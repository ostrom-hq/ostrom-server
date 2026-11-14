// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.domain.SlackMember
import run.ostrom.server.integrations.slack.domain.SlackPeople
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.OrgsRepository
import kotlin.uuid.Uuid

/**
 * [SlackPeople] on the orgs module's members and the identity module's people: the primary address only once it is
 * proven, so a link never follows an address someone merely typed.
 *
 * @param members The members.
 * @param users The people.
 * @param orgs The organizations.
 */
class MemberSlackPeople(
    private val members: MembersRepository,
    private val users: UsersRepository,
    private val orgs: OrgsRepository,
) : SlackPeople {
    override suspend fun member(orgId: Uuid, memberId: Uuid): SlackMember? {
        val member = members.findById(orgId, memberId) ?: return null
        val user = users.get(member.userId) ?: return null
        val org = orgs.get(orgId) ?: return null
        val proven = users.listEmails(user.id).filter { it.verified }.map { it.email.lowercase() }
        val email = user.primaryEmail?.takeIf { it.lowercase() in proven }
        return SlackMember(member.id, user.login, member.role, email, org.name)
    }
}

/**
 * Forgets a deleted organization's Slack links and messages at once, and again at its purge.
 *
 * @param links The links.
 * @param messages The yes requests' messages.
 */
class SlackOrgDeletionListener(
    private val links: SlackLinksRepository,
    private val messages: SlackApprovalMessagesRepository,
) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) {
        links.deleteAll(orgId)
        messages.deleteAll(orgId)
    }

    override suspend fun purge(orgId: Uuid) {
        deleted(orgId)
    }
}
