// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.CloudSessionsSwitch
import run.ostrom.server.integrations.github.domain.GitHubAccountInUseException
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppMisconfiguredException
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubDeviceAuthorization
import run.ostrom.server.integrations.github.domain.GitHubDeviceFlowDisabledException
import run.ostrom.server.integrations.github.domain.GitHubDeviceLinkProgress
import run.ostrom.server.integrations.github.domain.GitHubDevicePoll
import run.ostrom.server.integrations.github.domain.GitHubLinkActor
import run.ostrom.server.integrations.github.domain.GitHubLinkAdminOnlyException
import run.ostrom.server.integrations.github.domain.GitHubLinkAppNotConfiguredException
import run.ostrom.server.integrations.github.domain.GitHubLinkAttempt
import run.ostrom.server.integrations.github.domain.GitHubLinkBrowserMismatchException
import run.ostrom.server.integrations.github.domain.GitHubLinkCloudOffException
import run.ostrom.server.integrations.github.domain.GitHubLinkDeniedException
import run.ostrom.server.integrations.github.domain.GitHubLinkKind
import run.ostrom.server.integrations.github.domain.GitHubLinkMemberNotFoundException
import run.ostrom.server.integrations.github.domain.GitHubLinkNotFoundException
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.GitHubLinkStatus
import run.ostrom.server.integrations.github.domain.GitHubLinks
import run.ostrom.server.integrations.github.domain.GitHubRevocable
import run.ostrom.server.integrations.github.domain.GitHubUnreachableException
import run.ostrom.server.integrations.github.domain.sha256Hex
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class GitHubLinksTest {
    private val clock = SettableClock()
    private val apps = SettableApps()
    private val store = MemoryGitHubLinkStore()
    private val github = FakeGitHubUserAuth()
    private val members = SettableMembers(mutableSetOf(ORG to OWNER, ORG to DEV, ORG to ROBOT))
    private val audit = RecordingAudit()
    private var cloud = true
    private val links = GitHubLinks(apps, store, github, members, audit, CloudSessionsSwitch { cloud }, REDIRECT, clock)

    private val owner = actor(OWNER, admin = true)
    private val dev = actor(DEV, admin = false)

    private fun actor(member: Uuid, admin: Boolean) = GitHubLinkActor(
        ORG, member, admin, AuthenticatedPrincipal("usr_$member", PrincipalKind.USER, setOf("user:*")),
    )

    /** Starts a device link for [dev] and lets its first poll slot come. */
    private suspend fun deviceAttempt(): String {
        val id = links.startDevice(dev).id
        clock.now += 5.seconds
        return id
    }

    @Test
    fun aMemberLinksByTheDeviceFlowAtGitHubsPaceAndTheLinkIsAudited() = runTest {
        val link = links.startDevice(dev)
        assertEquals("ABCD-1234", link.userCode)
        assertEquals("https://github.com/login/device", link.verificationUri)
        assertEquals(NOW + 900.seconds, link.expiresAt)
        assertEquals(5, link.intervalSeconds)
        assertEquals(GitHubDeviceLinkProgress.Pending(5), links.pollDevice(dev, link.id), "Before the interval")
        assertEquals(emptyList(), github.polled, "GitHub is not asked before its interval")

        clock.now += 5.seconds
        github.polls += GitHubDevicePoll.Pending
        assertEquals(GitHubDeviceLinkProgress.Pending(5), links.pollDevice(dev, link.id))
        clock.now += 5.seconds
        github.polls += GitHubDevicePoll.Authorized(tokens("ghu_device"))
        val linked = assertIs<GitHubDeviceLinkProgress.Linked>(links.pollDevice(dev, link.id)).status
        assertEquals(GitHubLinkState.LINKED, linked.state)
        assertEquals("octocat", linked.link?.identity?.login)
        assertEquals(listOf("device-code", "device-code"), github.polled)
        assertTrue(store.attempts.isEmpty(), "The attempt is spent")
        assertEquals(listOf(GitHubLinks.CREATED), audit.records.map { it.event })
        assertEquals(DEV.toString(), audit.records.single().subjectId)
        assertEquals(GitHubLinkStatus(DEV, true, store.link(ORG, DEV), GitHubLinkState.LINKED), links.status(dev))
    }

    @Test
    fun theDeviceFlowKeepsGitHubsIntervalAndExpiryWithinBounds() = runTest {
        github.device = { GitHubDeviceAuthorization("dc", "WXYZ-0000", "https://github.com/login/device", 10, 1) }
        val short = links.startDevice(dev)
        assertEquals(5, short.intervalSeconds, "At least five seconds between two polls")
        assertEquals(NOW + 60.seconds, short.expiresAt, "At least a minute to enter the code")
        github.device = { GitHubDeviceAuthorization("dc", "WXYZ-0000", "https://github.com/login/device", 9999, 600) }
        val long = links.startDevice(dev)
        assertEquals(60, long.intervalSeconds)
        assertEquals(NOW + 15.minutes, long.expiresAt)
        assertEquals(1, store.attempts.size, "One pending attempt per member")
    }

    @Test
    fun aSlowDownLengthensTheIntervalAndTheEndsOfTheDeviceFlowSpendTheAttempt() = runTest {
        var id = deviceAttempt()
        github.polls += GitHubDevicePoll.SlowDown(15)
        assertEquals(GitHubDeviceLinkProgress.Pending(15), links.pollDevice(dev, id))
        assertEquals(15 to clock.now + 15.seconds, store.deferred.last())
        clock.now += 15.seconds
        github.polls += GitHubDevicePoll.SlowDown(1)
        assertEquals(GitHubDeviceLinkProgress.Pending(15), links.pollDevice(dev, id), "Never shorter than before")
        clock.now += 15.seconds
        github.polls += GitHubDevicePoll.Expired
        assertEquals(GitHubDeviceLinkProgress.Expired, links.pollDevice(dev, id))
        assertFailsWith<GitHubLinkNotFoundException> { links.pollDevice(dev, id) }

        id = deviceAttempt()
        github.polls += GitHubDevicePoll.Denied
        assertEquals(GitHubDeviceLinkProgress.Denied, links.pollDevice(dev, id))
        assertTrue(store.attempts.isEmpty())

        id = deviceAttempt()
        clock.now += 15.minutes
        assertEquals(GitHubDeviceLinkProgress.Expired, links.pollDevice(dev, id), "Expired here: GitHub not asked")
        assertTrue(store.attempts.isEmpty())
    }

    @Test
    fun aPollThatLosesItsSlotToAnotherWaitsAndOnlyItsMemberPolls() = runTest {
        val id = deviceAttempt()
        store.deferrable = false
        assertEquals(GitHubDeviceLinkProgress.Pending(5), links.pollDevice(dev, id))
        assertEquals(emptyList(), github.polled, "The concurrent poll asked GitHub, not this one")
        assertFailsWith<GitHubLinkNotFoundException> { links.pollDevice(owner, id) }
        assertFailsWith<GitHubLinkNotFoundException> { links.pollDevice(dev, "unknown") }
        store.attempts[ORG to "web"] = GitHubLinkAttempt("web", ORG, DEV, GitHubLinkKind.WEB, "v", "b", 0, NOW, NOW)
        assertFailsWith<GitHubLinkNotFoundException> { links.pollDevice(dev, "web") }
        apps.app = GitHubAppCredentials()
        assertFailsWith<GitHubLinkAppNotConfiguredException> { links.pollDevice(dev, id) }
    }

    @Test
    fun noLinkStartsWhileCloudSessionsAreOffOrWithoutTheAppsClient() = runTest {
        cloud = false
        assertFailsWith<GitHubLinkCloudOffException> { links.startDevice(dev) }
        assertFailsWith<GitHubLinkCloudOffException> { links.startWeb(dev, "session") }
        cloud = true
        apps.app = GitHubAppCredentials(appId = "42", clientId = CLIENT_ID)
        assertFailsWith<GitHubLinkAppNotConfiguredException> { links.startDevice(dev) }
        assertFailsWith<GitHubLinkAppNotConfiguredException> { links.startWeb(dev, "session") }
        apps.app = GitHubAppCredentials(appId = "42", clientSecret = "secret")
        assertFailsWith<GitHubLinkAppNotConfiguredException> { links.startDevice(dev) }
        val status = links.status(dev)
        assertEquals(GitHubLinkStatus(DEV, appConfigured = false, link = null, state = null), status)
    }

    @Test
    fun gitHubUnreachableIsUnavailableAndItsOwnRefusalsPassAsTheyAre() = runTest {
        github.device = { throw GitHubCallException("GitHub did not answer.") }
        assertFailsWith<GitHubUnreachableException> { links.startDevice(dev) }
        github.device = { throw GitHubDeviceFlowDisabledException() }
        assertFailsWith<GitHubDeviceFlowDisabledException> { links.startDevice(dev) }
        github.device = { throw CancellationException("gone") }
        assertFailsWith<CancellationException> { links.startDevice(dev) }
    }

    @Test
    fun theWebFlowLinksTheMemberOfTheSessionThatStartedItOnce() = runTest {
        val url = links.startWeb(dev, "session-dev")
        val state = url.substringAfter("state=").substringBefore('&')
        assertTrue(state.startsWith("$ORG."), state)
        val challenge = url.substringAfter("code_challenge=")
        assertEquals(43, challenge.length)
        assertEquals(emptyList(), audit.records, "Started for oneself: nothing to say yet")

        val status = links.completeWeb(state, "code-1", null, "session-dev" to dev.principal)
        assertEquals("octocat", status.link?.identity?.login)
        val (code, redirect, verifier) = github.exchanged.single()
        assertEquals("code-1" to REDIRECT, code to redirect)
        assertEquals(challenge, GitHubLinks.challenge(verifier), "The verifier of the challenge sent at the start")
        assertEquals(listOf(GitHubLinks.CREATED), audit.records.map { it.event })
        assertFailsWith<GitHubLinkNotFoundException> {
            links.completeWeb(state, "code-1", null, "session-dev" to dev.principal)
        }
    }

    @Test
    fun anOwnerLinksARobotAccountForAServiceMember() = runTest {
        github.exchange = { tokens("ghu_robot") }
        val url = links.startWeb(owner, "session-owner", ROBOT)
        assertEquals(listOf(GitHubLinks.STARTED), audit.records.map { it.event })
        assertEquals(ROBOT.toString(), audit.records.single().subjectId)
        val state = url.substringAfter("state=").substringBefore('&')
        val status = links.completeWeb(state, "code", null, "session-owner" to owner.principal)
        assertEquals(ROBOT, status.memberId)
        assertEquals("acme-bot", links.status(owner, ROBOT).link?.identity?.login)
        assertEquals(listOf("acme-bot"), links.links(owner).map { it.link?.identity?.login })
        assertEquals(owner.principal, audit.records.last().actor)

        assertFailsWith<GitHubLinkAdminOnlyException> { links.startWeb(dev, "session-dev", ROBOT) }
        assertFailsWith<GitHubLinkAdminOnlyException> { links.status(dev, ROBOT) }
        assertFailsWith<GitHubLinkAdminOnlyException> { links.links(dev) }
        assertFailsWith<GitHubLinkAdminOnlyException> { links.unlink(dev, ROBOT) }
        assertFailsWith<GitHubLinkMemberNotFoundException> { links.status(owner, Uuid.random()) }
        assertEquals(DEV, links.status(dev, DEV).memberId, "Naming oneself is one's own link")
    }

    @Test
    fun aCallbackFromAnotherBrowserLeavesTheAttemptForTheRightOne() = runTest {
        val state = links.startWeb(dev, "session-dev").substringAfter("state=").substringBefore('&')
        assertFailsWith<GitHubLinkBrowserMismatchException> {
            links.completeWeb(state, "code", null, "session-other" to owner.principal)
        }
        assertFailsWith<GitHubLinkBrowserMismatchException> { links.completeWeb(state, "code", null, null) }
        assertEquals(1, store.attempts.size, "Still there for the session that started it")
        store.attempts.values.single().let {
            store.attempts[ORG to it.id] =
                GitHubLinkAttempt(it.id, ORG, DEV, GitHubLinkKind.WEB, it.secret, null, 0, NOW, it.expiresAt)
        }
        assertFailsWith<GitHubLinkBrowserMismatchException> {
            links.completeWeb(state, "code", null, "session-dev" to dev.principal)
        }
    }

    @Test
    fun aCallbackOfAnUnknownExpiredCancelledOrMalformedAttemptLinksNothing() = runTest {
        val session = "session-dev" to dev.principal
        val malformed = listOf(null, "nope", "${"-".repeat(36)}.${"a".repeat(43)}", "$ORG.${"a".repeat(43)}")
        for (state in malformed) {
            assertFailsWith<GitHubLinkNotFoundException>(state) { links.completeWeb(state, "code", null, session) }
        }
        val deviceState = "$ORG.${"b".repeat(43)}"
        store.attempts[ORG to sha256Hex(deviceState)] =
            GitHubLinkAttempt(sha256Hex(deviceState), ORG, DEV, GitHubLinkKind.DEVICE, "dc", null, 5, NOW, NOW)
        assertFailsWith<GitHubLinkNotFoundException> { links.completeWeb(deviceState, "code", null, session) }

        fun start() = links.startWeb(dev, "session-dev").substringAfter("state=").substringBefore('&')
        var state = start()
        clock.now += 11.minutes
        assertFailsWith<GitHubLinkNotFoundException>("Expired") { links.completeWeb(state, "code", null, session) }
        state = start()
        assertFailsWith<GitHubLinkDeniedException> { links.completeWeb(state, null, "access_denied", session) }
        state = start()
        assertFailsWith<GitHubLinkNotFoundException> { links.completeWeb(state, null, null, session) }
        state = start()
        assertFailsWith<GitHubLinkNotFoundException> { links.completeWeb(state, "bad code!", null, session) }
        state = start()
        store.consumable = false
        assertFailsWith<GitHubLinkNotFoundException>("Taken by a concurrent callback") {
            links.completeWeb(state, "code", null, session)
        }
        store.consumable = true
        state = start()
        apps.app = GitHubAppCredentials()
        assertFailsWith<GitHubLinkAppNotConfiguredException> { links.completeWeb(state, "code", null, session) }
        assertEquals(emptyList(), github.exchanged)
        assertNull(store.link(ORG, DEV))
    }

    @Test
    fun aTokenNobodyStoresIsRevokedAtOnce() = runTest {
        // A member who left meanwhile.
        members.active -= ORG to DEV
        var state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        assertFailsWith<GitHubLinkMemberNotFoundException> {
            links.completeWeb(state, "code", null, "s" to dev.principal)
        }
        assertEquals(listOf("ghu_web"), github.revoked)
        members.active += ORG to DEV

        // The account is another member's.
        github.exchange = { tokens("ghu_robot") }
        state = links.startWeb(owner, "o", ROBOT).substringAfter("state=").substringBefore('&')
        links.completeWeb(state, "code", null, "o" to owner.principal)
        state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        assertFailsWith<GitHubAccountInUseException> { links.completeWeb(state, "code", null, "s" to dev.principal) }
        assertEquals(listOf("ghu_web", "ghu_robot"), github.revoked)

        // GitHub does not say whose token it is; its revocation fails too, which changes nothing.
        github.userFailure = GitHubCallException("down")
        github.revokeFailure = IllegalStateException("down too")
        state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        assertFailsWith<GitHubUnreachableException> { links.completeWeb(state, "code", null, "s" to dev.principal) }
        assertEquals(3, github.revoked.size)
        assertNull(store.link(ORG, DEV))
    }

    @Test
    fun aCancelledLinkIsNotRevokedHereAndACancelledRevocationStaysCancelled() = runTest {
        github.userFailure = CancellationException("gone")
        val state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        assertFailsWith<CancellationException> { links.completeWeb(state, "code", null, "s" to dev.principal) }
        assertEquals(emptyList(), github.revoked)
        github.revokeFailure = CancellationException("gone")
        assertFailsWith<CancellationException> {
            GitHubLinks.revokeToken(github, OAUTH_APP, GitHubRevocable(CLIENT_ID, "ghu_x"))
        }
    }

    @Test
    fun unlinkingRemovesTheLinkRevokesItsTokenAndIsAudited() = runTest {
        val state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        links.completeWeb(state, "code", null, "s" to dev.principal)
        links.unlink(dev)
        assertNull(store.link(ORG, DEV))
        assertEquals(listOf("ghu_web"), github.revoked)
        assertEquals(GitHubLinks.REMOVED, audit.records.last().event)
        links.unlink(dev)
        assertEquals(1, audit.records.count { it.event == GitHubLinks.REMOVED }, "Unlinking twice says nothing")

        // An owner unlinks a member; the App's client is gone, so nothing can be revoked.
        val again = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        links.completeWeb(again, "code", null, "s" to dev.principal)
        apps.app = GitHubAppCredentials()
        links.unlink(owner, DEV)
        assertNull(store.link(ORG, DEV))
        assertEquals(1, github.revoked.size)
    }

    @Test
    fun relinkingRevokesTheReplacedTokenAndAnOAuthAppsTokenIsRefusedAndRevoked() = runTest {
        suspend fun link(token: String) {
            github.exchange = { tokens(token) }
            val state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
            links.completeWeb(state, "code", null, "s" to dev.principal)
        }
        link("ghu_web")
        link("ghu_web")
        assertEquals(emptyList(), github.revoked, "The same token: still the link's")
        link("ghu_device")
        assertEquals(listOf("ghu_web"), github.revoked)

        github.exchange = { tokens("gho_oauth_app") }
        val state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        assertFailsWith<GitHubAppMisconfiguredException> {
            links.completeWeb(state, "code", null, "s" to dev.principal)
        }
        assertEquals(listOf("ghu_web", "gho_oauth_app"), github.revoked)
        assertEquals("ghu_device", store.links.getValue(ORG to DEV).second.accessToken, "The link is kept")
    }

    @Test
    fun anOwnerUnlinksAMemberWhoLeft() = runTest {
        val state = links.startWeb(dev, "s").substringAfter("state=").substringBefore('&')
        links.completeWeb(state, "code", null, "s" to dev.principal)
        members.active -= ORG to DEV
        assertFailsWith<GitHubLinkMemberNotFoundException> { links.status(owner, DEV) }
        links.unlink(owner, DEV)
        assertNull(store.link(ORG, DEV))
        assertEquals(listOf("ghu_web"), github.revoked)
    }

    @Test
    fun onlyTheAppThatIssuedATokenRevokesIt() = runTest {
        GitHubLinks.revokeToken(github, OAUTH_APP, GitHubRevocable("Iv1.previous", "ghu_old"))
        assertEquals(emptyList(), github.revoked)
        GitHubLinks.revokeToken(github, OAUTH_APP, GitHubRevocable(CLIENT_ID, "ghu_new"))
        assertEquals(listOf("ghu_new"), github.revoked)
    }

    @Test
    fun aLinkOfAnotherAppOrWithoutAnAppNeedsReconnecting() {
        assertEquals(null, GitHubLinkStatus.of(DEV, OAUTH_APP, null).state)
        assertEquals(GitHubLinkState.LINKED, GitHubLinkStatus.of(DEV, OAUTH_APP, octocatLink()).state)
        val other = octocatLink(clientId = "Iv1.previous")
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, GitHubLinkStatus.of(DEV, OAUTH_APP, other).state)
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, GitHubLinkStatus.of(DEV, null, octocatLink()).state)
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, GitHubLinkState.of("something-new"))
        assertEquals(GitHubLinkState.LINKED, GitHubLinkState.of("linked"))
    }

    private companion object {
        const val REDIRECT = "https://ostrom.example/api/v1/github/link/callback"
    }
}
