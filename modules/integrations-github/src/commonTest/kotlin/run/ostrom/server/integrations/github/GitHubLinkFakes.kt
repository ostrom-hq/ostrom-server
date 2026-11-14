// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubAccountInUseException
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import run.ostrom.server.integrations.github.domain.GitHubDeviceAuthorization
import run.ostrom.server.integrations.github.domain.GitHubDevicePoll
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubLink
import run.ostrom.server.integrations.github.domain.GitHubLinkAttempt
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.GitHubLinkStore
import run.ostrom.server.integrations.github.domain.GitHubOAuthApp
import run.ostrom.server.integrations.github.domain.GitHubRefresh
import run.ostrom.server.integrations.github.domain.GitHubRevocable
import run.ostrom.server.integrations.github.domain.GitHubSavedLink
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.domain.GitHubTokens
import run.ostrom.server.integrations.github.domain.GitHubUserAuthApi
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The members of the link tests: [OWNER] and [DEV] in org A, [ROBOT] a service account of org A. */
val OWNER: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val DEV: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000d")
val ROBOT: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000e")

/** The instance App's OAuth client in the link tests. */
const val CLIENT_ID = "Iv1.test"
val OAUTH_APP = GitHubOAuthApp(CLIENT_ID, "client-secret")

/** The App credentials, settable. */
class SettableApps(var app: GitHubAppCredentials = linkingApp()) : GitHubAppSource {
    override suspend fun current(): GitHubAppCredentials = app

    override fun forget() = Unit
}

/** An App whose OAuth client is known: it can link. */
fun linkingApp(clientId: String = CLIENT_ID) =
    GitHubAppCredentials(appId = "42", slug = "ostrom", clientId = clientId, clientSecret = "client-secret")

/** The active members of each organization. */
class SettableMembers(val active: MutableSet<Pair<Uuid, Uuid>> = mutableSetOf()) : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
        Uuid.parseOrNull(memberId)?.let { (orgId to it) in active } == true
}

/** The audit log, in memory. */
class RecordingAudit : OrgAuditTrail {
    val records = mutableListOf<OrgAuditRecord>()

    override suspend fun record(record: OrgAuditRecord) {
        records += record
    }
}

/** A user-to-server token pair that expires at [expiresAt]. */
fun tokens(access: String = "ghu_new", expiresAt: Instant? = NOW, refresh: String? = "ghr_new") =
    GitHubTokens(access, expiresAt, refresh, null)

/**
 * GitHub's OAuth endpoints, scripted: each answer is set by the test, each call recorded.
 */
class FakeGitHubUserAuth : GitHubUserAuthApi {
    var device: () -> GitHubDeviceAuthorization = {
        GitHubDeviceAuthorization("device-code", "ABCD-1234", "https://github.com/login/device", 900, 5)
    }
    var polls = ArrayDeque<GitHubDevicePoll>()
    var exchange: () -> GitHubTokens = { tokens("ghu_web") }
    var refresh: suspend (String) -> GitHubRefresh = { GitHubRefresh.Refreshed(tokens("ghu_refreshed")) }
    var identities = mutableMapOf(
        "ghu_web" to GitHubIdentity(7, "octocat"),
        "ghu_device" to GitHubIdentity(7, "octocat"),
        "ghu_robot" to GitHubIdentity(9, "acme-bot"),
    )
    var userFailure: Exception? = null
    var revokeFailure: Throwable? = null
    val polled = mutableListOf<String>()
    val exchanged = mutableListOf<Triple<String, String, String>>()
    val refreshed = mutableListOf<String>()
    val revoked = mutableListOf<String>()

    override suspend fun startDevice(clientId: String): GitHubDeviceAuthorization = device()

    override suspend fun pollDevice(clientId: String, deviceCode: String): GitHubDevicePoll {
        polled += deviceCode
        return polls.removeFirstOrNull() ?: GitHubDevicePoll.Pending
    }

    override fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String) =
        "https://github.com/login/oauth/authorize?client_id=$clientId&state=$state&code_challenge=$codeChallenge"

    override suspend fun exchangeCode(
        app: GitHubOAuthApp,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): GitHubTokens {
        exchanged += Triple(code, redirectUri, codeVerifier)
        return exchange()
    }

    override suspend fun refresh(app: GitHubOAuthApp, refreshToken: String): GitHubRefresh {
        refreshed += refreshToken
        return refresh.invoke(refreshToken)
    }

    override suspend fun user(accessToken: String): GitHubIdentity {
        userFailure?.let { throw it }
        return identities.getValue(accessToken)
    }

    override suspend fun revoke(app: GitHubOAuthApp, accessToken: String) {
        revoked += accessToken
        revokeFailure?.let { throw it }
    }
}

/**
 * The links in memory, with the rules of the PostgreSQL store that the service relies on (one link per GitHub account
 * in an organization, single-use attempts); the refresh claims are scripted by [claims].
 */
class MemoryGitHubLinkStore : GitHubLinkStore {
    val attempts = mutableMapOf<Pair<Uuid, String>, GitHubLinkAttempt>()
    val links = mutableMapOf<Pair<Uuid, Uuid>, Pair<GitHubLink, GitHubTokens>>()
    val claims = ArrayDeque<GitHubTokenClaim>()
    val completions = ArrayDeque<() -> Boolean>()
    var abandoned = mutableListOf<Boolean>()
    var abandonAnswer = true
    var consumable = true
    var deferrable = true
    val deferred = mutableListOf<Pair<Int, Instant>>()

    override suspend fun startAttempt(attempt: GitHubLinkAttempt, now: Instant) {
        attempts.entries.removeAll { it.value.orgId == attempt.orgId && it.value.memberId == attempt.memberId }
        attempts[attempt.orgId to attempt.id] = attempt
    }

    override suspend fun attempt(orgId: Uuid, id: String): GitHubLinkAttempt? = attempts[orgId to id]

    override suspend fun consumeAttempt(orgId: Uuid, id: String): Boolean =
        attempts.remove(orgId to id) != null && consumable

    @Suppress("ReturnCount") // The compare-and-set of the real store.
    override suspend fun deferPoll(
        orgId: Uuid,
        id: String,
        expectedNextPollAt: Instant?,
        intervalSeconds: Int,
        nextPollAt: Instant,
    ): Boolean {
        val attempt = attempts[orgId to id] ?: return false
        if (expectedNextPollAt != null && (!deferrable || attempt.nextPollAt != expectedNextPollAt)) return false
        deferred += intervalSeconds to nextPollAt
        attempts[orgId to id] = GitHubLinkAttempt(
            id, orgId, attempt.memberId, attempt.kind, attempt.secret, attempt.binding, intervalSeconds, nextPollAt,
            attempt.expiresAt,
        )
        return true
    }

    override suspend fun saveLink(
        orgId: Uuid,
        memberId: Uuid,
        identity: GitHubIdentity,
        clientId: String,
        tokens: GitHubTokens,
        now: Instant,
    ): GitHubSavedLink {
        val holder = links.values.firstOrNull { it.first.orgId == orgId && it.first.identity.userId == identity.userId }
        if (holder != null && holder.first.memberId != memberId) throw GitHubAccountInUseException()
        val link = GitHubLink(
            orgId, memberId, identity, clientId, GitHubLinkState.LINKED, tokens.accessExpiresAt,
            tokens.refreshExpiresAt, now, now,
        )
        val previous = links.put(orgId to memberId, link to tokens)
        val replaced = previous?.let { (old, oldTokens) -> GitHubRevocable(old.clientId, oldTokens.accessToken) }
        return GitHubSavedLink(link, replaced)
    }

    override suspend fun link(orgId: Uuid, memberId: Uuid): GitHubLink? = links[orgId to memberId]?.first

    override suspend fun links(orgId: Uuid, limit: Int): List<GitHubLink> =
        links.values.map { it.first }.filter { it.orgId == orgId }.sortedBy { it.identity.login }.take(limit)

    override suspend fun removeLink(orgId: Uuid, memberId: Uuid): GitHubRevocable? =
        links.remove(orgId to memberId)?.let { (link, tokens) -> GitHubRevocable(link.clientId, tokens.accessToken) }

    override suspend fun removeLinksOfOtherClients(clientId: String): List<GitHubRevocable> {
        val stale = links.filterValues { (link, _) -> link.clientId != clientId }
        stale.keys.forEach { links.remove(it) }
        return stale.values.map { (link, tokens) -> GitHubRevocable(link.clientId, tokens.accessToken) }
    }

    override suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        clientId: String,
        now: Instant,
        validUntil: Instant,
        usableUntil: Instant,
        staleAfter: Duration,
    ): GitHubTokenClaim = claims.removeFirst()

    override suspend fun completeRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        tokens: GitHubTokens,
        now: Instant,
    ): Boolean = completions.removeFirst().invoke()

    override suspend fun abandonRefresh(
        orgId: Uuid,
        memberId: Uuid,
        claim: GitHubTokenClaim.Refresh,
        reconnect: Boolean,
    ): Boolean {
        abandoned += reconnect
        return abandonAnswer && reconnect
    }
}

/** A stored link of [memberId] to octocat, without its tokens. */
fun octocatLink(
    memberId: Uuid = DEV,
    expiresAt: Instant? = NOW,
    clientId: String = CLIENT_ID,
    state: GitHubLinkState = GitHubLinkState.LINKED,
) = GitHubLink(ORG, memberId, GitHubIdentity(7, "octocat"), clientId, state, expiresAt, null, NOW, NOW)
