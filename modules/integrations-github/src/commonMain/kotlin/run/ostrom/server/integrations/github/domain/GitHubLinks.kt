// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.errors.OstromException
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The member a link request acts for.
 *
 * @property orgId Their organization.
 * @property memberId Their member id.
 * @property admin Whether they are an owner or an admin of it.
 * @property principal Their credential, for the audit log.
 */
data class GitHubLinkActor(
    val orgId: Uuid,
    val memberId: Uuid,
    val admin: Boolean,
    val principal: AuthenticatedPrincipal,
)

/**
 * The members' GitHub links (teamcode `GitHubLinkService`). A link holds the GitHub App user-to-server tokens of one
 * member in one organization, used only to give that member's own cloud sessions Git and `gh` access; no route
 * returns them. The App is the instance's ([GitHubAppSource]): its OAuth client links, refreshes and revokes.
 *
 * - The device flow ([startDevice], [pollDevice]) serves the CLI and the app: GitHub is asked at most once per
 *   interval whatever the client's pace, and the token exchange happens here.
 * - The web flow ([startWeb], [completeWeb]) serves the console: a single-use state and PKCE (S256), the attempt bound
 *   to the browser session that started it. An owner or an admin may start it for another member (a robot account
 *   nobody signs in to): the link is that member's.
 * - A GitHub account links to one member of an organization at most; linking again replaces the member's link; a
 *   member who left cannot be linked.
 * - [unlink] deletes the link then revokes its access token at GitHub, best effort.
 *
 * @param apps The instance's App.
 * @param store The links.
 * @param github GitHub's OAuth endpoints.
 * @param members Who is an active member.
 * @param audit The organization's audit log.
 * @param cloud Whether cloud sessions are on: a new link starts only then.
 * @param redirectUri The App's callback URL for the web flow.
 * @param clock The clock.
 */
@Suppress("TooManyFunctions", "LongParameterList") // The link's whole life, as one service, and its ports.
class GitHubLinks(
    private val apps: GitHubAppSource,
    private val store: GitHubLinkStore,
    private val github: GitHubUserAuthApi,
    private val members: MemberDirectory,
    private val audit: OrgAuditTrail,
    private val cloud: CloudSessionsSwitch,
    private val redirectUri: String,
    private val clock: Clock,
) {
    /**
     * @param actor The caller.
     * @param memberId Another member, for an owner or an admin; the caller's own link when null.
     * @return The link.
     * @throws GitHubLinkAdminOnlyException For another member's, unless an owner or an admin.
     * @throws GitHubLinkMemberNotFoundException For a member who is not in the organization.
     */
    suspend fun status(actor: GitHubLinkActor, memberId: Uuid? = null): GitHubLinkStatus {
        val target = target(actor, memberId)
        return GitHubLinkStatus.of(target, app(), store.link(actor.orgId, target))
    }

    /**
     * @param actor An owner or an admin.
     * @return Every link of the organization.
     * @throws GitHubLinkAdminOnlyException Otherwise.
     */
    suspend fun links(actor: GitHubLinkActor): List<GitHubLinkStatus> {
        if (!actor.admin) throw GitHubLinkAdminOnlyException()
        val app = app()
        return store.links(actor.orgId, MAX_LINKS).map { GitHubLinkStatus.of(it.memberId, app, it) }
    }

    /**
     * Starts GitHub's device flow for the caller's own link.
     *
     * @param actor The caller.
     * @return What to show them.
     * @throws GitHubLinkCloudOffException While cloud sessions are off.
     * @throws GitHubLinkAppNotConfiguredException Without the App's OAuth client.
     */
    suspend fun startDevice(actor: GitHubLinkActor): GitHubDeviceLink {
        if (!cloud.enabled()) throw GitHubLinkCloudOffException()
        val app = app() ?: throw GitHubLinkAppNotConfiguredException()
        val device = remote { github.startDevice(app.clientId) }
        val now = clock.now()
        val interval = device.intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        val expiresAt = now + device.expiresInSeconds.coerceIn(MIN_DEVICE_SECONDS, MAX_DEVICE_SECONDS).seconds
        val attempt = GitHubLinkAttempt(
            Uuid.random().toString(), actor.orgId, actor.memberId, GitHubLinkKind.DEVICE, device.deviceCode, null,
            interval, now + interval.seconds, expiresAt,
        )
        store.startAttempt(attempt, now)
        return GitHubDeviceLink(attempt.id, device.userCode, device.verificationUri, expiresAt, interval)
    }

    /**
     * One poll of a device link, by the member who started it. The poll slot is taken before GitHub is asked, so a
     * concurrent poll or a GitHub error never shortens the interval, and a client polling too fast never earns the
     * member a `slow_down`.
     *
     * @param actor The caller.
     * @param id The attempt.
     * @return Where the link is.
     * @throws GitHubLinkNotFoundException For an unknown, used or another member's attempt.
     */
    @Suppress("ReturnCount") // Each answer of GitHub ends the poll its own way.
    suspend fun pollDevice(actor: GitHubLinkActor, id: String): GitHubDeviceLinkProgress {
        val attempt = store.attempt(actor.orgId, id)
            ?.takeIf { it.kind == GitHubLinkKind.DEVICE && it.memberId == actor.memberId }
            ?: throw GitHubLinkNotFoundException()
        val now = clock.now()
        if (attempt.expiresAt <= now) {
            store.consumeAttempt(actor.orgId, id)
            return GitHubDeviceLinkProgress.Expired
        }
        if (now < attempt.nextPollAt) return GitHubDeviceLinkProgress.Pending(attempt.pollIntervalSeconds)
        val app = app() ?: throw GitHubLinkAppNotConfiguredException()
        val next = now + attempt.pollIntervalSeconds.seconds
        if (!store.deferPoll(actor.orgId, id, attempt.nextPollAt, attempt.pollIntervalSeconds, next)) {
            return GitHubDeviceLinkProgress.Pending(attempt.pollIntervalSeconds)
        }
        return when (val poll = remote { github.pollDevice(app.clientId, attempt.secret) }) {
            GitHubDevicePoll.Pending -> GitHubDeviceLinkProgress.Pending(attempt.pollIntervalSeconds)
            is GitHubDevicePoll.SlowDown -> {
                val interval = poll.intervalSeconds.coerceIn(attempt.pollIntervalSeconds, MAX_INTERVAL_SECONDS)
                store.deferPoll(actor.orgId, id, null, interval, now + interval.seconds)
                GitHubDeviceLinkProgress.Pending(interval)
            }
            GitHubDevicePoll.Expired -> {
                store.consumeAttempt(actor.orgId, id)
                GitHubDeviceLinkProgress.Expired
            }
            GitHubDevicePoll.Denied -> {
                store.consumeAttempt(actor.orgId, id)
                GitHubDeviceLinkProgress.Denied
            }
            is GitHubDevicePoll.Authorized -> {
                store.consumeAttempt(actor.orgId, id)
                val status = complete(app, actor.orgId, actor.memberId, actor.principal, poll.tokens)
                GitHubDeviceLinkProgress.Linked(status)
            }
        }
    }

    /**
     * Starts the web flow. The state names the organization (the callback carries no `Ostrom-Org`), and its hash, the
     * attempt; the attempt is bound to [session], so a link sent to someone else binds nothing.
     *
     * @param actor The caller, in a browser.
     * @param session The browser session that starts it.
     * @param memberId Another member to link, for an owner or an admin; the caller when null.
     * @return Where the browser authorizes the App.
     * @throws GitHubLinkCloudOffException While cloud sessions are off.
     * @throws GitHubLinkAppNotConfiguredException Without the App's OAuth client.
     */
    suspend fun startWeb(actor: GitHubLinkActor, session: String, memberId: Uuid? = null): String {
        val target = target(actor, memberId)
        if (!cloud.enabled()) throw GitHubLinkCloudOffException()
        val app = app() ?: throw GitHubLinkAppNotConfiguredException()
        val state = "${actor.orgId}.${token()}"
        val verifier = token()
        val now = clock.now()
        val attempt = GitHubLinkAttempt(
            sha256Hex(state), actor.orgId, target, GitHubLinkKind.WEB, verifier, sha256Hex(session), 0, now,
            now + WEB_ATTEMPT,
        )
        store.startAttempt(attempt, now)
        // The callback carries the session, not who started it for another member: say it here.
        if (target != actor.memberId) {
            audit.record(OrgAuditRecord(actor.orgId, actor.principal, STARTED, MEMBER, target.toString()))
        }
        return github.authorizationUrl(app.clientId, redirectUri, state, challenge(verifier))
    }

    /**
     * The web flow's callback. The attempt is single-use and finished only by the session that started it; a session
     * that does not match leaves it for the right one.
     *
     * @param state The state of [startWeb].
     * @param code GitHub's code.
     * @param error GitHub's error, when the member cancelled.
     * @param session The browser session the callback came with, and its principal, or null without one.
     * @return The link.
     * @throws GitHubLinkNotFoundException For an unknown, used, expired or malformed attempt.
     * @throws GitHubLinkBrowserMismatchException For another session.
     * @throws GitHubLinkDeniedException When the member cancelled.
     */
    @Suppress("ThrowsCount") // Each refusal of the callback is its own answer.
    suspend fun completeWeb(
        state: String?,
        code: String?,
        error: String?,
        session: Pair<String, AuthenticatedPrincipal>?,
    ): GitHubLinkStatus {
        val orgId = state?.takeIf { STATE.matches(it) }?.substringBefore('.')?.let(Uuid::parseOrNull)
            ?: throw GitHubLinkNotFoundException()
        val attempt = store.attempt(orgId, sha256Hex(state))?.takeIf { it.kind == GitHubLinkKind.WEB }
            ?: throw GitHubLinkNotFoundException()
        if (session == null || !sameText(sha256Hex(session.first), attempt.binding.orEmpty())) {
            throw GitHubLinkBrowserMismatchException()
        }
        if (!store.consumeAttempt(orgId, attempt.id) || attempt.expiresAt <= clock.now()) {
            throw GitHubLinkNotFoundException()
        }
        if (error != null) throw GitHubLinkDeniedException()
        if (code == null || !CODE.matches(code)) throw GitHubLinkNotFoundException()
        val app = app() ?: throw GitHubLinkAppNotConfiguredException()
        val tokens = remote { github.exchangeCode(app, code, redirectUri, attempt.secret) }
        return complete(app, orgId, attempt.memberId, session.second, tokens)
    }

    /**
     * Removes a link and revokes its access token at GitHub, best effort. Unlinking twice is not an error.
     *
     * @param actor The caller.
     * @param memberId Another member, for an owner or an admin; the caller when null.
     */
    suspend fun unlink(actor: GitHubLinkActor, memberId: Uuid? = null) {
        // A member who left can still be unlinked: their tokens go, and their GitHub account is free again.
        val target = target(actor, memberId, active = false)
        val removed = store.removeLink(actor.orgId, target)
        if (removed == null) {
            // Nothing to unlink: done for oneself or an active member, unknown for anybody else (another org's).
            val known = target == actor.memberId || members.isActiveMember(actor.orgId, target.toString())
            if (!known) throw GitHubLinkMemberNotFoundException()
            return
        }
        audit.record(OrgAuditRecord(actor.orgId, actor.principal, REMOVED, MEMBER, target.toString()))
        app()?.let { revokeToken(github, it, removed) }
    }

    @Suppress("ThrowsCount") // A refused token is revoked, then the refusal goes on.
    private suspend fun complete(
        app: GitHubOAuthApp,
        orgId: Uuid,
        memberId: Uuid,
        actor: AuthenticatedPrincipal,
        tokens: GitHubTokens,
    ): GitHubLinkStatus {
        val issued = GitHubRevocable(app.clientId, tokens.accessToken)
        val saved = try {
            // An OAuth App's token (`gho_`) never expires nor refreshes: refused, and revoked below.
            if (!tokens.accessToken.startsWith(USER_TOKEN_PREFIX)) throw GitHubAppMisconfiguredException(OAUTH_APP)
            val identity = remote { github.user(tokens.accessToken) }
            if (!members.isActiveMember(orgId, memberId.toString())) throw GitHubLinkMemberNotFoundException()
            store.saveLink(orgId, memberId, identity, app.clientId, tokens, clock.now())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Never keep a live token nobody stores. Only this token is revoked: the member's grant, and any other
            // token GitHub issued for it, stay untouched.
            revokeToken(github, app, issued)
            throw error
        }
        // The tokens of the link it replaced are no one's any more.
        saved.replaced?.takeIf { it.accessToken != tokens.accessToken }?.let { revokeToken(github, app, it) }
        audit.record(OrgAuditRecord(orgId, actor, CREATED, MEMBER, memberId.toString()))
        return GitHubLinkStatus(memberId, true, saved.link, saved.link.state)
    }

    private suspend fun target(actor: GitHubLinkActor, memberId: Uuid?, active: Boolean = true): Uuid {
        if (memberId == null || memberId == actor.memberId) return actor.memberId
        if (!actor.admin) throw GitHubLinkAdminOnlyException()
        if (active && !members.isActiveMember(actor.orgId, memberId.toString())) {
            throw GitHubLinkMemberNotFoundException()
        }
        return memberId
    }

    private suspend fun app(): GitHubOAuthApp? = apps.current().oauthApp()

    /** Constants and helpers shared with the refresh. */
    companion object {
        /** The fewest seconds between two polls of GitHub. */
        const val MIN_INTERVAL_SECONDS: Int = 5

        /** The most seconds between two polls of GitHub. */
        const val MAX_INTERVAL_SECONDS: Int = 60

        /** The most links an organization lists. */
        const val MAX_LINKS: Int = 1000

        /** The audit log's events. */
        const val STARTED: String = "github.link.started"

        /** A link stored. */
        const val CREATED: String = "github.link.created"

        /** A link removed. */
        const val REMOVED: String = "github.link.removed"

        /** A link ended without anyone asking. */
        const val RECONNECT_REQUIRED: String = "github.link.reconnect_required"

        /** The audit log's subject type. */
        const val MEMBER: String = "member"

        /**
         * Revokes an access token, best effort: the stored tokens are already gone and an access token expires within
         * hours. Only the App that issued a token can revoke it; a token of a previous App is left to expire.
         *
         * @param github GitHub.
         * @param app The current App.
         * @param token The token.
         */
        suspend fun revokeToken(github: GitHubUserAuthApi, app: GitHubOAuthApp, token: GitHubRevocable) {
            if (token.clientId != app.clientId) return
            try {
                withTimeoutOrNull(REVOKE_TIMEOUT) { github.revoke(app, token.accessToken) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Best effort, said above: nothing to tell the caller, whose link is already gone.
                Unit
            }
        }

        /**
         * Runs a call to GitHub: a transport failure becomes `github_unreachable`, without GitHub's answer, which may
         * echo what it was sent; a refusal with its own key passes as is.
         *
         * @param block The call.
         * @return Its result.
         */
        internal suspend fun <T> remote(block: suspend () -> T): T = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: OstromException) {
            throw error
        } catch (_: GitHubCallException) {
            throw GitHubUnreachableException()
        }

        /** @return The PKCE challenge (S256) of a verifier. */
        internal suspend fun challenge(verifier: String): String = BASE64_URL.encode(
            CryptographyProvider.Default.get(SHA256).hasher().hash(verifier.encodeToByteArray()),
        )
    }
}

/** 32 random bytes, base64url without padding: a state or a verifier (43 characters). */
private fun token(): String = BASE64_URL.encode(CryptographyRandom.nextBytes(TOKEN_BYTES))

/** Compares two texts in a time that does not depend on where they differ. */
private fun sameText(a: String, b: String): Boolean {
    var difference = a.length xor b.length
    for (index in a.indices) difference = difference or (a[index].code xor (b.getOrNull(index)?.code ?: 0))
    return difference == 0
}

private const val TOKEN_BYTES = 32
private const val USER_TOKEN_PREFIX = "ghu_"
private const val OAUTH_APP =
    "The instance's client id does not issue GitHub App user tokens: configure a GitHub App, not an OAuth App."
private const val MIN_DEVICE_SECONDS = 60
private const val MAX_DEVICE_SECONDS = 15 * 60
private val WEB_ATTEMPT = 10.minutes
private val REVOKE_TIMEOUT = 10.seconds
private val BASE64_URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val STATE = Regex("[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")
private val CODE = Regex("[A-Za-z0-9_-]{1,512}")
