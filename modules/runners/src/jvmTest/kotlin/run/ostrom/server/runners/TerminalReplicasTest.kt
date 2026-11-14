// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TerminalHosts
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.fakes.testIdentitiesWith
import run.ostrom.server.runners.infrastructure.KtorTerminalForwarder
import run.ostrom.server.runners.infrastructure.TerminalForwarder
import run.ostrom.server.runners.infrastructure.TerminalRelay
import run.ostrom.server.runners.infrastructure.TerminalReplica
import run.ostrom.server.runners.presentation.TerminalCloseCodes
import run.ostrom.server.runners.presentation.TerminalControl
import run.ostrom.server.runners.presentation.TerminalJson
import run.ostrom.server.runners.presentation.TerminalRoutesDependencies
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * A terminal across replicas (TC-REP-1), on one server playing both: this replica ([SELF]) and the one [hosts] says
 * holds the runner ([OTHER]), which the [forwarder] reaches through the same server as a hop client. A member stays on
 * their socket while the runner moves between the two. On the JVM only: Ktor's test client has no WebSockets on
 * native platforms.
 */
class TerminalReplicasTest {
    private val hosts = FakeHosts()
    private val forwarder = TestForwarder()

    /** The runners' sockets, open while a test runs. */
    private val sockets = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val overrides = module {
        includes(testIdentities)
        single {
            val replica = TerminalReplica(SELF, hosts, forwarder, 50.milliseconds, 100.milliseconds)
            TerminalRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), replica)
        }
    }

    private class FakeHosts : TerminalHosts {
        @Volatile var host: String? = OTHER

        @Volatile var failing = false
        val calls = ConcurrentLinkedQueue<String>()

        private fun called(what: String) {
            calls += what
            if (failing) throw IOException("database down")
        }

        override suspend fun hold(orgId: Uuid, sessionId: Uuid, epoch: Long) = called("hold")

        override suspend fun renew(orgId: Uuid, sessionId: Uuid, epoch: Long) = called("renew")

        override suspend fun release(orgId: Uuid, sessionId: Uuid, epoch: Long) = called("release")

        override suspend fun host(orgId: Uuid, sessionId: Uuid): String? = host
    }

    private class TestForwarder : TerminalForwarder {
        lateinit var client: HttpClient

        @Volatile var failures = 0
        val urls = ConcurrentLinkedQueue<String>()

        override suspend fun forward(
            replicaUrl: String,
            sessionId: Uuid,
            ticket: String,
            after: TerminalRelay.Cursor?,
            block: suspend (DefaultWebSocketSession) -> Unit,
        ) {
            urls += replicaUrl
            if (failures > 0) {
                failures--
                throw IOException("unreachable")
            }
            KtorTerminalForwarder(client).forward(replicaUrl, sessionId, ticket, after, block)
        }
    }

    private suspend fun TestServer.liveSession(): Pair<Uuid, String> {
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val live = session(ownerId = ALICE.toString(), state = SessionState.RUNNING)
            .copy(epoch = 1, runnerTokenHash = tokens.hash(token))
        koin.get<CloudSessionsRepository>().createWithinCap(live, 5)
        return live.id to token
    }

    /** A runner's socket kept open until its job is cancelled, once the relay here knows it. */
    private suspend fun runner(
        ws: HttpClient,
        relay: TerminalRelay,
        id: Uuid,
        token: String,
    ): Pair<Job, DefaultClientWebSocketSession> {
        val socket = CompletableDeferred<DefaultClientWebSocketSession>()
        val job = sockets.launch {
            ws.webSocket("/api/v1/cloud/runner/terminal?sessionId=$id&epoch=1", request = { bearerAuth(token) }) {
                socket.complete(this)
                awaitCancellation()
            }
        }
        eventually { relay.runnerHere(id) }
        return job to socket.await()
    }

    private suspend fun Job.gone(relay: TerminalRelay, id: Uuid) {
        cancel()
        eventually { !relay.runnerHere(id) }
    }

    private suspend fun eventually(condition: suspend () -> Boolean) = withTimeout(10.seconds) {
        while (!condition()) delay(10.milliseconds)
    }

    /** Reads until this state of the runner, skipping the bytes and the states before it. */
    private suspend fun DefaultClientWebSocketSession.until(state: TerminalControl.Runner) = withTimeout(10.seconds) {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text && control(frame.readText()) == state) break
        }
    }

    private fun control(text: String) = TerminalJson.decodeFromString(TerminalControl.serializer(), text)

    private suspend fun DefaultClientWebSocketSession.nextBytes(): String = withTimeout(10.seconds) {
        var frame = incoming.receive()
        while (frame !is Frame.Binary) frame = incoming.receive()
        frame.readBytes().decodeToString()
    }

    @AfterTest
    fun closeTheSockets() = sockets.cancel()

    @Test
    fun aMemberFollowsTheRunnerFromOneReplicaToTheOther() = serverTest(listOf(RunnersModule), overrides) { server ->
        val (id, token) = server.liveSession()
        val relay = server.koin.get<TerminalRelay>()
        val ws = createClient { install(WebSockets) }
        forwarder.client = ws

        // The runner on the other replica: the member is relayed there.
        val (first, there) = runner(ws, relay, id, token)
        ws.webSocket("/api/v1/cloud/sessions/$id/terminal", request = { bearerAuth("alice") }) {
            until(TerminalControl.Runner(true))
            there.send(Frame.Binary(true, "$ ".encodeToByteArray()))
            assertEquals("$ ", nextBytes())
            send(Frame.Binary(true, "ls\r".encodeToByteArray()))
            assertEquals("ls\r", there.nextBytes())
            send("""{"type":"resize","columns":100,"rows":30}""")
            val resize = withTimeout(10.seconds) { (there.incoming.receive() as Frame.Text).readText() }
            assertEquals(TerminalControl.Resize(100, 30), control(resize))
            eventually { "renew" in hosts.calls }

            // It leaves: the member hears it, and waits here, where nobody holds it now.
            hosts.host = null
            first.gone(relay, id)
            until(TerminalControl.Runner(false))
            delay(300.milliseconds)

            // It comes back to this replica: the member is in its room.
            val (second, here) = runner(ws, relay, id, token)
            until(TerminalControl.Runner(true))
            here.send(Frame.Binary(true, "back".encodeToByteArray()))
            assertEquals("back", nextBytes())
            delay(300.milliseconds)

            // It leaves again, for the other replica, which has not got it yet: the member keeps looking.
            hosts.host = OTHER
            val looks = forwarder.urls.size
            second.gone(relay, id)
            eventually { forwarder.urls.size >= looks + 2 }
            assertEquals(setOf(OTHER), forwarder.urls.toSet())

            // This replica holds it again: the member waits in its room, and an unknown frame still closes the socket.
            hosts.host = SELF
            delay(300.milliseconds)
            send("""{"type":"dance"}""")
            assertEquals(TerminalCloseCodes.BAD_REQUEST, withTimeout(10.seconds) { closeReason.await()?.code })
        }
        assertTrue(hosts.calls.containsAll(listOf("hold", "renew", "release")), hosts.calls.toString())
    }

    @Test
    fun aRefusalThereIsTheMembersAndAnUnreachableReplicaIsLookedForAgain() =
        serverTest(listOf(RunnersModule), overrides) { server ->
            val (id, token) = server.liveSession()
            val relay = server.koin.get<TerminalRelay>()
            val ws = createClient { install(WebSockets) }
            forwarder.client = ws
            hosts.failing = true
            forwarder.failures = 1

            // The holds cannot be written: the runner is served all the same, the lease left to expire.
            val (job, _) = runner(ws, relay, id, token)
            ws.webSocket("/api/v1/cloud/sessions/$id/terminal", request = { bearerAuth("alice") }) {
                until(TerminalControl.Runner(false))
                until(TerminalControl.Runner(true))
            }
            eventually { relay.counts().third == 0 }
            ws.webSocket("/api/v1/cloud/sessions/$id/terminal", request = { bearerAuth("alice") }) {
                until(TerminalControl.Runner(true))
                send("""{"type":"dance"}""")
                assertEquals(TerminalCloseCodes.BAD_REQUEST, withTimeout(10.seconds) { closeReason.await()?.code })
            }
            eventually { "renew" in hosts.calls }
            job.gone(relay, id)
            eventually { "release" in hosts.calls }
        }

    @Test
    fun aReplacedRunnerSocketLeavesTheHoldToItsSuccessor() = serverTest(listOf(RunnersModule), overrides) { server ->
        val (id, token) = server.liveSession()
        val relay = server.koin.get<TerminalRelay>()
        val ws = createClient { install(WebSockets) }

        // The runner came back to this replica, for the same execution, before its old socket ended.
        val (old, _) = runner(ws, relay, id, token)
        val (new, _) = runner(ws, relay, id, token)
        eventually { hosts.calls.count { it == "hold" } == 2 }
        new.gone(relay, id)
        eventually { "release" in hosts.calls }
        old.cancel()
        delay(300.milliseconds)
        assertEquals(1, hosts.calls.count { it == "release" }, "The replaced socket gave back nothing")
    }

    @Test
    fun aReplicaToldItsUrlHoldsTheRunnersItServes() = serverTest(
        listOf(RunnersModule),
        testIdentitiesWith(
            mapOf("OSTROM_RUNNERS_ALLOWED_ORGS" to ORG_A.toString(), "OSTROM_RUNNERS_REPLICA_URL" to "$SELF/"),
        ),
    ) { server ->
        val (id, token) = server.liveSession()
        val relay = server.koin.get<TerminalRelay>()
        val replica = checkNotNull(server.koin.get<TerminalRoutesDependencies>().replica)
        assertEquals(SELF, replica.url)
        assertEquals(TerminalReplica.LOOK_EVERY, replica.lookEvery)
        val ws = createClient { install(WebSockets) }

        val (job, _) = runner(ws, relay, id, token)
        eventually { replica.hosts.host(ORG_A, id) == SELF }
        ws.webSocket("/api/v1/cloud/sessions/$id/terminal", request = { bearerAuth("alice") }) {
            until(TerminalControl.Runner(true))
            job.gone(relay, id)
            until(TerminalControl.Runner(false))
        }
        eventually { replica.hosts.host(ORG_A, id) == null }
    }

    private companion object {
        const val SELF = "http://replica-a:8080"
        // The test client reaches the server under test at localhost only.
        const val OTHER = "http://localhost"
    }
}
