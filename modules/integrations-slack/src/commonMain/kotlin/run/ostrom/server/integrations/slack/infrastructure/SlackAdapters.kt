// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UserEmail
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.domain.SlackMember
import run.ostrom.server.integrations.slack.domain.SlackPeople
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import kotlin.uuid.Uuid

/**
 * [SlackPeople] on the orgs module's members and the identity module's people (the module binds their repositories'
 * reads): the primary address only once it is proven, so a link never follows an address someone merely typed.
 *
 * @param members A membership, by organization and id.
 * @param users A person.
 * @param emails A person's addresses.
 * @param orgs An organization.
 */
class MemberSlackPeople(
    private val members: suspend (Uuid, Uuid) -> Member?,
    private val users: suspend (Uuid) -> User?,
    private val emails: suspend (Uuid) -> List<UserEmail>,
    private val orgs: suspend (Uuid) -> Org?,
) : SlackPeople {
    override suspend fun member(orgId: Uuid, memberId: Uuid): SlackMember? {
        val member = members(orgId, memberId)
        val user = member?.let { users(it.userId) }
        val org = user?.let { orgs(orgId) }
        if (member == null || user == null || org == null) return null
        val proven = emails(user.id).filter { it.verified }.map { it.email.lowercase() }
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
