// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EdDSA
import run.ostrom.server.integrations.discord.domain.DiscordApi
import run.ostrom.server.integrations.discord.domain.DiscordApp
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessage
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttempt
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttemptsRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinksRepository
import run.ostrom.server.integrations.discord.domain.DiscordMember
import run.ostrom.server.integrations.discord.domain.DiscordMessage
import run.ostrom.server.integrations.discord.domain.DiscordPeople
import run.ostrom.server.integrations.discord.domain.DiscordPosted
import run.ostrom.server.integrations.discord.domain.DiscordSettings
import run.ostrom.server.integrations.discord.domain.DiscordUser
import run.ostrom.server.integrations.discord.domain.StoredDiscordLink
import run.ostrom.server.integrations.discord.domain.toHex
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A bot token the settings accept. */
const val BOT_TOKEN = "MTAwMDAwMDAwMDAwMDAwMDAwMA.GaBcDe.abcdefghijklmnopqrstuvwxyz0123456789AB"

/** An application id the settings accept. */
const val APPLICATION_ID = "100000000000000001"

/** A client secret the settings accept. */
const val CLIENT_SECRET = "abcdefghijklmnopqrstuvwxyz012345"

/** A server id the settings accept. */
const val GUILD_ID = "200000000000000002"

/** A public key the settings accept (no private key behind it). */
const val PUBLIC_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** An Ed25519 key pair, as Discord's application has one: its public key in hexadecimal, and a signer. */
class DiscordKeys private constructor(private val pair: EdDSA.KeyPair, val publicKey: String) {
    /**
     * @param timestamp The `X-Signature-Timestamp`.
     * @param body The raw body.
     *
     * @return The `X-Signature-Ed25519` of them, in hexadecimal.
     */
    suspend fun sign(timestamp: String, body: String): String =
        pair.privateKey.signatureGenerator().generateSignature((timestamp + body).encodeToByteArray()).toHex()

    /** A new key pair. */
    companion object {
        /** @return A fresh pair, its public key the last 32 bytes of its DER encoding. */
        suspend fun generate(): DiscordKeys {
            val pair = CryptographyProvider.Default.get(EdDSA).keyPairGenerator(EdDSA.Curve.Ed25519).generateKey()
            val der = pair.publicKey.encodeToByteArray(EdDSA.PublicKey.Format.DER)
            return DiscordKeys(pair, der.copyOfRange(der.size - KEY_BYTES, der.size).toHex())
        }

        private const val KEY_BYTES = 32
    }
}

/** The instance's settings with the Discord application stored, or not while [configured] is false. */
class DiscordInstanceSettings(var configured: Boolean = true, var publicKey: String = PUBLIC_KEY) : InstanceSettings {
    override suspend fun read(section: String): SettingsValues = when {
        section == DiscordSettings.NAME && configured -> SettingsValues(
            mapOf(
                DiscordSettings.BOT_TOKEN to BOT_TOKEN,
                DiscordSettings.APPLICATION_ID to APPLICATION_ID,
                DiscordSettings.PUBLIC_KEY to publicKey,
                DiscordSettings.CLIENT_SECRET to CLIENT_SECRET,
                DiscordSettings.GUILD_ID to GUILD_ID,
            ),
        )
        section == "sign-up" -> SettingsValues(mapOf("mode" to "open"))
        else -> SettingsValues.EMPTY
    }
}

/**
 * Discord as a test wants it: a code `code-<id>` signs in the account `<id>` named [username]; [members] are in the
 * server; [failure] is thrown by every call while set, the other failures by their call only.
 */
class FakeDiscordApi(var members: Set<String> = emptySet()) : DiscordApi {
    var failure: DiscordCallException? = null
    var exchangeFailure: DiscordCallException? = null
    var guildFailure: DiscordCallException? = null
    var openFailure: DiscordCallException? = null
    var postFailure: DiscordCallException? = null
    var deleteFailure: DiscordCallException? = null
    var username = "bob"

    /** The next deletes that fail as Discord asking to slow down (retryable). */
    var deleteFailures = 0
    val opened = mutableListOf<String>()
    val posted = mutableListOf<Pair<String, DiscordMessage>>()
    val edited = mutableListOf<Pair<DiscordPosted, DiscordMessage>>()
    val deleted = mutableListOf<DiscordPosted>()
    val exchanged = mutableListOf<Triple<DiscordApp, String, String>>()

    override suspend fun checkBot(botToken: String): String? {
        failure?.let { throw it }
        return if (botToken == BOT_TOKEN) null else "discord_http_401"
    }

    override suspend fun exchangeCode(app: DiscordApp, code: String, redirectUri: String): String {
        (failure ?: exchangeFailure)?.let { throw it }
        exchanged += Triple(app, code, redirectUri)
        return "token-${code.removePrefix("code-")}"
    }

    override suspend fun currentUser(accessToken: String): DiscordUser {
        failure?.let { throw it }
        return DiscordUser(accessToken.removePrefix("token-"), username)
    }

    override suspend fun inGuild(app: DiscordApp, userId: String): Boolean {
        (failure ?: guildFailure)?.let { throw it }
        return userId in members
    }

    override suspend fun openDm(botToken: String, userId: String): String {
        (failure ?: openFailure)?.let { throw it }
        opened += userId
        return "DM-$userId"
    }

    override suspend fun postMessage(botToken: String, channelId: String, message: DiscordMessage): DiscordPosted {
        (failure ?: postFailure)?.let { throw it }
        posted += channelId to message
        return DiscordPosted(channelId, "${posted.size}")
    }

    override suspend fun editMessage(botToken: String, posted: DiscordPosted, message: DiscordMessage) {
        failure?.let { throw it }
        edited += posted to message
    }

    override suspend fun deleteMessage(botToken: String, posted: DiscordPosted) {
        (failure ?: deleteFailure)?.let { throw it }
        if (deleteFailures > 0) {
            deleteFailures--
            throw DiscordCallException("discord_rate_limited", retryable = true)
        }
        deleted += posted
    }
}

/** The links, in memory; [failingReads] organizations' reads fail, [failingChannelSaves] channel saves fail. */
class InMemoryDiscordLinks : DiscordLinksRepository {
    val links = mutableMapOf<Pair<Uuid, Uuid>, StoredDiscordLink>()
    val failingReads = mutableSetOf<Uuid>()
    var failingChannelSaves = 0

    override suspend fun of(orgId: Uuid, memberId: Uuid): StoredDiscordLink? = links[orgId to memberId]

    override suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredDiscordLink> {
        if (orgId in failingReads) error("The database is down")
        return memberIds.mapNotNull { links[orgId to it] }
    }

    override suspend fun memberOf(orgId: Uuid, discordUserId: String): Uuid? =
        links.entries.firstOrNull { it.key.first == orgId && it.value.discordUserId == discordUserId }?.key?.second

    override suspend fun save(orgId: Uuid, link: StoredDiscordLink) {
        links[orgId to link.memberId] = link
    }

    override suspend fun saveDmChannel(orgId: Uuid, memberId: Uuid, discordUserId: String, channelId: String) {
        if (failingChannelSaves > 0) {
            failingChannelSaves--
            error("The database is down")
        }
        val link = links[orgId to memberId]?.takeIf { it.discordUserId == discordUserId } ?: return
        links[orgId to memberId] = link.copy(dmChannelId = channelId)
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        links.remove(orgId to memberId)
    }

    override suspend fun deleteAll(orgId: Uuid) {
        links.keys.removeAll { it.first == orgId }
    }
}

/** The unfinished links, in memory. */
class InMemoryDiscordAttempts : DiscordLinkAttemptsRepository {
    val attempts = mutableMapOf<Pair<Uuid, String>, DiscordLinkAttempt>()

    /** Whether [consume] finds the attempt already used by another call. */
    var raced = false

    override suspend fun start(orgId: Uuid, attempt: DiscordLinkAttempt, now: Instant) {
        attempts.entries.removeAll { (key, old) ->
            key.first == orgId && (old.memberId == attempt.memberId || old.expiresAt <= now)
        }
        attempts[orgId to attempt.stateHash] = attempt
    }

    override suspend fun of(orgId: Uuid, stateHash: String): DiscordLinkAttempt? = attempts[orgId to stateHash]

    override suspend fun consume(orgId: Uuid, stateHash: String): Boolean =
        attempts.remove(orgId to stateHash) != null && !raced

    override suspend fun deleteAll(orgId: Uuid) {
        attempts.keys.removeAll { it.first == orgId }
    }
}

/** The yes requests' messages, in memory. */
class InMemoryDiscordMessages : DiscordApprovalMessagesRepository {
    val messages = mutableMapOf<Pair<Uuid, Uuid>, DiscordApprovalMessage>()

    /** The saves that fail, while above zero. */
    var failingSaves = 0

    override suspend fun save(orgId: Uuid, message: DiscordApprovalMessage) {
        if (failingSaves > 0) {
            failingSaves--
            error("The database is down")
        }
        messages[orgId to message.approvalId] = message
    }

    override suspend fun of(orgId: Uuid, approvalId: Uuid): DiscordApprovalMessage? = messages[orgId to approvalId]

    override suspend fun deleteAll(orgId: Uuid) {
        messages.keys.removeAll { it.first == orgId }
    }
}

/** The members a test knows. */
class MapDiscordPeople(private val members: Map<Uuid, DiscordMember>) : DiscordPeople {
    override suspend fun member(orgId: Uuid, memberId: Uuid): DiscordMember? = members[memberId]
}
