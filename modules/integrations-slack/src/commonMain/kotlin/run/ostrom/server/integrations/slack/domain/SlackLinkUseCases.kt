// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import kotlinx.coroutines.CancellationException
import run.ostrom.core.api.models.SlackLink
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Reads a member's own Slack link. */
fun interface GetSlackLinkUseCase {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Their link, and whether Slack is configured.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid): SlackLink
}

/** Links a member to the Slack account of their proven e-mail address. */
fun interface LinkSlackUseCase {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Their link.
     * @throws SlackNotConfiguredException When no bot token is stored.
     * @throws SlackEmailUnprovenException When they have no proven address.
     * @throws SlackUserNotFoundException When Slack has no active person with it.
     * @throws SlackUserTakenException When another member of the organization linked that Slack account.
     * @throws SlackUnavailableException When Slack does not answer.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid): SlackLink
}

/** Removes a member's own Slack link; nothing when there is none. */
fun interface UnlinkSlackUseCase {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid)
}

/**
 * [GetSlackLinkUseCase] on the stored links.
 *
 * @param apps The Slack app.
 * @param links The links.
 */
class GetSlackLinkUseCaseImpl(
    private val apps: SlackApps,
    private val links: SlackLinksRepository,
) : GetSlackLinkUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid): SlackLink =
        links.of(orgId, memberId).toDto(memberId, apps.current() != null)
}

/**
 * [LinkSlackUseCase]: the member never types a Slack id, so nobody links another person's account. Slack's
 * `users.lookupByEmail` matches only an address it confirmed; the Ostrom address must be proven too.
 *
 * @param apps The Slack app.
 * @param api Slack.
 * @param people The members.
 * @param links The links.
 * @param texts The message the Slack account is told with.
 * @param clock The time.
 */
@Suppress("LongParameterList") // One port per owner: settings, Slack, people, links, texts, clock.
class LinkSlackUseCaseImpl(
    private val apps: SlackApps,
    private val api: SlackApi,
    private val people: SlackPeople,
    private val links: SlackLinksRepository,
    private val texts: SlackTexts,
    private val clock: Clock,
) : LinkSlackUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid): SlackLink {
        val app = apps.current() ?: throw SlackNotConfiguredException()
        val member = people.member(orgId, memberId) ?: throw SlackUserNotFoundException()
        val email = member.provenEmail ?: throw SlackEmailUnprovenException()
        val user = try {
            api.lookupByEmail(app.botToken, email)
        } catch (error: SlackCallException) {
            if (error.retryable) throw SlackUnavailableException()
            // A refused token or a missing scope is the administrator's to fix: the member is told Slack is not set.
            throw SlackNotConfiguredException()
        }
        if (user == null || user.deleted || user.bot) throw SlackUserNotFoundException()
        // A Slack account answers for one member: its Reject is that member's no (UNIQUE in the table too).
        if (links.memberOf(orgId, user.id).let { it != null && it != memberId }) throw SlackUserTakenException()
        // Stored first: the confirmation below is best effort and never undoes the link.
        val link = StoredSlackLink(memberId, user.id, clock.now())
        links.save(orgId, link)
        tell(app, user.id, texts.linked(member.login, member.orgName))
        return link.toDto(memberId, configured = true)
    }

    // Best effort: the link stands even when the message does not go.
    private suspend fun tell(app: SlackApp, userId: String, message: SlackMessage) {
        try {
            api.postMessage(app.botToken, userId, message)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Unit
        }
    }
}

/**
 * [UnlinkSlackUseCase] on the stored links.
 *
 * @param links The links.
 */
class UnlinkSlackUseCaseImpl(private val links: SlackLinksRepository) : UnlinkSlackUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid) {
        links.remove(orgId, memberId)
    }
}

private fun StoredSlackLink?.toDto(memberId: Uuid, configured: Boolean): SlackLink = SlackLink(
    memberId = memberId.toString(),
    configured = configured,
    linked = this != null,
    slackUserId = this?.slackUserId,
    linkedAt = this?.run { linkedAt.toString() },
)
