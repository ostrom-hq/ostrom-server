// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.MAX_NOTE
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.presentation.AnswerApprovalRequest
import run.ostrom.server.gates.presentation.ApprovalAnswer
import run.ostrom.server.gates.presentation.ApprovalQueue
import run.ostrom.server.gates.presentation.ApprovalViewResponse
import run.ostrom.server.gates.presentation.GateAskRequest
import run.ostrom.server.gates.presentation.GatePush
import run.ostrom.server.gates.presentation.GatePushRef
import run.ostrom.server.gates.presentation.GateResponse
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import run.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import run.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The note an approver sends with their answer, against a real PostgreSQL, real HTTP and the real receipts: stored with
 * the decision, read by the session waiting on the gate, signed on the receipt, returned by the view, and never read
 * by another organization.
 */
class ApprovalNotePostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val session = "claude-5e5"
    private val note = "Rebase on main first, then ask again."

    // Tokens are `<kind>:<org letter>:<id>`: `user` a browser session, `agent` the agent token of launch `<id>`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                        authenticatedAt = Clock.System.now(),
                    )
                    else -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                }
            }
        }
        single<PhoneUnlockProofs> { PhoneUnlockProofs { _, _, _ -> true } }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor {
                    val (kind, org, id) = principal.subjectId.split(":")
                    val orgId = if (org == "a") orgA else orgB
                    return if (kind == "agent") {
                        GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                    } else {
                        GateActor(id, orgId, ActorKind.USER, personId = id)
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
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b"), enforced = true)
            }
        }
    }

    private fun noteTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(InstanceModule, ReceiptsModule, GatesModule), overrides) { server -> block(server) }

    private suspend fun TestServer.read(token: String, path: String): HttpResponse =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun TestServer.push(org: String): GateResponse = client.post("/api/v1/gates") {
        header(HttpHeaders.Authorization, "Bearer agent:$org:$session")
        contentType(ContentType.Application.Json)
        setBody(
            GateAskRequest(
                sessionId = session,
                repo = "ostrom-hq/gate-demo",
                cls = "push-main",
                command = "git push origin main",
                push = GatePush(
                    "git@github.com:ostrom-hq/gate-demo.git",
                    listOf(GatePushRef("refs/heads/main", "0123456789abcdef0123456789abcdef01234567")),
                ),
                diff = "+ok",
            ),
        )
    }.body()

    private suspend fun TestServer.answer(token: String, id: String, body: AnswerApprovalRequest): HttpResponse =
        client.post("/api/v1/approvals/$id/answer") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.pendingOf(token: String) =
        read(token, "/api/v1/approvals/queue").body<ApprovalQueue>().pending.single().approval

    @Test
    fun theNoteIsStoredSentToTheSessionSignedOnTheReceiptAndShownByTheView() = noteTest { server ->
        val opened = server.push("a")
        val approval = server.pendingOf("user:a:rev-b")
        val tooLong = server.answer(
            "user:a:rev-b",
            approval.id,
            AnswerApprovalRequest(ApprovalAnswer.REJECT, approval.shownDigest, note = "x".repeat(MAX_NOTE + 1)),
        )
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
        assertTrue("\"note_too_long\"" in tooLong.bodyAsText(), tooLong.bodyAsText())

        val answered = server.answer(
            "user:a:rev-b",
            approval.id,
            AnswerApprovalRequest(ApprovalAnswer.REJECT, approval.shownDigest, note = note),
        )
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
        val view = answered.body<ApprovalViewResponse>()
        assertEquals(note, view.note, "the answer's view returns it")
        assertEquals(note, server.read("user:a:rev-b", "/api/v1/approvals/${approval.id}/view")
            .body<ApprovalViewResponse>().note, "the view returns it once stored")
        val last = Json.parseToJsonElement(answered.bodyAsText()).jsonObject.keys.last()
        assertEquals("note", last, "the view's note is its last field")

        // The session waiting on the gate reads the no and the note together, with the agent's own token.
        val waited = server.read("agent:a:$session", "/api/v1/gates/${opened.gate.id}?wait=1").body<GateResponse>()
        assertEquals("rejected", waited.gate.status)
        assertEquals(note, waited.gate.note)

        // Signed on the receipt of the decision, which still verifies.
        val receipt = view.receipt as JsonObject
        val decision = receipt.getValue("decisions").jsonArray.single().jsonObject
        assertEquals(note, decision.getValue("note").jsonPrimitive.content)
        assertEquals("rejected", decision.getValue("result").jsonPrimitive.content)
        val stored = server.koin.get<GetReceiptUseCase>()(orgA, waited.gate.receiptId!!)
        assertEquals(receipt, stored)
        assertTrue(server.koin.get<VerifyReceiptUseCase>().verify(orgA, stored).ok, "the signature covers the note")

        // Another organization reads neither the gate, nor the view, nor the receipt.
        assertEquals(HttpStatusCode.NotFound, server.read("agent:b:$session", "/api/v1/gates/${opened.gate.id}").status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.read("user:b:rev-b", "/api/v1/approvals/${approval.id}/view").status,
        )
        assertFailsWith<ReceiptNotFoundException> {
            server.koin.get<GetReceiptUseCase>()(orgB, waited.gate.receiptId!!)
        }
    }

    @Test
    fun theFirstNameOfTheNoteIsReadAndAnAnswerWithoutOneWritesNone() = noteTest { server ->
        server.push("a")
        val approval = server.pendingOf("user:a:rev-b")
        val answered = server.answer(
            "user:a:rev-b",
            approval.id,
            AnswerApprovalRequest(ApprovalAnswer.APPROVE, approval.shownDigest, comment = "Ship it.", note = " "),
        ).body<ApprovalViewResponse>()
        assertEquals("Ship it.", answered.note, "`comment`, the field's first name, is read past an empty `note`")

        server.push("b")
        val other = server.pendingOf("user:b:rev-b")
        val plain = server.answer(
            "user:b:rev-b",
            other.id,
            AnswerApprovalRequest(ApprovalAnswer.APPROVE, other.shownDigest),
        )
        val body = Json.parseToJsonElement(plain.bodyAsText()).jsonObject
        assertFalse("note" in body, "no note, no field")
        val decision = body.getValue("receipt").jsonObject.getValue("decisions").jsonArray.single().jsonObject
        assertFalse("note" in decision, "a decision without a note is signed as before")
        assertNull(plain.body<ApprovalViewResponse>().note)
    }
}
