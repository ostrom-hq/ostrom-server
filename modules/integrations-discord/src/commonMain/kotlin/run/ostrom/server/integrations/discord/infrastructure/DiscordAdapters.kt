// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.infrastructure

import run.ostrom.server.identity.domain.User
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttemptsRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinksRepository
import run.ostrom.server.integrations.discord.domain.DiscordMember
import run.ostrom.server.integrations.discord.domain.DiscordPeople
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import kotlin.uuid.Uuid

/**
 * [DiscordPeople] on the orgs module's members and the identity module's people (the module binds their
 * repositories' reads).
 *
 * @param members A membership, by organization and id.
 * @param users A person.
 * @param orgs An organization.
 */
class MemberDiscordPeople(
    private val members: suspend (Uuid, Uuid) -> Member?,
    private val users: suspend (Uuid) -> User?,
    private val orgs: suspend (Uuid) -> Org?,
) : DiscordPeople {
    override suspend fun member(orgId: Uuid, memberId: Uuid): DiscordMember? {
        val member = members(orgId, memberId)
        val user = member?.let { users(it.userId) }
        val org = user?.let { orgs(orgId) }
        if (member == null || user == null || org == null) return null
        return DiscordMember(member.id, user.login, member.role, org.name)
    }
}

/**
 * Forgets a deleted organization's Discord links, unfinished links and messages at once, and again at its purge.
 *
 * @param links The links.
 * @param attempts The unfinished links.
 * @param messages The yes requests' messages.
 */
class DiscordOrgDeletionListener(
    private val links: DiscordLinksRepository,
    private val attempts: DiscordLinkAttemptsRepository,
    private val messages: DiscordApprovalMessagesRepository,
) : OrgDeletionListener {
    override suspend fun deleted(orgId: Uuid) {
        links.deleteAll(orgId)
        attempts.deleteAll(orgId)
        messages.deleteAll(orgId)
    }

    override suspend fun purge(orgId: Uuid) {
        deleted(orgId)
    }
}
