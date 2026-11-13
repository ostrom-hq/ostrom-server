// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.presentation.AnswerApprovalRequest
import run.ostrom.server.gates.presentation.Approval
import run.ostrom.server.gates.presentation.ApprovalAnswer
import run.ostrom.server.gates.presentation.ApprovalCallerView
import run.ostrom.server.gates.presentation.ApprovalQueue
import run.ostrom.server.gates.presentation.ApprovalViewResponse
import run.ostrom.server.gates.presentation.ApprovalViewer
import run.ostrom.server.gates.presentation.ApprovalWhat
import run.ostrom.server.gates.presentation.ApprovalWho
import run.ostrom.server.gates.presentation.GateAskRequest
import run.ostrom.server.gates.presentation.GatePush
import run.ostrom.server.gates.presentation.GatePushRef
import run.ostrom.server.gates.presentation.GateResponse
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * `GET /approvals/queue` and `GET /approvals/{id}/view` against a real PostgreSQL and real HTTP: who may read them,
 * what the reader may do, and the isolation of organizations.
 */
class ApprovalQueuePostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val session = "claude-5e5"

    // Tokens are `<kind>:<org letter>:<person>`. `user` is the console's browser session, `app` the unlocked app, `cli`
    // a personal token of the CLI (every scope, any program can present it), `reader` a read-only browser session.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> person(token, PrincipalChannel.BROWSER, GateScopes.ALL_USER)
                    "app" -> person(token, PrincipalChannel.MOBILE_APP, GateScopes.ANSWER)
                    "cli" -> person(token, PrincipalChannel.API_TOKEN, GateScopes.ALL_USER, GateScopes.ANSWER)
                    "reader" -> person(token, PrincipalChannel.BROWSER, GateScopes.READ)
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    "device" -> AuthenticatedPrincipal(token, PrincipalKind.DEVICE, setOf(GateScopes.ANSWER))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet())
                }
            }
        }
        // The app's unlock, bound to the request it answers (Q-APP-STEPUP-RISK): app's PhoneYesPostgresTest plays it.
        single<PhoneUnlockProofs> { PhoneUnlockProofs { _, _, _ -> true } }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") orgA else orgB
                    return when (kind) {
                        "agent" -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                        "device" -> GateActor(id, orgId, ActorKind.DEVICE, personId = id)
                        else -> GateActor(id, orgId, ActorKind.USER, personId = id)
                    }
                }
            }
        }
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) = Unit
            }
        }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b", "rev-c"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    private fun person(token: String, channel: PrincipalChannel, vararg scopes: String) = AuthenticatedPrincipal(
        token,
        PrincipalKind.USER,
        scopes.toSet(),
        channel = channel,
        authenticatedAt = Clock.System.now(),
    )

    private fun queueTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule), overrides) { server -> block(server) }

    private suspend fun TestServer.read(token: String?, path: String): HttpResponse =
        client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun TestServer.push(commit: String = "0123456789abcdef0123456789abcdef01234567"): GateResponse =
        client.post("/api/v1/gates") {
            header(HttpHeaders.Authorization, "Bearer agent:a:$session")
            contentType(ContentType.Application.Json)
            setBody(
                GateAskRequest(
                    sessionId = session,
                    repo = "ostrom-hq/gate-demo",
                    cls = "push-main",
                    command = "git push origin main",
                    push = GatePush(
                        "git@github.com:ostrom-hq/gate-demo.git",
                        listOf(GatePushRef("refs/heads/main", commit)),
                    ),
                    diff = "+ok",
                ),
            )
        }.body()

    private suspend fun TestServer.queue(token: String): ApprovalQueue =
        read(token, "/api/v1/approvals/queue").body()

    private suspend fun TestServer.view(token: String, id: String): ApprovalViewResponse =
        read(token, "/api/v1/approvals/$id/view").body()

    @Test
    fun theQueueShowsWhatWaitsForTheCallerThenWhatTheyAnswered() = queueTest { server ->
        val opened = server.push()
        val queue = server.queue("user:a:rev-b")
        assertEquals("rev-b", queue.operator)
        assertEquals(ApprovalViewer("rev-b", "console"), queue.you)
        assertTrue(queue.recent.isEmpty())
        val mine = queue.pending.single()
        assertEquals(opened.gate.id, mine.approval.group)
        assertEquals("rev-b", mine.approval.requestedFrom)
        assertEquals(ApprovalWho(dev = "lyes", session = session), mine.who)
        assertEquals(ApprovalWhat("Merge into main", "git push origin main", "ostrom-hq/gate-demo"), mine.what)
        assertEquals("Merge into main", mine.gate?.label)
        assertEquals(listOf("hook"), mine.askedVia)
        assertEquals(ApprovalCallerView("rev-b", self = false, canApprove = true), mine.you)
        val served = server.read("user:a:rev-b", "/api/v1/approvals/${mine.approval.id}").body<Approval>()
        assertEquals(served, mine.approval, "the approval is the one GET /approvals/{id} serves")
        assertEquals(mine, server.view("user:a:rev-b", mine.approval.id), "the view is the queue's item")
        val raw = Json.parseToJsonElement(server.read("user:a:rev-b", "/api/v1/approvals/queue").bodyAsText())
        val you = raw.jsonObject.getValue("pending").jsonArray.single().jsonObject.getValue("you").jsonObject
        assertEquals(setOf("login", "self", "canApprove"), you.keys, "no reason nor selfApproval when a yes passes")

        val asker = server.queue("user:a:lyes").pending.single().you
        assertEquals(
            ApprovalCallerView(
                "lyes",
                self = true,
                canApprove = false,
                reason = "You asked for this action and cannot approve it: rev-b or rev-c must say yes.",
            ),
            asker,
        )
        val askersId = server.queue("user:a:lyes").pending.single().approval.id
        assertEquals(asker, server.view("user:a:lyes", askersId).you)

        val answered = server.client.post("/api/v1/approvals/${mine.approval.id}/answer") {
            header(HttpHeaders.Authorization, "Bearer app:a:rev-b")
            contentType(ContentType.Application.Json)
            setBody(AnswerApprovalRequest(ApprovalAnswer.APPROVE, mine.approval.shownDigest))
        }
        assertEquals(HttpStatusCode.OK, answered.status, "the yes the queue said would pass")
        val after = server.queue("user:a:rev-b")
        assertTrue(after.pending.isEmpty())
        val recent = after.recent.single()
        assertEquals("approved", recent.approval.status)
        assertEquals(
            ApprovalCallerView("rev-b", self = false, canApprove = false, reason = "This gate was already answered."),
            recent.you,
        )
        assertEquals(listOf("expired"), server.queue("user:a:rev-c").recent.map { it.approval.status })
    }

    @Test
    fun theViaAndTheYesFollowWhereTheCredentialIsHeld() = queueTest { server ->
        server.push()
        val id = server.queue("user:a:rev-b").pending.single().approval.id
        assertEquals("app", server.queue("app:a:rev-b").you.via)
        assertEquals("cli", server.queue("cli:a:rev-b").you.via)
        assertEquals("console", server.queue("reader:a:rev-b").you.via)
        assertEquals(true, server.view("app:a:rev-b", id).you.canApprove, "the unlocked app says yes")
        assertEquals(
            ApprovalCallerView("rev-b", false, false, "A yes is given in the browser or in the app."),
            server.view("cli:a:rev-b", id).you,
            "a personal token reads, it never says yes (S2-31)",
        )
        assertEquals(
            ApprovalCallerView("rev-b", false, false, "This credential reads approvals but does not answer them."),
            server.view("reader:a:rev-b", id).you,
        )
        assertEquals(
            ApprovalCallerView("rev-c", false, false, "Only the approver this request was sent to can answer it."),
            server.view("user:a:rev-c", id).you,
            "another person of the organization reads it, without a yes",
        )
    }

    @Test
    fun anotherOrganizationSeesNeitherTheQueueNorTheView() = queueTest { server ->
        server.push()
        val id = server.queue("user:a:rev-b").pending.single().approval.id
        val elsewhere = server.queue("user:b:rev-b")
        assertTrue(elsewhere.pending.isEmpty() && elsewhere.recent.isEmpty(), "org B sees no request of org A")
        assertEquals(HttpStatusCode.NotFound, server.read("user:b:rev-b", "/api/v1/approvals/$id/view").status)
        val unknown = server.read("user:a:rev-b", "/api/v1/approvals/${Uuid.random()}/view")
        assertEquals(HttpStatusCode.NotFound, unknown.status, "an unknown id answers as another org's")
        val malformed = server.read("user:a:rev-b", "/api/v1/approvals/not-a-uuid/view")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun withoutATokenTheQueueAndTheViewAnswer401AndOnlyAPersonReadsThem() = queueTest { server ->
        server.push()
        val id = server.queue("user:a:rev-b").pending.single().approval.id
        for (path in listOf("/api/v1/approvals/queue", "/api/v1/approvals/$id/view")) {
            assertEquals(HttpStatusCode.Unauthorized, server.read(null, path).status, path)
            assertEquals(HttpStatusCode.Forbidden, server.read("agent:a:$session", path).status, "$path: an agent")
            assertEquals(HttpStatusCode.Forbidden, server.read("device:a:rev-b", path).status, "$path: a device")
            assertEquals(HttpStatusCode.Forbidden, server.read("nobody", path).status, "$path: no scope")
            assertEquals(HttpStatusCode.NotFound, server.read("user:a", path).status, "$path: no organization")
        }
    }
}
