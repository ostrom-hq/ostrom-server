// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.compiler.domain.services.CompilerSources
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.domain.Invitation
import dev.ostrom.server.orgs.domain.InvitationsRepository
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlin.uuid.Uuid

/**
 * The gate journey of the load and end-to-end benches (`ostrom-e2e/scripts/load-setup.ts`, E2E-AUTH-007), through
 * every module of the server: an owner (dev-a) and an admin (rev-b) of one organization, an accepted workflow posted
 * by dev-a's browser session whose gate node names rev-b as the approver of pushes to main of `acme/api`, then a push
 * to main asked with dev-a's personal token. The gate waits for rev-b, and rev-b finds the request in their inbox.
 */
class GateJourneyPostgresTest {
    // The organization the last setUp created.
    private var orgId: Uuid = Uuid.NIL

    private suspend fun TestServer.signIn(login: String): Pair<User, StartedSession> {
        val identity = VerifiedIdentity(
            IdentityProvider.GITHUB,
            login,
            "$login@example.com",
            true,
            login,
            login = login,
        )
        val user = koin.get<ResolveUserUseCase>()(identity)
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    // A browser write: the session cookie, the CSRF header repeating its cookie, from the console's origin.
    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** dev-a owns the organization, rev-b and rev-c join it as admins. */
    private suspend fun TestServer.team(devA: User, approvers: List<User>): Uuid {
        val now = Clock.System.now()
        val org = Org(Uuid.random(), "acme", "Acme", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, devA.id, OrgRole.OWNER, now)) { false }
        val invitations = koin.get<InvitationsRepository>()
        for (approver in approvers) {
            val email = "${approver.login}@example.com"
            val invitation =
                Invitation(Uuid.random(), org.id, email, OrgRole.ADMIN, devA.id, now, now + 1.hours, 1, 0, null)
            invitations.create(invitation, "hash-of-${approver.id}")
            invitations.accept(invitation, Member(Uuid.random(), org.id, approver.id, OrgRole.ADMIN, now), now)
        }
        return org.id
    }

    private fun push(cls: String, sha: String = COMMIT) = """
        {"repo": "acme/api", "class": "$cls", "tool": "Bash", "command": "git push origin main",
         "push": {"remote": "origin", "refs": [{"ref": "refs/heads/main", "sha": "$sha"}]}}
    """.trimIndent()

    /** The benches' setup: the team, dev-a's personal token, the accepted workflow. Returns the token's secret. */
    private suspend fun TestServer.setUp(devA: Pair<User, StartedSession>, approvers: List<User>): String {
        orgId = team(devA.first, approvers)
        val token = client.post("/api/v1/tokens") {
            session(devA.second)
            json("""{"name": "k6 requester", "scopes": ["gates:ask", "gates:read"], "expiresInDays": 1}""")
        }
        assertEquals(201, token.status.value, token.bodyAsText())
        val names = approvers.joinToString(", ") { "\"${it.login}\"" }
        val workflow = client.post("/api/v1/workflows") {
            session(devA.second)
            json(
                """
                {"id": "k6-push-main", "name": "k6 push to main", "domain": "delivery", "status": "accepted",
                 "foundAt": "2026-11-02T17:00:00Z", "edges": [],
                 "nodes": [{"id": "push-main", "kind": "gate", "label": "Push to main", "approvers": [$names],
                            "actions": [{"class": "push-main", "label": "Push to the main branch", "patterns": [],
                                         "repos": ["acme/api"]}]}]}
                """.trimIndent(),
            )
        }
        assertTrue(workflow.status.value < 300, "workflow → ${workflow.status} ${workflow.bodyAsText()}")
        return token.json().getValue("secret").jsonPrimitive.content
    }

    private suspend fun TestServer.ask(secret: String, cls: String, sha: String = COMMIT): JsonObject {
        val asked = client.post("/api/v1/gates") {
            bearerAuth(secret)
            json(push(cls, sha))
        }
        assertEquals(200, asked.status.value, asked.bodyAsText())
        return asked.json().getValue("gate").jsonObject
    }

    /** The approval waiting in [approver]'s inbox. */
    private suspend fun TestServer.waiting(approver: StartedSession): JsonObject {
        val inbox = client.get("/api/v1/approvals?mine=true&status=pending") { session(approver) }
        return inbox.json().getValue("items").jsonArray.single().jsonObject
    }

    /** [approver]'s answer to [approval]. */
    private suspend fun TestServer.answer(
        approver: StartedSession,
        approval: JsonObject,
        answer: String,
    ): HttpResponse {
        val id = approval.getValue("id").jsonPrimitive.content
        val shown = approval.getValue("shownDigest").jsonPrimitive.content
        return client.post("/api/v1/approvals/$id/answer") {
            session(approver)
            json("""{"answer": "$answer", "shownDigest": "$shown"}""")
        }
    }

    /**
     * The receipt of an answer: signed by the instance, verified as `ostrom verify` does, with its result, naming the
     * pushed commit in clear. Returns it.
     */
    private suspend fun TestServer.assertSignedReceipt(answered: HttpResponse, result: String): JsonObject {
        assertEquals(200, answered.status.value, answered.bodyAsText())
        val receipt = assertNotNull(answered.json()["receipt"]?.jsonObject, answered.bodyAsText())
        assertTrue(receipt["signature"]?.jsonPrimitive?.content.orEmpty().isNotEmpty(), answered.bodyAsText())
        val verification = koin.get<VerifyReceiptUseCase>().verify(receipt)
        assertTrue(verification.ok, "verification: $verification")
        val decision = receipt.getValue("decisions").jsonArray.single().jsonObject
        assertEquals(result, decision.getValue("result").jsonPrimitive.content)
        assertEquals(COMMIT, receipt.getValue("subject").jsonObject.getValue("id").jsonPrimitive.content)
        return receipt
    }

    @Test
    fun aPushToMainWaitsForTheApproverTheAcceptedWorkflowNames() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val (revB, revBSession) = server.signIn("rev-b")
        val secret = server.setUp(devA, listOf(revB))

        // The benches ask the coarse class `git.push`; the CLI asks the catalogue's `push-main`. Both are this push.
        for (cls in listOf("git.push", "push-main")) {
            assertEquals("pending", server.ask(secret, cls).getValue("status").jsonPrimitive.content, cls)
        }

        val inbox = server.client.get("/api/v1/approvals?mine=true&status=pending") { session(revBSession) }
        assertEquals(200, inbox.status.value, inbox.bodyAsText())
        val items = inbox.json().getValue("items").jsonArray
        assertEquals(1, items.size, "rev-b's inbox: ${inbox.bodyAsText()}")

        // A no is a decision too: its answer carries its signed receipt, and the server keeps it (E2E-GATE-001).
        val answered = server.answer(revBSession, items.single().jsonObject, "reject")
        val receipt = server.assertSignedReceipt(answered, "rejected")
        val id = receipt.getValue("id").jsonPrimitive.content
        val kept = server.client.get("/api/v1/receipts/$id") { session(devA.second) }
        assertEquals(200, kept.status.value, kept.bodyAsText())
        assertTrue(COMMIT in kept.bodyAsText() && "rev-b" in kept.bodyAsText(), kept.bodyAsText())
    }

    @Test
    fun onlyTheWinnerOfTwoSimultaneousYesHasASignedReceipt() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val (revB, revBSession) = server.signIn("rev-b")
        val (revC, revCSession) = server.signIn("rev-c")
        val secret = server.setUp(devA, listOf(revB, revC))
        assertEquals("pending", server.ask(secret, "push-main").getValue("status").jsonPrimitive.content)

        // Both read their approval first, then say yes at once.
        val approvals = listOf(revBSession, revCSession).map { it to server.waiting(it) }
        val answers = coroutineScope {
            approvals.map { (approver, approval) -> async { server.answer(approver, approval, "approve") } }.awaitAll()
        }.sortedBy { it.status.value }
        assertEquals(listOf(200, 409), answers.map { it.status.value })
        server.assertSignedReceipt(answers.first(), "approved")
        val refused = answers.last()
        assertNull(refused.json()["receipt"], "a refused yes has no receipt: ${refused.bodyAsText()}")
    }

    // The bench's race (`load/race.js`): one approver says yes 100 times at once. One yes wins; the others are 409s
    // that must stay cheap. The race, 100 reads, then 100 late yes: each burst prints what it costs (`PEAKS`).
    @Test
    fun aHundredSimultaneousYesGiveOneWinnerAndCheapRefusals() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val (revB, revBSession) = server.signIn("rev-b")
        val secret = server.setUp(devA, listOf(revB))
        server.ask(secret, "push-main")
        val approval = server.waiting(revBSession)
        val id = approval.getValue("id").jsonPrimitive.content

        val race = peaks("race") { List(RACE) { async { server.answer(revBSession, approval, "approve") } }.awaitAll() }
        assertEquals(listOf(200) + List(RACE - 1) { 409 }, race.map { it.status.value }.sorted())
        val reads = peaks("reads") {
            List(RACE) { async { server.client.get("/api/v1/approvals/$id/view") { session(revBSession) } } }.awaitAll()
        }
        assertEquals(List(RACE) { 200 }, reads.map { it.status.value })
        val late = peaks("late") { List(RACE) { async { server.answer(revBSession, approval, "approve") } }.awaitAll() }
        assertEquals(List(RACE) { 409 }, late.map { it.status.value })
    }

    // What `POST /gates` spends its time on, step by step (`TIMING`): the bench's gate p95 went from 10 to 19 ms.
    @Test
    fun whatAnAskSpendsItsTimeOn() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val (revB, _) = server.signIn("rev-b")
        val secret = server.setUp(devA, listOf(revB))
        val nodes = server.koin.get<GateNodeResolver>()
        val sources = server.koin.get<CompilerSources>()
        timing("health") { assertEquals(200, server.client.get("/api/v1/health").status.value) }
        timing("resolve") { assertNotNull(nodes.resolve(orgId, "acme/api", "push-main")) }
        timing("ruleGates") { sources.ruleGates(orgId) }
        timing("settings") { sources.settings(orgId) }
        var n = 0
        timing("ask") {
            val sha = (n++).toString(HEX).padStart(SHA_LENGTH, '0')
            assertEquals("pending", server.ask(secret, "push-main", sha).getValue("status").jsonPrimitive.content)
        }
    }

    private suspend fun timing(stage: String, step: suspend () -> Unit) {
        repeat(WARM_UP) { step() }
        val took = List(TIMED) { TimeSource.Monotonic.measureTime { step() } }.sorted()
        val mean = took.fold(Duration.ZERO) { sum, it -> sum + it } / TIMED
        println("TIMING stage=$stage mean=$mean p50=${took[TIMED / 2]} p95=${took[TIMED * P95 / PERCENT]}")
    }

    private companion object {
        const val WARM_UP = 20
        const val TIMED = 200
        const val P95 = 95
        const val PERCENT = 100
        const val HEX = 16
        const val SHA_LENGTH = 40
        const val RACE = 100
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
