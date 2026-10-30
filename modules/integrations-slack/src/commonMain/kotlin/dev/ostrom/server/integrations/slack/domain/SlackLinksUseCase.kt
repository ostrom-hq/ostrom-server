// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Who of an organization is reached on Slack; an owner or an admin sets it (the routes check the role). */
interface SlackLinksUseCase {
    /**
     * @param orgId The organization.
     * @return Its links.
     */
    suspend fun list(orgId: Uuid): List<SlackLink>

    /**
     * Links a member to their Slack account.
     *
     * @param orgId The organization.
     * @param personId The member.
     * @param slack The workspace and the member on Slack.
     * @param by Who links.
     * @return The link.
     * @throws OstromException 400 for a malformed id, 409 when this Slack member is someone else already.
     */
    suspend fun link(orgId: Uuid, personId: String, slack: Pair<String, String>, by: String): SlackLink

    /**
     * Unlinks a member: their requests wait in the console again.
     *
     * @param orgId The organization.
     * @param personId The member.
     * @throws OstromException 404 when they were not linked.
     */
    suspend fun unlink(orgId: Uuid, personId: String)
}

/**
 * [SlackLinksUseCase] on the repository.
 *
 * @param repository Links.
 * @param clock The time.
 */
class SlackLinksUseCaseImpl(
    private val repository: SlackRepository,
    private val clock: Clock,
) : SlackLinksUseCase {
    override suspend fun list(orgId: Uuid): List<SlackLink> = repository.links(orgId)

    override suspend fun link(orgId: Uuid, personId: String, slack: Pair<String, String>, by: String): SlackLink {
        val (teamId, userId) = slack.first.trim() to slack.second.trim()
        val member = personId.isNotBlank() && personId.length <= MAX_ID
        val valid = member && TEAM.matches(teamId) && USER.matches(userId)
        if (!valid) throw OstromException(ErrorKind.INVALID_REQUEST, "A Slack link needs a member, a T… and a U… id")
        val link = SlackLink(orgId, personId, teamId, userId, by, clock.now())
        if (!repository.saveLink(link)) {
            throw OstromException(ErrorKind.CONFLICT, "This Slack account is already linked to another member.")
        }
        return link
    }

    override suspend fun unlink(orgId: Uuid, personId: String) {
        if (!repository.deleteLink(orgId, personId)) {
            throw OstromException(ErrorKind.NOT_FOUND, "This member is not linked to Slack.")
        }
    }

    private companion object {
        const val MAX_ID = 200
        val TEAM = Regex("T[A-Z0-9]{2,30}")
        val USER = Regex("[UW][A-Z0-9]{2,30}")
    }
}
