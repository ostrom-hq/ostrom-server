// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePush
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.GateResponse
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.receipts.domain.services.ReceiptRule
import dev.ostrom.server.receipts.domain.services.ReceiptRules
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.usecases.CloseSessionUseCaseImpl
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * AUDIT-SERVEUR-3 B2: a gate asked while its session ends is either in the session's signed receipt, or refused with a
 * 409; never accepted and missing from the receipt. Real PostgreSQL, real HTTP, real gates and receipts. The receipt
 * of a session cites its gates by its subject digest: the test computes the digest of the gates the session holds once
 * ended, and the receipt must carry that one.
 */
class SessionEndGateRacePostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")

    /** The rules in force; the first read after [arm] waits for [release], so a test can act while an end reads. */
    private class HeldRules : ReceiptRules {
        private val mutex = Mutex()
        private var hold: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun arm() = mutex.withLock { hold = release }

        override suspend fun rulesFor(orgId: Uuid, repo: String?): List<ReceiptRule> {
            val held = mutex.withLock { hold.also { hold = null } }
            if (held != null) {
                entered.complete(Unit)
                held.await()
            }
            return emptyList()
        }
    }

    // Tokens are `agent:a:<launch>`, an agent token of `lyes`; a push to main waits for a yes of `rev-b`.
    private fun raceTest(rules: ReceiptRules, block: suspend (TestServer) -> Unit) = serverTest(
        listOf(InstanceModule, ReceiptsModule, GatesModule, SessionsModule),
        module {
            single<TokenAuthenticator> {
                object : TokenAuthenticator {
                    override suspend fun authenticate(token: String) =
                        AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                }
            }
            single<GateActorResolver> {
                object : GateActorResolver {
                    override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor {
                        val launch = principal.subjectId.substringAfterLast(':')
                        return GateActor(
                            "agent-$launch",
                            orgA,
                            ActorKind.AGENT_SESSION,
                            launchId = launch,
                            personId = "lyes",
                        )
                    }
                }
            }
            single<PrincipalOrgResolver> {
                object : PrincipalOrgResolver {
                    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?) =
                        OrgPrincipal(orgA, Uuid.random(), OrgRole.MEMBER)
                }
            }
            single<GateNodeResolver> {
                object : GateNodeResolver {
                    override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                        GateNode("ship", "g-main", "Merge into main", listOf("rev-b"), enforced = true)
                            .takeIf { actionClass == "push-main" }
                }
            }
            single<ReceiptRules> { rules }
        },
    ) { server -> block(server) }

    private suspend fun TestServer.post(launch: String, path: String, body: Any): HttpResponse = client.post(path) {
        header(HttpHeaders.Authorization, "Bearer agent:a:$launch")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.start(id: String) {
        val started = post(launchOf(id), "/api/v1/sessions", """{"id":"$id","agent":"claude-code","repo":"acme/api"}""")
        assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
    }

    /** Session [id] asks to push [sha] to main. */
    private suspend fun TestServer.ask(id: String, sha: String): HttpResponse {
        val push = GatePush("git@github.com:acme/api.git", listOf(GatePushRef("refs/heads/main", sha)), false)
        val ask = GateAskRequest(
            sessionId = id, repo = "acme/api", cls = "push-main", command = "git push origin main", push = push,
            diff = "+$sha",
        )
        return post(launchOf(id), "/api/v1/gates", ask)
    }

    private suspend fun TestServer.end(id: String) {
        val ended = post(launchOf(id), "/api/v1/sessions/$id/end", "{}")
        assertEquals(HttpStatusCode.OK, ended.status, ended.bodyAsText())
    }

    /** The gates the ended session [id] holds; its signed receipt must cover exactly these. */
    private suspend fun TestServer.assertReceiptCoversItsGates(id: String): List<String> {
        val session = assertNotNull(koin.get<SessionsRepository>().get(orgA, id))
        val gates = koin.get<ListSessionGatesUseCase>()(orgA, id)
        val receipt = koin.get<GetReceiptUseCase>()(orgA, assertNotNull(session.receiptId, "$id is signed"))
        val expected = Digests.digestOf(
            CloseSessionUseCaseImpl.preimage(session, assertNotNull(session.endedAt), session.exitCode, gates),
        )
        val digest = receipt.getValue("subject").jsonObject.getValue("digest").jsonPrimitive.content
        assertEquals(expected, digest, "The receipt of $id covers its gates ${gates.map { it.id }}")
        return gates.map { it.id.toString() }
    }

    private fun launchOf(id: String) = "launch-$id"

    private fun shaOf(n: Int) = n.toString(16).padStart(40, '0')

    @Test
    fun aGateAskedWhileItsSessionEndsIsInTheSignedReceipt() = HeldRules().let { rules ->
        raceTest(rules) { server ->
            server.start("s-1")
            rules.arm()
            coroutineScope {
                val ending = async { server.end("s-1") }
                // The end has read what it reads before writing the end: the session is still open.
                rules.entered.await()
                val asked = server.ask("s-1", shaOf(1))
                assertEquals(HttpStatusCode.OK, asked.status, asked.bodyAsText())
                rules.release.complete(Unit)
                ending.await()
                val gate = asked.body<GateResponse>().gate.id
                assertTrue(gate in server.assertReceiptCoversItsGates("s-1"), "The accepted gate is in the session")
            }
        }
    }

    @Test
    fun aGateAskedAsItsSessionEndsIsSignedOrRefused() = raceTest(HeldRules()) { server ->
        val ids = (1..RACES).map { "s-race-$it" }
        ids.forEach { server.start(it) }
        val outcomes = coroutineScope {
            ids.mapIndexed { n, id ->
                val go = CompletableDeferred<Unit>()
                val asking = async {
                    go.await()
                    server.ask(id, shaOf(n + 1))
                }
                val ending = async {
                    go.await()
                    server.end(id)
                }
                go.complete(Unit)
                async { ending.await(); id to asking.await() }
            }.awaitAll()
        }
        for ((id, asked) in outcomes) {
            val gates = server.assertReceiptCoversItsGates(id)
            when (asked.status) {
                HttpStatusCode.OK -> assertEquals(listOf(asked.body<GateResponse>().gate.id), gates, id)
                HttpStatusCode.Conflict -> {
                    assertTrue("session closed:" in asked.bodyAsText(), asked.bodyAsText())
                    assertEquals(emptyList(), gates, "A refused gate is in no session: $id")
                }
                else -> fail("$id: ${asked.status} ${asked.bodyAsText()}")
            }
        }
    }

    @Test
    fun aGateAskedAfterItsSessionEndedIsRefused() = raceTest(HeldRules()) { server ->
        server.start("s-after")
        server.end("s-after")
        val asked = server.ask("s-after", shaOf(1))
        assertEquals(HttpStatusCode.Conflict, asked.status, asked.bodyAsText())
        assertTrue("session closed:" in asked.bodyAsText(), asked.bodyAsText())
        assertEquals(emptyList(), server.assertReceiptCoversItsGates("s-after"))
    }

    private companion object {
        const val RACES = 20
    }
}
