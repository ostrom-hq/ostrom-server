// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.DeviceDaemonRules
import run.ostrom.server.runners.domain.DeviceDaemonUseCaseImpl
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RemoteSessionException
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.ResumableAnswer
import run.ostrom.server.runners.domain.ResumableSession
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE_USER
import run.ostrom.server.runners.fakes.BOB_LAPTOP
import run.ostrom.server.runners.fakes.ContextCipher
import run.ostrom.server.runners.fakes.FakeDeviceDaemons
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.aliceLogin
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's DeviceDaemonUseCase tests: the poll, the folders, the listings, on the device of the caller's sign-in. */
class DeviceDaemonTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val daemons = FakeDeviceDaemons()
    private val clock = SettableClock()
    private val slept = mutableListOf<Duration>()
    private var onSleep: suspend () -> Unit = {}
    private val daemon = DeviceDaemonUseCaseImpl(FakeSignedInDevices(), daemons, repository, ContextCipher, clock) {
        slept += it
        clock.now += it
        onSleep()
    }
    private val alice = Caller(ORG_A, "alice")
    private val laptop = aliceLogin("alice-laptop")
    private val browser = AuthenticatedPrincipal(ALICE_USER.toString(), PrincipalKind.USER, setOf("user:*"))
    private val folder = DaemonFolder("f1", "ostrom", "~/git/ostrom")
    private val conversation = "0f3c2b1a-9d8e-4c7b-a6f5-e4d3c2b1a090"

    private fun waiting(
        org: Uuid = ORG_A,
        folderId: String? = "f1",
        phase: String = RemoteSessionRules.WAITING_HOST,
    ) =
        session(SessionState.CREATING, orgId = org).copy(
            phase = phase,
            desiredState = DesiredState.RUNNING,
            kind = SessionKind.REMOTE,
            host = RemoteHost(LAPTOP, "ostrom", RemoteSessionRules.ASK, folderId),
        ).also { repository.sessions[it.id] = it }

    private suspend fun online(features: List<String> = listOf(DeviceDaemonRules.RESUME_FEATURE)) {
        daemons.publish(LAPTOP, listOf(folder))
        daemons.touch(LAPTOP, "1.0.0", features, clock.now())
    }

    private suspend fun refusal(type: String, block: suspend () -> Unit) {
        val refused = assertFailsWith<RemoteSessionException> { block() }
        assertEquals(type, refused.type)
    }

    @Test
    fun theDaemonChannelTakesTheCliSignInOfTheDeviceItself() = runTest {
        refusal("daemon_requires_device") { daemon.device(browser) }
        refusal("daemon_requires_device") { daemon.device(laptop.copy(kind = PrincipalKind.SERVICE)) }
        assertEquals(LAPTOP, daemon.device(laptop))
    }

    @Test
    fun aPollMarksTheDaemonOnlineAndAnswersTheWaitingSessionsOfItsOrganizationAtOnce() = runTest {
        val order = waiting()
        waiting(org = ORG_B)
        waiting(folderId = null)
        waiting(phase = RemoteSessionRules.STARTING_HOST)

        val poll = daemon.poll(alice, laptop, 20.seconds, "1.0.0", listOf("resume.v1"))

        assertEquals(listOf(order.id), poll.orders.map { it.sessionId })
        val first = poll.orders.single()
        assertEquals(listOf("f1", "ostrom", "claude", "ask", "creating"), with(first) {
            listOf(folderId, directoryName, agent, permissionMode, status)
        })
        assertEquals(order.updatedAt, first.requestedAt)
        assertEquals(Triple(clock.now(), "1.0.0", listOf("resume.v1")), daemons.seen[LAPTOP])
        assertTrue(slept.isEmpty())
    }

    @Test
    fun aQueueEntryWithoutAFolderIsNoOrder() = runTest {
        val folderless = session().copy(host = RemoteHost(LAPTOP, "ostrom", RemoteSessionRules.ASK))
        val nameless = session().copy(host = RemoteHost(LAPTOP, null, RemoteSessionRules.ASK, "f1"))
        val odd = object : CloudSessionsRepository by repository {
            override suspend fun daemonOrders(orgId: Uuid, deviceId: Uuid, limit: Int) =
                listOf(session(), folderless, nameless)
        }
        val reader = DeviceDaemonUseCaseImpl(FakeSignedInDevices(), daemons, odd, ContextCipher, clock)

        val orders = reader.poll(alice, laptop, Duration.ZERO, null).orders

        assertEquals(listOf(nameless.id to ""), orders.map { it.sessionId to it.directoryName })
    }

    @Test
    fun anEmptyPollReadsTheQueueEverySecondUpToTwentySeconds() = runTest {
        val poll = daemon.poll(alice, laptop, 60.seconds, null)

        assertTrue(poll.orders.isEmpty())
        assertEquals(List(20) { DeviceDaemonRules.POLL }, slept)
    }

    @Test
    fun byDefaultAPollWaitsOnTheCoroutinesClock() = runTest {
        val waiter = DeviceDaemonUseCaseImpl(FakeSignedInDevices(), daemons, repository, ContextCipher, clock)
        val started = testScheduler.currentTime
        assertTrue(waiter.poll(alice, laptop, 2.seconds, null).orders.isEmpty())
        assertEquals(2_000L, testScheduler.currentTime - started)
    }

    @Test
    fun aPollEndsWithTheFirstOrderThatArrives() = runTest {
        onSleep = { if (slept.size == 3) waiting() }

        val poll = daemon.poll(alice, laptop, 20.seconds, null)

        assertEquals(1, poll.orders.size)
        assertEquals(3, slept.size)
    }

    @Test
    fun aNegativeWaitAnswersAtOnce() = runTest {
        daemon.poll(alice, laptop, (-5).seconds, null)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun onlyADaemonThatListsConversationsIsHandedTheListings() = runTest {
        val request = daemons.askListing(LAPTOP, "f1", 20, null, clock.now(), clock.now() + 30.seconds)

        assertTrue(daemon.poll(alice, laptop, Duration.ZERO, null).listings.isEmpty())
        val listed = daemon.poll(alice, laptop, 20.seconds, null, listOf("resume.v1")).listings
        assertEquals(listOf(request), listed.map { it.requestId })
    }

    @Test
    fun aPollRefusesAMalformedVersionOrFeatures() = runTest {
        refusal("invalid_version") { daemon.poll(alice, laptop, Duration.ZERO, "1.0 beta") }
        refusal("invalid_features") { daemon.poll(alice, laptop, Duration.ZERO, null, listOf("Resume")) }
        refusal("invalid_features") { daemon.poll(alice, laptop, Duration.ZERO, null, List(9) { "f$it" }) }
        assertEquals(0, daemons.touches)
    }

    @Test
    fun theDaemonStopsAndItsDeviceIsOfflineAtOnce() = runTest {
        online()
        daemon.offline(laptop)
        assertEquals(null, daemon.owned(browser, LAPTOP).daemon)
    }

    @Test
    fun theFoldersAreReplacedAsAWhole() = runTest {
        assertEquals(listOf(folder), daemon.publish(laptop, listOf(folder)))
        daemon.publish(laptop, emptyList())
        assertTrue(daemon.owned(browser, LAPTOP).folders.isEmpty())
    }

    @Test
    fun foldersOutOfBoundsAreRefused() = runTest {
        val refusals = listOf(
            List(65) { DaemonFolder("f$it", "x") },
            listOf(folder, folder),
            listOf(DaemonFolder("a/b", "x")),
            listOf(DaemonFolder("f", "../etc")),
            listOf(DaemonFolder("f", "x", " ")),
            listOf(DaemonFolder("f", "x", "a".repeat(513))),
            listOf(DaemonFolder("f", "x", "a\nb")),
        )
        for (folders in refusals) refusal("invalid_folders") { daemon.publish(laptop, folders) }
        val longest = listOf(DaemonFolder("f", "x", "a".repeat(512)), DaemonFolder("g", "y"))
        assertEquals(longest, daemon.publish(laptop, longest))
    }

    @Test
    fun anotherPersonsDeviceIsNotFoundWhoeverAsks() = runTest {
        refusal("device_not_found") { daemon.owned(browser, BOB_LAPTOP) }
        refusal("device_not_found") { daemon.owned(browser, Uuid.random()) }
        refusal("device_not_found") { daemon.owned(browser.copy(kind = PrincipalKind.SERVICE), LAPTOP) }
        refusal("device_not_found") { daemon.owned(browser.copy(subjectId = "not-a-uuid"), LAPTOP) }
    }

    @Test
    fun theOwnerReadsTheirDevicesDaemonAndFolders() = runTest {
        online()
        val device = daemon.owned(browser, LAPTOP)
        assertEquals(listOf(folder), device.folders)
        assertEquals(true, device.daemon?.online)
        clock.now += 61.seconds
        assertEquals(false, daemon.owned(browser, LAPTOP).daemon?.online)
    }

    @Test
    fun aListingIsAnsweredByTheDaemonSealedAndDroppedOnceRead() = runTest {
        online()
        val at = Instant.parse("2026-10-30T09:00:00Z")
        val page = listOf(ResumableSession(conversation, "Fix the tests", at, 3))
        val answer = ResumableAnswer(page, "2026-10-30T08:00:00Z")
        onSleep = {
            val request = daemons.requests.keys.single()
            if (slept.size == 2) daemon.answer(laptop, request, answer)
        }

        assertEquals(answer, daemon.resumable(browser, LAPTOP, "f1", 20, null))
        assertEquals(2, slept.size)
        assertTrue(daemons.requests.isEmpty())
        assertEquals(1, daemons.dropped.size)
    }

    @Test
    fun theAnswerRestsSealedToItsListing() = runTest {
        online()
        val request = daemons.askListing(LAPTOP, "f1", 20, null, clock.now(), clock.now() + 30.seconds)
        val page = ResumableAnswer(listOf(ResumableSession(conversation, "Secret title", clock.now(), 1)))
        daemon.answer(laptop, request, page)
        assertTrue(daemons.answers.getValue(request).startsWith("device-daemon-listing:$request|"))
    }

    @Test
    fun theDaemonsRefusalIsTheMembersAnswer() = runTest {
        online()
        val refusal = ResumableAnswer(emptyList(), refused = "folder_missing")
        onSleep = { daemon.answer(laptop, daemons.requests.keys.single(), refusal) }

        val refused = assertFailsWith<RemoteSessionException> { daemon.resumable(browser, LAPTOP, "f1", 20, null) }

        assertEquals("folder_missing", refused.type)
        assertEquals(ErrorKind.UNPROCESSABLE, refused.kind)
    }

    @Test
    fun aDaemonThatDoesNotAnswerInTenSecondsIsATimeout() = runTest {
        online()

        val refused = assertFailsWith<RemoteSessionException> { daemon.resumable(browser, LAPTOP, "f1", 20, null) }

        assertEquals("device_no_answer", refused.type)
        assertEquals(ErrorKind.GATEWAY_TIMEOUT, refused.kind)
        assertEquals(40, slept.size)
        assertTrue(daemons.requests.isEmpty())
    }

    @Test
    fun anAnswerThatNoLongerOpensIsNoAnswer() = runTest {
        online()
        onSleep = { daemons.answers[daemons.requests.keys.single()] = "another-key|{}" }
        refusal("device_no_answer") { daemon.resumable(browser, LAPTOP, "f1", 20, null) }
    }

    @Test
    fun aCancelledWaitStillDropsItsListing() = runTest {
        online()
        onSleep = { throw CancellationException("gone") }
        assertFailsWith<CancellationException> { daemon.resumable(browser, LAPTOP, "f1", 20, null) }
        assertEquals(1, daemons.dropped.size)
    }

    @Test
    fun aListingNeedsAnOnlineDaemonThatListsAndAPublishedFolder() = runTest {
        refusal("invalid_limit") { daemon.resumable(browser, LAPTOP, "f1", 0, null) }
        refusal("invalid_limit") { daemon.resumable(browser, LAPTOP, "f1", 51, null) }
        refusal("invalid_cursor") { daemon.resumable(browser, LAPTOP, "f1", 20, "yesterday") }
        refusal("device_offline") { daemon.resumable(browser, LAPTOP, "f1", 20, null) }
        online()
        clock.now += 61.seconds
        refusal("device_offline") { daemon.resumable(browser, LAPTOP, "f1", 20, null) }
        online(features = emptyList())
        refusal("daemon_resume_unsupported") { daemon.resumable(browser, LAPTOP, "f1", 20, "2026-10-30T08:00:00Z") }
        online()
        refusal("folder_not_published") { daemon.resumable(browser, LAPTOP, "f2", 20, null) }
        assertTrue(daemons.requests.isEmpty())
    }

    @Test
    fun anAnswerNobodyWaitsForIsNotFound() = runTest {
        refusal("listing_not_found") { daemon.answer(laptop, Uuid.random(), ResumableAnswer(emptyList())) }
        val other = daemons.askListing(BOB_LAPTOP, "f1", 20, null, clock.now(), clock.now() + 30.seconds)
        refusal("listing_not_found") { daemon.answer(laptop, other, ResumableAnswer(emptyList())) }
    }

    @Test
    fun anAnswerOutOfBoundsIsRefused() = runTest {
        val at = clock.now()
        val good = ResumableSession(conversation, "Title", at, 1)
        val refusals = mapOf(
            ResumableAnswer(emptyList(), refused = "Folder Missing") to "invalid_reason",
            ResumableAnswer(List(51) { good }) to "invalid_listing",
            ResumableAnswer(listOf(good), refused = "folder_missing") to "invalid_listing",
            ResumableAnswer(emptyList(), next = "soon") to "invalid_listing",
            ResumableAnswer(listOf(good.copy(id = "not-a-uuid"))) to "invalid_listing",
            ResumableAnswer(listOf(good.copy(turns = -1))) to "invalid_listing",
            ResumableAnswer(listOf(good.copy(title = " "))) to "invalid_listing",
            ResumableAnswer(listOf(good.copy(title = "a".repeat(81)))) to "invalid_listing",
            ResumableAnswer(listOf(good.copy(title = "a\nb"))) to "invalid_listing",
        )
        for ((answer, type) in refusals) refusal(type) { daemon.answer(laptop, Uuid.random(), answer) }
    }

    @Test
    fun aConversationsTitleIsNeverPrinted() {
        val session = ResumableSession(conversation, "Secret", Instant.parse("2026-10-30T09:00:00Z"), 1)
        assertTrue("Secret" !in session.toString())
    }
}
