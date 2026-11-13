// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Who ends a link nobody asked to end, in the audit log: the server itself. */
val GITHUB_ACTOR: AuthenticatedPrincipal = AuthenticatedPrincipal("github", PrincipalKind.SERVICE, emptySet())

/**
 * A member's GitHub access for their own cloud sessions, from their stored link (teamcode
 * `ResolveGitHubAccessUseCase`). A token that expires within [validFor] is refreshed first. The refresh is serialized
 * across replicas: the right to spend the rotating refresh token is committed before GitHub is called, other callers
 * wait for it (up to [maxWait], or get the current token while it stays valid 5 more minutes), and an owner silent for
 * [staleAfter] is taken over.
 *
 * - No App, no link, a member who left, or a link GitHub refused to refresh: [GitHubAccess.NotLinked].
 * - GitHub unreachable and no token still valid: [GitHubAccess.Unavailable]; the old tokens stay for a later try.
 * - GitHub refusing the refresh token, or a refresh token that expired, ends the link (`reconnect_required`, journaled
 *   `github.link.reconnect_required` with the reason).
 *
 * Each refresh GitHub is asked for writes one log line, its outcome only: never a token, a body or a message.
 *
 * @param apps The instance's App.
 * @param store The links.
 * @param github GitHub.
 * @param members Who is an active member.
 * @param audit The organization's audit log.
 * @param clock The clock.
 * @param logger Writes the line of a refresh.
 * @param validFor How long a token handed out must stay valid.
 * @param staleAfter How long a refresh owner may stay silent.
 * @param refreshTimeout How long GitHub has to refresh.
 * @param maxWait How long a caller waits for another's refresh.
 */
@Suppress("LongParameterList") // The collaborators and the four bounds of the refresh, each tested.
class GitHubAccessResolver(
    private val apps: GitHubAppSource,
    private val store: GitHubLinkStore,
    private val github: GitHubUserAuthApi,
    private val members: MemberDirectory,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
    private val logger: Logger,
    private val validFor: Duration = 1.hours,
    private val staleAfter: Duration = 60.seconds,
    private val refreshTimeout: Duration = 20.seconds,
    private val maxWait: Duration = 20.seconds,
) {
    /**
     * @param orgId The organization.
     * @param memberId The member the session runs for.
     * @return Their access.
     */
    suspend fun access(orgId: Uuid, memberId: Uuid): GitHubAccess {
        val app = apps.current().oauthApp() ?: return GitHubAccess.NotLinked
        if (!members.isActiveMember(orgId, memberId.toString())) return GitHubAccess.NotLinked
        var waited = Duration.ZERO
        while (true) {
            val now = clock.now()
            val claim =
                store.claim(orgId, memberId, app.clientId, now, now + validFor, now + STILL_USABLE, staleAfter)
            when (claim) {
                GitHubTokenClaim.NotLinked -> return GitHubAccess.NotLinked
                is GitHubTokenClaim.ReconnectRequired -> {
                    claim.endedBecause?.let { ended(orgId, memberId, it) }
                    return GitHubAccess.NotLinked
                }
                is GitHubTokenClaim.Ready -> return linked(claim.link, claim.accessToken, claim.link.accessExpiresAt)
                is GitHubTokenClaim.Refresh -> return refresh(orgId, memberId, app, claim, now)
                GitHubTokenClaim.Wait -> {
                    if (waited >= maxWait) return GitHubAccess.Unavailable
                    delay(WAIT_STEP)
                    waited += WAIT_STEP
                }
            }
        }
    }

    private suspend fun refresh(
        orgId: Uuid,
        memberId: Uuid,
        app: GitHubOAuthApp,
        claim: GitHubTokenClaim.Refresh,
        now: Instant,
    ): GitHubAccess {
        var failure = "timeout"
        val result = try {
            withTimeout(refreshTimeout) { github.refresh(app, claim.refreshToken) }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (error: CancellationException) {
            withContext(NonCancellable) { store.abandonRefresh(orgId, memberId, claim, reconnect = false) }
            logger.info(line(memberId, "cancelled"))
            throw error
        } catch (error: OstromException) {
            failure = error.type
            null
        } catch (error: Exception) {
            failure = error::class.simpleName ?: "error"
            null
        }
        return when (result) {
            is GitHubRefresh.Refreshed -> refreshed(orgId, memberId, app, claim, result.tokens)
            GitHubRefresh.Rejected -> {
                logger.info(line(memberId, "rejected"))
                if (store.abandonRefresh(orgId, memberId, claim, reconnect = true)) {
                    ended(orgId, memberId, "refresh_rejected")
                }
                GitHubAccess.NotLinked
            }
            null -> {
                logger.info(line(memberId, "failed cause=$failure"))
                store.abandonRefresh(orgId, memberId, claim, reconnect = false)
                // A token still valid for a while beats no access at all.
                val expiresAt = claim.link.accessExpiresAt
                if (expiresAt != null && expiresAt > now + STILL_USABLE) {
                    linked(claim.link, claim.accessToken, expiresAt)
                } else {
                    GitHubAccess.Unavailable
                }
            }
        }
    }

    private suspend fun refreshed(
        orgId: Uuid,
        memberId: Uuid,
        app: GitHubOAuthApp,
        claim: GitHubTokenClaim.Refresh,
        tokens: GitHubTokens,
    ): GitHubAccess {
        // GitHub already rotated the refresh token: losing these tokens would force the member to link again.
        val saved = withContext(NonCancellable) { save(orgId, memberId, claim, tokens) }
        logger.info(
            line(
                memberId,
                when (saved) {
                    true -> "refreshed"
                    null -> "refreshed_unsaved"
                    false -> "refreshed_fenced"
                },
            ),
        )
        if (saved == false) {
            // Fenced by a relink, an unlink or a takeover: this token belongs to no stored link any more.
            GitHubLinks.revokeToken(github, app, GitHubRevocable(app.clientId, tokens.accessToken))
            return GitHubAccess.Unavailable
        }
        // Saved, or not saved because of a passing database error: the new token is valid either way.
        return linked(claim.link, tokens.accessToken, tokens.accessExpiresAt)
    }

    /** True when saved, false when fenced, null when the database kept failing. */
    private suspend fun save(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        tokens: GitHubTokens,
    ): Boolean? {
        repeat(SAVE_ATTEMPTS) { attempt ->
            try {
                return store.completeRefresh(orgId, memberId, claim, tokens, clock.now())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A passing database error: tried again, and the new token is handed out anyway.
                if (attempt < SAVE_ATTEMPTS - 1) delay(SAVE_BACKOFF * (attempt + 1))
            }
        }
        return null
    }

    private suspend fun ended(orgId: Uuid, memberId: Uuid, reason: String) = audit.record(
        OrgAuditRecord(
            orgId,
            GITHUB_ACTOR,
            GitHubLinks.RECONNECT_REQUIRED,
            GitHubLinks.MEMBER,
            memberId.toString(),
            auditDetails("reason" to reason),
        ),
    )

    private fun line(memberId: Uuid, outcome: String) = "Ostrom github refresh member=$memberId outcome=$outcome"

    private fun linked(link: GitHubLink, token: String, expiresAt: Instant?) =
        GitHubAccess.Linked(token, expiresAt, link.identity.login, link.identity.userId)
}

private val WAIT_STEP = 100.milliseconds
private val STILL_USABLE = 5.minutes
private val SAVE_BACKOFF = 200.milliseconds
private const val SAVE_ATTEMPTS = 3
