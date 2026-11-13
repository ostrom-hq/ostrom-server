// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The members' links and their pending attempts, one organization's at a time. Tokens and attempt secrets are sealed,
 * each bound to its organization and member: an envelope copied to another row does not open.
 */
interface GitHubLinkStore {
    /**
     * Stores an attempt, replacing the member's previous one and dropping the organization's expired ones.
     *
     * @param attempt The attempt.
     * @param now The current time.
     */
    suspend fun startAttempt(attempt: GitHubLinkAttempt, now: Instant)

    /**
     * @param orgId The organization.
     * @param id The attempt.
     * @return The attempt, or null.
     */
    suspend fun attempt(orgId: Uuid, id: String): GitHubLinkAttempt?

    /**
     * Removes an attempt.
     *
     * @param orgId The organization.
     * @param id The attempt.
     * @return False when another request already took it.
     */
    suspend fun consumeAttempt(orgId: Uuid, id: String): Boolean

    /**
     * Moves the next allowed poll; with [expectedNextPollAt], only while it is still that value, so two concurrent
     * polls never both ask GitHub within one interval.
     *
     * @param orgId The organization.
     * @param id The attempt.
     * @param expectedNextPollAt The value read, or null to move it whatever it is.
     * @param intervalSeconds The interval to keep.
     * @param nextPollAt The next allowed poll.
     * @return Whether it moved.
     */
    suspend fun deferPoll(
        orgId: Uuid,
        id: String,
        expectedNextPollAt: Instant?,
        intervalSeconds: Int,
        nextPollAt: Instant,
    ): Boolean

    /**
     * Stores the member's link, replacing any previous one.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param identity The GitHub account.
     * @param clientId The App's client that issued the tokens.
     * @param tokens The tokens.
     * @param now The current time.
     * @return The link, and the access token of the link it replaced (null when none, or unreadable).
     * @throws GitHubAccountInUseException When another member of the organization linked that account.
     */
    suspend fun saveLink(
        orgId: Uuid,
        memberId: Uuid,
        identity: GitHubIdentity,
        clientId: String,
        tokens: GitHubTokens,
        now: Instant,
    ): GitHubSavedLink

    /**
     * @param orgId The organization.
     * @param memberId The member.
     * @return The member's link, or null.
     */
    suspend fun link(orgId: Uuid, memberId: Uuid): GitHubLink?

    /**
     * @param orgId The organization.
     * @param limit The most links read.
     * @return The organization's links, by GitHub login.
     */
    suspend fun links(orgId: Uuid, limit: Int): List<GitHubLink>

    /**
     * Deletes the member's link and pending attempts in one transaction.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @return Its last access token for a best-effort revocation, or null without a link (or one that no longer
     *   opens).
     */
    suspend fun removeLink(orgId: Uuid, memberId: Uuid): GitHubRevocable?

    /**
     * The member's token when it stays valid past [validUntil]; otherwise the right to refresh it, committed before
     * GitHub is called so two replicas never spend the same rotating refresh token. An owner silent for [staleAfter]
     * is taken over. While another caller refreshes, a token still valid past [usableUntil] is returned rather than
     * waited on. A link of another App than [clientId] needs a new link; so does one whose refresh token expired once
     * its access token is spent.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param clientId The current App's client.
     * @param now The current time.
     * @param validUntil The time a token handed out must outlive.
     * @param usableUntil The time a token handed out during another's refresh must outlive.
     * @param staleAfter How long a refresh owner may stay silent.
     * @return The claim.
     */
    @Suppress("LongParameterList") // The claim's whole window: one transaction decides it.
    suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        clientId: String,
        now: Instant,
        validUntil: Instant,
        usableUntil: Instant,
        staleAfter: Duration,
    ): GitHubTokenClaim

    /**
     * Saves refreshed tokens if [claim] still owns the refresh.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param claim The owned claim.
     * @param tokens The new tokens.
     * @param now The current time.
     * @return False when a relink, an unlink or a takeover won.
     */
    suspend fun completeRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        tokens: GitHubTokens,
        now: Instant,
    ): Boolean

    /**
     * Ends an owned refresh: [reconnect] marks the link `reconnect_required`; otherwise the old tokens stay for a later
     * attempt.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param claim The owned claim.
     * @param reconnect Whether the link ends.
     * @return Whether this call ended the link.
     */
    suspend fun abandonRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        reconnect: Boolean,
    ): Boolean
}

/**
 * GitHub's OAuth endpoints for a GitHub App's user-to-server tokens. An implementation never puts a response body in
 * an error: GitHub may echo what it was sent. A transport failure, or an answer the server does not act on, is a
 * [GitHubCallException]; a refusal the member or an administrator can fix is an `OstromException` with its key.
 */
interface GitHubUserAuthApi {
    /**
     * Starts the device flow.
     *
     * @param clientId The App's client.
     * @return GitHub's codes.
     */
    suspend fun startDevice(clientId: String): GitHubDeviceAuthorization

    /**
     * Polls a device flow once.
     *
     * @param clientId The App's client.
     * @param deviceCode The device code.
     * @return GitHub's answer.
     */
    suspend fun pollDevice(clientId: String, deviceCode: String): GitHubDevicePoll

    /**
     * @param clientId The App's client.
     * @param redirectUri Where GitHub sends the browser back.
     * @param state The single-use state.
     * @param codeChallenge The PKCE challenge (S256).
     * @return Where the browser authorizes the App.
     */
    fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String

    /**
     * Exchanges the web flow's code.
     *
     * @param app The App's client.
     * @param code GitHub's code.
     * @param redirectUri The same redirect as the authorization's.
     * @param codeVerifier The PKCE verifier.
     * @return The tokens.
     */
    suspend fun exchangeCode(app: GitHubOAuthApp, code: String, redirectUri: String, codeVerifier: String): GitHubTokens

    /**
     * Spends a refresh token.
     *
     * @param app The App's client.
     * @param refreshToken The refresh token.
     * @return New tokens, or [GitHubRefresh.Rejected] only for a definitive refusal of the refresh token.
     */
    suspend fun refresh(app: GitHubOAuthApp, refreshToken: String): GitHubRefresh

    /**
     * @param accessToken A user-to-server token.
     * @return The account it belongs to.
     */
    suspend fun user(accessToken: String): GitHubIdentity

    /**
     * Revokes one access token. The member's authorization of the App stays on GitHub, where only they manage it.
     *
     * @param app The App that issued it.
     * @param accessToken The token.
     */
    suspend fun revoke(app: GitHubOAuthApp, accessToken: String)
}

/** Whether the instance runs cloud sessions: a new link starts only then (one being finished still completes). */
fun interface CloudSessionsSwitch {
    /** @return Whether cloud sessions are on. */
    suspend fun enabled(): Boolean
}
