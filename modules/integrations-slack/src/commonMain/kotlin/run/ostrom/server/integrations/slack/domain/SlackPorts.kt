// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import kotlinx.serialization.json.JsonArray
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A Slack call that did not succeed. Its [code] is ours or Slack's error code (`ratelimited`, `invalid_auth`), never
 * Slack's text nor a token.
 *
 * @property code What failed.
 * @property retryable Whether another attempt later may succeed (Slack unreachable, failing or asking to slow down).
 */
class SlackCallException(val code: String, val retryable: Boolean) : Exception("Slack call failed: $code")

/**
 * A person of the Slack workspace, as `users.lookupByEmail` answers.
 *
 * @property id The Slack user id.
 * @property deleted Whether the account is deactivated.
 * @property bot Whether it is a bot or an app user.
 */
data class SlackUser(val id: String, val deleted: Boolean, val bot: Boolean)

/**
 * A message the bot posted.
 *
 * @property channel The conversation (a direct message's `D…`).
 * @property ts Its timestamp, its id in that conversation.
 */
data class SlackPosted(val channel: String, val ts: String)

/**
 * A message: [text] is what a notification shows and the fallback, [blocks] its layout when it has one.
 *
 * @property text The text.
 * @property blocks Slack's Block Kit blocks, or null.
 */
data class SlackMessage(val text: String, val blocks: JsonArray? = null)

/** Slack's Web API, with the app's bot token. Every call throws [SlackCallException] when it does not succeed. */
interface SlackApi {
    /**
     * `auth.test`.
     *
     * @param token A bot token.
     *
     * @return Slack's error code when it refuses the token, or null when the token works.
     */
    suspend fun authTest(token: String): String?

    /**
     * `users.lookupByEmail`.
     *
     * @param token The bot token.
     * @param email The address.
     *
     * @return The person, or null when the workspace has none with this address.
     */
    suspend fun lookupByEmail(token: String, email: String): SlackUser?

    /**
     * `chat.postMessage` to a person: Slack opens the direct message with the app.
     *
     * @param token The bot token.
     * @param userId The Slack user.
     * @param message The message.
     *
     * @return Where it was posted.
     */
    suspend fun postMessage(token: String, userId: String, message: SlackMessage): SlackPosted

    /**
     * `chat.update`.
     *
     * @param token The bot token.
     * @param posted The message.
     * @param message What it says now.
     */
    suspend fun updateMessage(token: String, posted: SlackPosted, message: SlackMessage)

    /**
     * `chat.delete`: takes back a message of the bot (`chat:write` covers it).
     *
     * @param token The bot token.
     * @param posted The message.
     */
    suspend fun deleteMessage(token: String, posted: SlackPosted)
}

/**
 * A member's Slack link.
 *
 * @property memberId The member.
 * @property slackUserId The Slack user.
 * @property linkedAt When they linked it.
 */
data class StoredSlackLink(val memberId: Uuid, val slackUserId: String, val linkedAt: Instant)

/** The members' Slack links, per organization. */
interface SlackLinksRepository {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Their link, or null.
     */
    suspend fun of(orgId: Uuid, memberId: Uuid): StoredSlackLink?

    /**
     * @param orgId The organization.
     * @param memberIds The members.
     *
     * @return The links of those of [memberIds] who linked one, in one read.
     */
    suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredSlackLink>

    /**
     * @param orgId The organization.
     * @param slackUserId A Slack user.
     *
     * @return The member who linked it in the organization, or null.
     */
    suspend fun memberOf(orgId: Uuid, slackUserId: String): Uuid?

    /**
     * Links a member, replacing their former link.
     *
     * @param orgId The organization.
     * @param link The link.
     * @throws SlackUserTakenException When another member of the organization linked that Slack account meanwhile.
     */
    suspend fun save(orgId: Uuid, link: StoredSlackLink)

    /**
     * @param orgId The organization.
     * @param memberId The member.
     */
    suspend fun remove(orgId: Uuid, memberId: Uuid)

    /** @param orgId The organization whose links are forgotten. */
    suspend fun deleteAll(orgId: Uuid)
}

/**
 * A yes request's message in an approver's Slack.
 *
 * @property approvalId The approver's request.
 * @property gateId Its gate.
 * @property posted Where it is.
 */
data class SlackApprovalMessage(val approvalId: Uuid, val gateId: Uuid, val posted: SlackPosted)

/** The yes requests' messages, so they are updated once the gate is decided. */
interface SlackApprovalMessagesRepository {
    /**
     * Keeps a message; the request's former one, if any, is replaced.
     *
     * @param orgId The organization.
     * @param message The message.
     */
    suspend fun save(orgId: Uuid, message: SlackApprovalMessage)

    /**
     * @param orgId The organization.
     * @param approvalId An approver's request.
     *
     * @return The message posted for it, or null.
     */
    suspend fun of(orgId: Uuid, approvalId: Uuid): SlackApprovalMessage?

    /** @param orgId The organization whose messages are forgotten. */
    suspend fun deleteAll(orgId: Uuid)
}

/**
 * A member as the Slack link needs them.
 *
 * @property memberId The membership.
 * @property login Their login, the approver's name on a gate.
 * @property role Their role in the organization.
 * @property provenEmail Their primary address once proven, else null.
 * @property orgName The organization's name.
 */
data class SlackMember(
    val memberId: Uuid,
    val login: String,
    val role: OrgRole,
    val provenEmail: String?,
    val orgName: String,
)

/** The members of the organizations (identity and orgs modules). */
fun interface SlackPeople {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Them, or null when they are not a member any more.
     */
    suspend fun member(orgId: Uuid, memberId: Uuid): SlackMember?
}

/** HMAC-SHA256, the signature of Slack's requests. */
fun interface SlackSigner {
    /**
     * @param secret The signing secret.
     * @param text The signed text.
     *
     * @return The signature, in lower-case hex.
     */
    suspend fun sign(secret: String, text: String): String
}
