// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import kotlinx.serialization.json.JsonArray
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A Discord call that did not succeed. Its [code] is ours or Discord's JSON error code (`discord_50007`), never
 * Discord's text nor a token.
 *
 * @property code What failed.
 * @property retryable Whether another attempt later may succeed (Discord unreachable, failing or asking to slow down).
 */
class DiscordCallException(val code: String, val retryable: Boolean) : Exception("Discord call failed: $code")

/**
 * A Discord account, as `/users/@me` answers.
 *
 * @property id The user id (a snowflake).
 * @property username Its username.
 */
data class DiscordUser(val id: String, val username: String)

/**
 * A message the bot posted.
 *
 * @property channelId The direct message channel.
 * @property messageId Its id in that channel.
 */
data class DiscordPosted(val channelId: String, val messageId: String)

/**
 * A message: [content] is its text (Discord's markdown, at most 2000 characters), [components] its buttons, if any.
 *
 * @property content The text.
 * @property components Discord's message components (action rows), or null.
 */
data class DiscordMessage(val content: String, val components: JsonArray? = null)

/** Discord's API. Every call throws [DiscordCallException] when it does not succeed. */
interface DiscordApi {
    /**
     * `GET /users/@me` with a bot token.
     *
     * @param botToken A bot token.
     *
     * @return Why Discord refuses it, or null when it works.
     * @throws DiscordCallException When Discord does not answer (retryable).
     */
    suspend fun checkBot(botToken: String): String?

    /**
     * `POST /oauth2/token`: exchanges the link's code for the person's access token.
     *
     * @param app The application.
     * @param code Discord's code.
     * @param redirectUri The redirect the authorization named.
     *
     * @return The access token (scope `identify`), used once and never kept.
     */
    suspend fun exchangeCode(app: DiscordApp, code: String, redirectUri: String): String

    /**
     * `GET /users/@me` with a person's access token.
     *
     * @param accessToken The token.
     *
     * @return The account.
     */
    suspend fun currentUser(accessToken: String): DiscordUser

    /**
     * `GET /guilds/{guildId}/members/{userId}`.
     *
     * @param app The application.
     * @param userId The account.
     *
     * @return Whether the account is a member of the instance's server.
     */
    suspend fun inGuild(app: DiscordApp, userId: String): Boolean

    /**
     * `POST /users/@me/channels`: the bot's direct message channel with a person.
     *
     * @param botToken The bot token.
     * @param userId The account.
     *
     * @return The channel id.
     */
    suspend fun openDm(botToken: String, userId: String): String

    /**
     * `POST /channels/{channelId}/messages`.
     *
     * @param botToken The bot token.
     * @param channelId The channel.
     * @param message The message.
     *
     * @return Where it was posted.
     */
    suspend fun postMessage(botToken: String, channelId: String, message: DiscordMessage): DiscordPosted

    /**
     * `PATCH /channels/{channelId}/messages/{messageId}`.
     *
     * @param botToken The bot token.
     * @param posted The message.
     * @param message What it says now.
     */
    suspend fun editMessage(botToken: String, posted: DiscordPosted, message: DiscordMessage)

    /**
     * `DELETE /channels/{channelId}/messages/{messageId}`.
     *
     * @param botToken The bot token.
     * @param posted The message.
     */
    suspend fun deleteMessage(botToken: String, posted: DiscordPosted)
}

/**
 * A member's Discord link.
 *
 * @property memberId The member.
 * @property discordUserId The Discord account.
 * @property username Its username when it was linked.
 * @property linkedAt When they linked it.
 * @property dmChannelId The bot's direct message channel with the account, once opened.
 */
data class StoredDiscordLink(
    val memberId: Uuid,
    val discordUserId: String,
    val username: String,
    val linkedAt: Instant,
    val dmChannelId: String? = null,
)

/** The members' Discord links, per organization. */
interface DiscordLinksRepository {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Their link, or null.
     */
    suspend fun of(orgId: Uuid, memberId: Uuid): StoredDiscordLink?

    /**
     * @param orgId The organization.
     * @param memberIds The members.
     *
     * @return The links of those of [memberIds] who linked one, in one read.
     */
    suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredDiscordLink>

    /**
     * @param orgId The organization.
     * @param discordUserId A Discord account.
     *
     * @return The member who linked it in the organization, or null.
     */
    suspend fun memberOf(orgId: Uuid, discordUserId: String): Uuid?

    /**
     * Links a member, replacing their former link (and its direct message channel).
     *
     * @param orgId The organization.
     * @param link The link.
     * @throws DiscordUserTakenException When another member of the organization linked that account meanwhile.
     */
    suspend fun save(orgId: Uuid, link: StoredDiscordLink)

    /**
     * Keeps the direct message channel of a link, when the link still names that account.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param discordUserId The account the channel was opened with.
     * @param channelId The channel.
     */
    suspend fun saveDmChannel(orgId: Uuid, memberId: Uuid, discordUserId: String, channelId: String)

    /**
     * @param orgId The organization.
     * @param memberId The member.
     */
    suspend fun remove(orgId: Uuid, memberId: Uuid)

    /** @param orgId The organization whose links are forgotten. */
    suspend fun deleteAll(orgId: Uuid)
}

/**
 * An unfinished link: the member who started it, the browser session it is bound to.
 *
 * @property stateHash The SHA-256 of its state, in hexadecimal: its id.
 * @property memberId The member.
 * @property binding The SHA-256 of the browser session that started it.
 * @property expiresAt When it stops being accepted.
 */
data class DiscordLinkAttempt(val stateHash: String, val memberId: Uuid, val binding: String, val expiresAt: Instant)

/** The unfinished links, single-use. */
interface DiscordLinkAttemptsRepository {
    /**
     * Keeps an attempt; the member's former ones, and the organization's expired ones, go.
     *
     * @param orgId The organization.
     * @param attempt The attempt.
     * @param now The time.
     */
    suspend fun start(orgId: Uuid, attempt: DiscordLinkAttempt, now: Instant)

    /**
     * @param orgId The organization.
     * @param stateHash The hash of its state.
     *
     * @return The attempt, or null.
     */
    suspend fun of(orgId: Uuid, stateHash: String): DiscordLinkAttempt?

    /**
     * Uses an attempt up.
     *
     * @param orgId The organization.
     * @param stateHash The hash of its state.
     *
     * @return Whether this call removed it (false when another one did).
     */
    suspend fun consume(orgId: Uuid, stateHash: String): Boolean

    /** @param orgId The organization whose attempts are forgotten. */
    suspend fun deleteAll(orgId: Uuid)
}

/**
 * A yes request's message in an approver's Discord.
 *
 * @property approvalId The approver's request.
 * @property gateId Its gate.
 * @property posted Where it is.
 */
data class DiscordApprovalMessage(val approvalId: Uuid, val gateId: Uuid, val posted: DiscordPosted)

/** The yes requests' messages, so they are updated once the gate is decided. */
interface DiscordApprovalMessagesRepository {
    /**
     * Keeps a message; the request's former one, if any, is replaced.
     *
     * @param orgId The organization.
     * @param message The message.
     */
    suspend fun save(orgId: Uuid, message: DiscordApprovalMessage)

    /**
     * @param orgId The organization.
     * @param approvalId An approver's request.
     *
     * @return The message posted for it, or null.
     */
    suspend fun of(orgId: Uuid, approvalId: Uuid): DiscordApprovalMessage?

    /** @param orgId The organization whose messages are forgotten. */
    suspend fun deleteAll(orgId: Uuid)
}

/**
 * A member as the Discord link needs them.
 *
 * @property memberId The membership.
 * @property login Their login, the approver's name on a gate.
 * @property role Their role in the organization.
 * @property orgName The organization's name.
 */
data class DiscordMember(val memberId: Uuid, val login: String, val role: OrgRole, val orgName: String)

/** The members of the organizations (identity and orgs modules). */
fun interface DiscordPeople {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Them, or null when they are not a member any more.
     */
    suspend fun member(orgId: Uuid, memberId: Uuid): DiscordMember?
}

/** Ed25519, the signature of Discord's interactions. */
fun interface DiscordVerifier {
    /**
     * @param publicKey The application's public key, 64 hexadecimal characters.
     * @param data The signed bytes (the timestamp, then the raw body).
     * @param signature The signature, 128 hexadecimal characters.
     *
     * @return Whether it verifies; false for a malformed key or signature.
     */
    suspend fun verify(publicKey: String, data: ByteArray, signature: String): Boolean
}
