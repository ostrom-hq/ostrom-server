// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CancellationException
import run.ostrom.core.api.models.DiscordLink
import run.ostrom.server.identity.domain.constantTimeEquals
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Reads a member's own Discord link. */
fun interface GetDiscordLinkUseCase {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     *
     * @return Their link, and whether Discord is configured.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid): DiscordLink
}

/** Starts linking a member's Discord account: Discord's authorization URL, bound to their browser session. */
fun interface StartDiscordLinkUseCase {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     * @param session The browser session that starts it.
     *
     * @return Where the browser authorizes the application.
     * @throws DiscordNotConfiguredException When the `discord` settings section is incomplete.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid, session: String): String
}

/** Discord's return from a link: exchanges the code and stores the link. */
fun interface CompleteDiscordLinkUseCase {
    /**
     * @param state The state of [StartDiscordLinkUseCase].
     * @param code Discord's code.
     * @param error Discord's error, when the person declined.
     * @param session The browser session the callback came with, or null without one.
     *
     * @return The link.
     * @throws DiscordLinkNotFoundException For an unknown, used, expired or malformed attempt, or a member who left.
     * @throws DiscordLinkBrowserMismatchException For another session.
     * @throws DiscordLinkDeniedException When the person declined or Discord refused the code.
     * @throws DiscordNotConfiguredException When the `discord` settings section is incomplete.
     * @throws DiscordNotInGuildException When the account is not in the instance's server.
     * @throws DiscordUserTakenException When another member of the organization linked that account.
     * @throws DiscordUnreachableException When Discord does not answer.
     */
    suspend operator fun invoke(state: String?, code: String?, error: String?, session: String?): DiscordLink
}

/** Removes a member's own Discord link; nothing when there is none. */
fun interface UnlinkDiscordUseCase {
    /**
     * @param orgId The organization.
     * @param memberId The member.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid)
}

/**
 * [GetDiscordLinkUseCase] on the stored links.
 *
 * @param apps The Discord application.
 * @param links The links.
 */
class GetDiscordLinkUseCaseImpl(
    private val apps: DiscordApps,
    private val links: DiscordLinksRepository,
) : GetDiscordLinkUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid): DiscordLink =
        links.of(orgId, memberId).toDto(memberId, apps.current() != null)
}

/**
 * [StartDiscordLinkUseCase]: the state names the organization (the callback carries no `Ostrom-Org`) and, by its
 * hash, the attempt; the attempt is bound to the browser session's hash, so a link sent to someone else binds nothing.
 *
 * @param apps The Discord application.
 * @param attempts The unfinished links.
 * @param redirectUri The link's callback, `<OSTROM_PUBLIC_URL>/api/v1/discord/link/callback`.
 * @param clock The time.
 */
class StartDiscordLinkUseCaseImpl(
    private val apps: DiscordApps,
    private val attempts: DiscordLinkAttemptsRepository,
    private val redirectUri: String,
    private val clock: Clock,
) : StartDiscordLinkUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid, session: String): String {
        val app = apps.current() ?: throw DiscordNotConfiguredException()
        val state = "$orgId.${token()}"
        val now = clock.now()
        attempts.start(orgId, DiscordLinkAttempt(sha256Hex(state), memberId, sha256Hex(session), now + ATTEMPT), now)
        return URLBuilder(AUTHORIZE_URL).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", app.applicationId)
            parameters.append("scope", "identify")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("prompt", "consent")
        }.buildString()
    }

    private companion object {
        const val AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
    }
}

/**
 * [CompleteDiscordLinkUseCase]: the attempt is single-use and finished only by the browser session that started it;
 * another session leaves it for the right one. The account must be in the instance's server (a bot only writes to a
 * person it shares a server with), and answers for one member of the organization (its Reject is that member's no).
 *
 * @param apps The Discord application.
 * @param api Discord.
 * @param attempts The unfinished links.
 * @param people The members.
 * @param links The links.
 * @param texts The message the account is told with.
 * @param redirectUri The link's callback, as the start named it.
 * @param clock The time.
 */
@Suppress("LongParameterList") // One port per owner: settings, Discord, attempts, people, links, texts, clock.
class CompleteDiscordLinkUseCaseImpl(
    private val apps: DiscordApps,
    private val api: DiscordApi,
    private val attempts: DiscordLinkAttemptsRepository,
    private val people: DiscordPeople,
    private val links: DiscordLinksRepository,
    private val texts: DiscordTexts,
    private val redirectUri: String,
    private val clock: Clock,
) : CompleteDiscordLinkUseCase {
    @Suppress("ThrowsCount", "CyclomaticComplexMethod") // Each refusal of the callback is its own answer.
    override suspend fun invoke(state: String?, code: String?, error: String?, session: String?): DiscordLink {
        val orgId = state?.takeIf { STATE.matches(it) }?.let { Uuid.parseOrNull(it.substringBefore('.')) }
            ?: throw DiscordLinkNotFoundException()
        val hash = sha256Hex(state)
        val attempt = attempts.of(orgId, hash) ?: throw DiscordLinkNotFoundException()
        if (session == null || !constantTimeEquals(sha256Hex(session), attempt.binding)) {
            throw DiscordLinkBrowserMismatchException()
        }
        if (!attempts.consume(orgId, hash) || attempt.expiresAt <= clock.now()) throw DiscordLinkNotFoundException()
        if (error != null) throw DiscordLinkDeniedException()
        if (code == null || !CODE.matches(code)) throw DiscordLinkNotFoundException()
        val app = apps.current() ?: throw DiscordNotConfiguredException()
        val user = remote {
            val token = api.exchangeCode(app, code, redirectUri)
            api.currentUser(token)
        }
        if (!inServer(app, user.id)) throw DiscordNotInGuildException()
        val member = people.member(orgId, attempt.memberId) ?: throw DiscordLinkNotFoundException()
        if (links.memberOf(orgId, user.id).let { it != null && it != member.memberId }) {
            throw DiscordUserTakenException()
        }
        // Stored first: the confirmation below is best effort and never undoes the link.
        val link = StoredDiscordLink(member.memberId, user.id, user.username, clock.now())
        links.save(orgId, link)
        tell(app, orgId, link, texts.linked(member.login, member.orgName))
        return link.toDto(member.memberId, configured = true)
    }

    // A refusal of the code (used, expired, another redirect) is a denied link; Discord down is unreachable.
    private suspend fun <T> remote(call: suspend () -> T): T = try {
        call()
    } catch (failure: DiscordCallException) {
        if (failure.retryable) throw DiscordUnreachableException()
        throw DiscordLinkDeniedException()
    }

    // A refusal of the membership read (a wrong server id, the bot not in it) is the administrator's to fix.
    private suspend fun inServer(app: DiscordApp, userId: String): Boolean = try {
        api.inGuild(app, userId)
    } catch (failure: DiscordCallException) {
        if (failure.retryable) throw DiscordUnreachableException()
        throw DiscordNotConfiguredException()
    }

    // Best effort: the link stands even when the message does not go; the channel is kept for the next messages.
    private suspend fun tell(app: DiscordApp, orgId: Uuid, link: StoredDiscordLink, message: DiscordMessage) {
        try {
            val channel = api.openDm(app.botToken, link.discordUserId)
            links.saveDmChannel(orgId, link.memberId, link.discordUserId, channel)
            api.postMessage(app.botToken, channel, message)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Unit
        }
    }
}

/**
 * [UnlinkDiscordUseCase] on the stored links.
 *
 * @param links The links.
 */
class UnlinkDiscordUseCaseImpl(private val links: DiscordLinksRepository) : UnlinkDiscordUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid) {
        links.remove(orgId, memberId)
    }
}

private fun StoredDiscordLink?.toDto(memberId: Uuid, configured: Boolean): DiscordLink = DiscordLink(
    memberId = memberId.toString(),
    configured = configured,
    linked = this != null,
    discordUserId = this?.discordUserId,
    discordUsername = this?.username,
    linkedAt = this?.run { linkedAt.toString() },
)

/** 32 random bytes, base64url without padding (43 characters). */
private fun token(): String = BASE64_URL.encode(CryptographyRandom.nextBytes(TOKEN_BYTES))

/**
 * The SHA-256 of a text, in hexadecimal: how a single-use state and a browser session are kept.
 *
 * @param text The text.
 * @return Its hash.
 */
internal suspend fun sha256Hex(text: String): String =
    CryptographyProvider.Default.get(SHA256).hasher().hash(text.encodeToByteArray()).toHex()

/** @return The bytes in lower-case hexadecimal. */
internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

private const val TOKEN_BYTES = 32

/** How long a started link waits for Discord's return. */
private val ATTEMPT = 10.minutes

/** A state: the organization, a dot, 32 random bytes in base64url. */
private val STATE = Regex("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")

/** A code as Discord gives it. */
private val CODE = Regex("[A-Za-z0-9_-]{1,128}")
private const val BYTE = 0xff
private const val HEX = 16
private val BASE64_URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
