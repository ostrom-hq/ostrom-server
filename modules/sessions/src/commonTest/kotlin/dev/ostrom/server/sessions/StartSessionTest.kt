package dev.ostrom.server.sessions

import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.InvalidSessionRequestException
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.usecases.SessionSegments
import dev.ostrom.server.sessions.fakes.ORG
import dev.ostrom.server.sessions.fakes.SessionBench
import dev.ostrom.server.sessions.fakes.agent
import dev.ostrom.server.sessions.fakes.user
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StartSessionTest {
    private val start = StartSession(
        id = "s-1",
        launchId = "from-the-body",
        repo = " acme/api ",
        realRepo = "acme/api",
        cwd = "/work",
        agent = "claude-code",
        harnessVersion = "0.9.0",
        packageDigest = "pkg-1",
        model = "sonnet",
    )

    @Test
    fun aSessionIsTheCredentialsPersonsAndLaunchs() = runTest {
        val bench = SessionBench()
        val session = bench.start(agent(), start)

        assertEquals("s-1", session.id)
        assertEquals("s-1", session.rootId)
        assertEquals(1, session.segment)
        assertEquals("lyes", session.dev, "The person is the credential's")
        assertEquals("launch-1", session.launchId, "An agent token's launch wins over the body's")
        assertEquals("acme/api", session.repo)
        assertNull(session.realRepo, "The same repository twice is not another one")
        assertEquals(Instant.parse("2026-10-30T09:00:00.123Z"), session.startedAt, "Stored to the millisecond")
        assertNull(session.lastSeenAt, "A session is followed once its launcher gives a sign of life")
        assertEquals(listOf("s-1"), bench.repository.locks)
        assertEquals("from-the-body", bench.start(user("rev-b"), start.copy(id = "s-2")).launchId)
    }

    @Test
    fun theSameLaunchFindsItsSegmentAgain() = runTest {
        val bench = SessionBench()
        val first = bench.start(user(), start.copy(launchId = null, packageDigest = null))
        val again = bench.start(user(), start.copy(launchId = null))
        assertEquals(first, again, "A hook without the launcher finds the open segment")

        val named = bench.start(agent(), start)
        assertEquals(first.id, named.id)
        assertEquals("launch-1", named.launchId, "The launcher names the segment its hook opened")
        assertEquals("pkg-1", named.packageDigest)
        assertEquals(named, bench.start(agent(), start.copy(packageDigest = "pkg-2")))
        assertEquals(named, bench.start(user(), start.copy(launchId = null)))
        assertEquals(1, bench.repository.rows.size)
    }

    @Test
    fun anotherLaunchSupersedesTheOpenSegment() = runTest {
        val bench = SessionBench()
        val first = bench.start(agent("launch-1"), start)
        bench.clock.now += 5.minutes
        val second = bench.start(agent("launch-2"), start)

        val closed = bench.repository.get(ORG, first.id) ?: error("kept")
        assertEquals(Session.SUPERSEDED, closed.source)
        assertEquals("rcpt_session_s-1", closed.receiptId)
        assertEquals("s-1~r2", second.id)
        assertEquals(2, second.segment)
        assertEquals("s-1", second.rootId)
        assertEquals("s-1", second.resumedFrom)
        assertEquals("rcpt_session_s-1", second.previousReceiptId)
        assertEquals("launch-2", second.launchId)

        bench.end(agent("launch-2"), second.id, EndSession())
        val third = bench.start(agent("launch-2"), start)
        assertEquals("s-1~r3", third.id, "After a clean end, the same launch resumes in a new segment")
        assertEquals("rcpt_session_s-1~r2", third.previousReceiptId)
    }

    @Test
    fun anotherPersonsSessionIsNotYours() = runTest {
        val bench = SessionBench()
        bench.start(agent(), start)
        assertFailsWith<NotYourSessionException> { bench.start(agent(person = "mallory"), start) }
        assertEquals(1, bench.repository.rows.size)
    }

    @Test
    fun aMalformedStartIsRefusedAndTheRestBounded() = runTest {
        val bench = SessionBench()
        for (id in listOf("", "a b", "s~r2", "x".repeat(101))) {
            assertFailsWith<InvalidSessionRequestException> { bench.start(user(), start.copy(id = id)) }
        }
        assertFailsWith<InvalidSessionRequestException> { bench.start(user(), start.copy(agent = " ")) }
        val bounded = SessionSegments.bounded(
            start.copy(repo = "  ", launchId = "l".repeat(80), cwd = "c".repeat(2000), agent = " codex "),
        )
        assertNull(bounded.repo)
        assertEquals(SessionSegments.LAUNCH, bounded.launchId?.length)
        assertEquals(SessionSegments.PATH, bounded.cwd?.length)
        assertEquals("codex", bounded.agent)
        assertTrue(SessionSegments.sameLaunch(null, "x") && SessionSegments.sameLaunch("x", null))
        assertTrue(SessionSegments.sameLaunch("x", "x") && !SessionSegments.sameLaunch("x", "y"))
    }
}
