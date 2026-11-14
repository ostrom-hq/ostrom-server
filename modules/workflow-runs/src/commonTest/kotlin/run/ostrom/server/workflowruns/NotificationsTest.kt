// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.core.api.models.PackageMode
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.sessions.domain.models.SessionClosedException
import run.ostrom.server.workflowruns.domain.models.NotInPackageException
import run.ostrom.server.workflowruns.domain.models.RunPackageRevokedException
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.repositories.RunAudience
import run.ostrom.server.workflowruns.domain.repositories.RunQuery
import run.ostrom.server.workflowruns.domain.usecases.ListNotificationsUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.NotificationRequest
import run.ostrom.server.workflowruns.domain.usecases.SendNotificationUseCaseImpl
import run.ostrom.server.workflowruns.fakes.ADA
import run.ostrom.server.workflowruns.fakes.ADMIN
import run.ostrom.server.workflowruns.fakes.AGENT
import run.ostrom.server.workflowruns.fakes.BOB
import run.ostrom.server.workflowruns.fakes.FakePackages
import run.ostrom.server.workflowruns.fakes.FakeSessions
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.fakes.InMemoryRuns
import run.ostrom.server.workflowruns.fakes.ORG
import run.ostrom.server.workflowruns.fakes.OTHER_AGENT
import run.ostrom.server.workflowruns.fakes.PACKAGE
import run.ostrom.server.workflowruns.fakes.RecordingChannels
import run.ostrom.server.workflowruns.fakes.access
import run.ostrom.server.workflowruns.fakes.envelope
import run.ostrom.server.workflowruns.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class NotificationsTest {
    private val runs = InMemoryRuns()
    private val channels = RecordingChannels()
    private val send = SendNotificationUseCaseImpl(access(), runs, channels, FixedClock())
    private val list = ListNotificationsUseCaseImpl(access(), runs)

    private fun request(notifyId: String = "nt-1", sessionId: String? = "s-1", command: String? = "git push") =
        NotificationRequest(sessionId, "sha256:pkg", "n-notify", notifyId, command)

    @Test
    fun thePackagesMessageIsSentWithTheSessionsPersonRepositoryAndCommand() = runTest {
        val sent = send(AGENT, request(command = "git push origin main  # current branch: main"))
        assertEquals("ada ran git push origin main in acme/api (s-1)", sent.text, "The hook's note is not quoted")
        assertEquals(NotificationStatus.SENT, sent.status)
        assertEquals(listOf("log:team" to sent.text), channels.delivered)
        assertTrue(Regex("ntf_[0-9a-f]{32}").matches(sent.id), sent.id)
        val fields = listOf(sent.sessionId, sent.version, sent.digest, sent.dev)
        assertEquals(listOf("s-1", "v3", "sha256:pkg", "ada"), fields)
        assertEquals(PackageMode.ENFORCE, sent.mode)
    }

    @Test
    fun theSameActionOfASessionIsNotifiedOnce() = runTest {
        val first = send(AGENT, request())
        assertEquals(first, send(AGENT, request()), "A hook passing twice gets the first one back")
        send(AGENT, request(command = "git push --tags"))
        assertEquals(2, channels.delivered.size, "Sent once per action")
        assertEquals(2, runs.notifications.size)
    }

    @Test
    fun theCommandsSecretsAreMaskedAndItsBracesStayText() = runTest {
        val command = "curl -H 'Authorization: Bearer abcdefghijkl' https://x.test/{session}?token=1 --password=hunter2"
        val sent = send(AGENT, request(command = command))
        val masked = "curl -H 'Authorization: Bearer ***' https://x.test/{session}?token=*** --password=***"
        assertEquals("ada ran $masked in acme/api (s-1)", sent.text)
        assertEquals(sent.text, channels.delivered.single().second, "The channel gets the masked text only")
    }

    @Test
    fun anEndedSessionIsNotNotifiedFor() = runTest {
        val ended = SendNotificationUseCaseImpl(
            access(FakeSessions(listOf(session(ended = true)))),
            runs,
            channels,
            FixedClock(),
        )
        assertFailsWith<SessionClosedException> { ended(AGENT, request()) }
        assertTrue(channels.delivered.isEmpty())
    }

    @Test
    fun aNotificationNotSentGoesAgainAtTheNextPass() = runTest {
        assertEquals(NotificationStatus.NOT_SENT, send(AGENT, request(notifyId = "nt-2")).status)
        send(AGENT, request(notifyId = "nt-2"))
        assertEquals(2, channels.delivered.size)
    }

    @Test
    fun aShadowNodeIsLoggedOnlyAndAnUnconnectedChannelNotSent() = runTest {
        val shadow = send(AGENT, request(notifyId = "nt-3"))
        assertEquals(NotificationStatus.LOGGED_ONLY to "shadow ada", shadow.status to shadow.text)
        assertTrue(channels.delivered.isEmpty(), "A shadow node reaches no channel")
        val slack = send(AGENT, request(notifyId = "nt-2"))
        assertEquals(NotificationStatus.NOT_SENT to "not connected", slack.status to slack.error)
    }

    @Test
    fun aPersonOutsideASessionNotifiesAsThemselvesInThePackagesRepository() = runTest {
        val sent = send(BOB, request(sessionId = null, command = null))
        assertEquals("bob ran  in acme/api (-)", sent.text)
        assertNull(sent.sessionId)
        val noRepo = SendNotificationUseCaseImpl(
            access(packages = FakePackages(listOf(PACKAGE.copy(envelope = envelope(repo = null))))),
            runs,
            channels,
            FixedClock(),
        )
        assertEquals("bob ran  in  (-)", noRepo(BOB, request(sessionId = null, command = null)).text)
        val sessionWithoutRepo = SendNotificationUseCaseImpl(
            access(FakeSessions(listOf(session(repo = null)))),
            runs,
            channels,
            FixedClock(),
        )
        assertEquals("ada ran ls in acme/api (s-1)", sessionWithoutRepo(AGENT, request(command = "ls")).text)
    }

    @Test
    fun aRevokedPackageOrAnUnknownNotificationSendsNothing() = runTest {
        val revoked = SendNotificationUseCaseImpl(
            access(packages = FakePackages(listOf(PACKAGE.copy(envelope = envelope(revoked = true))))),
            runs,
            channels,
            FixedClock(),
        )
        assertFailsWith<RunPackageRevokedException> { revoked(AGENT, request()) }
        assertFailsWith<NotInPackageException> { send(AGENT, request(notifyId = "nt-9")) }
        assertFailsWith<NotInPackageException> { send(AGENT, request().copy(nodeId = "n-check")) }
        assertFailsWith<NotYourSessionException> { send(OTHER_AGENT, request()) }
        assertTrue(channels.delivered.isEmpty())
    }

    @Test
    fun theNotificationsAreReadByTheirPersonAnAdministratorAndTheSessionsAgentOnly() = runTest {
        val sent = send(AGENT, request())
        assertEquals(1, list(ADMIN, RunQuery(sessionId = "s-1")).size)
        assertFailsWith<RunSessionNotFoundException> { list(BOB, RunQuery(sessionId = "s-1")) }
        assertTrue(list(ADA, RunQuery(before = sent.id)).isEmpty(), "After the last notification")
        assertFailsWith<InvalidPageException> { list(ADA, RunQuery(before = "nope")) }
        assertEquals(1, list(ADA, RunQuery()).size)
        assertEquals(1, list(AGENT, RunQuery(sessionId = "s-1")).size)
        assertTrue(list(AGENT, RunQuery()).isEmpty())
        assertFailsWith<RunSessionNotFoundException> { list(OTHER_AGENT, RunQuery(sessionId = "s-1")) }
    }

    @Test
    fun theOrganizationsNotificationsAreListedToTheirPersonAndTheApproversOnly() = runTest {
        // TeamCode #238: without a session, a member lists their own and those of the sessions whose gates they
        // approve; an owner or an administrator too, no more.
        val carol = GateActor("carol", ORG, ActorKind.USER, personId = "carol")
        val list = ListNotificationsUseCaseImpl(access(FakeSessions(approvals = mapOf("carol" to listOf("s-1")))), runs)
        val ada = send(AGENT, request())
        val bob = send(BOB, request(notifyId = "nt-1", sessionId = null))
        assertEquals(emptyList(), list(ADMIN, RunQuery()), "An administrator lists no member's notifications")
        assertFailsWith<RunSessionNotFoundException> { list(ADMIN, RunQuery(sessionId = "s-1")) }
        assertEquals(listOf(ada.id), list(ADA, RunQuery()).map { it.id })
        assertEquals(listOf(bob.id), list(BOB, RunQuery()).map { it.id })
        assertEquals(listOf(ada.id), list(carol, RunQuery()).map { it.id })
        assertEquals(RunAudience("carol", listOf("s-1")), runs.queries.last().audience)
        assertEquals(listOf(ada.id), list(carol, RunQuery(sessionId = "s-1")).map { it.id })
        assertFailsWith<InvalidPageException> { list(BOB, RunQuery(before = ada.id)) }
        assertEquals(emptyList(), list(carol, RunQuery(before = ada.id)))
        assertFailsWith<InvalidPageException> { list(ADMIN, RunQuery(before = bob.id)) }
    }
}
