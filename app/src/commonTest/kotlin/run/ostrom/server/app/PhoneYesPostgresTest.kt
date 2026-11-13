// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.identity.domain.APP_REDIRECT_URI
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MobileCode
import run.ostrom.server.identity.domain.MobileCodesRepository
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.infrastructure.Sha256SecretHasher
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.orgs.domain.Invitation
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * A yes from the phone app to a high-risk action (every action today: the server measures no risk, so no yes is low)
 * takes an unlock bound to its own request, less than 60 seconds old and used once (Q-APP-STEPUP-RISK, app security
 * audit A-S1): one biometric is one yes, never five minutes of them. Through every module of the server: dev-a's push
 * to main waits for rev-b, who answers from the phone.
 */
class PhoneYesPostgresTest {
    private suspend fun TestServer.signIn(login: String): Pair<User, StartedSession> {
        val email = "$login@example.com"
        val identity = VerifiedIdentity(IdentityProvider.GITHUB, login, email, true, login, login = login)
        val user = koin.get<ResolveUserUseCase>()(identity)
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

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

    /** dev-a owns the organization, rev-b is its admin; dev-a's push to main waits for rev-b. Returns the request. */
    private suspend fun TestServer.pushWaitingFor(devA: Pair<User, StartedSession>, revB: User): JsonObject {
        val now = Clock.System.now()
        val org = Org(Uuid.random(), "acme", "Acme", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, devA.first.id, OrgRole.OWNER, now)) {
            false
        }
        val invitation = Invitation(
            Uuid.random(),
            org.id,
            "rev-b@example.com",
            OrgRole.ADMIN,
            devA.first.id,
            now,
            now + 1.hours,
            1,
            0,
            null,
        )
        val invitations = koin.get<InvitationsRepository>()
        invitations.create(invitation, "hash-of-rev-b")
        invitations.accept(invitation, Member(Uuid.random(), org.id, revB.id, OrgRole.ADMIN, now), now)
        val token = client.post("/api/v1/tokens") {
            session(devA.second)
            json("""{"name": "requester", "scopes": ["gates:ask", "gates:read"], "expiresInDays": 1}""")
        }
        assertEquals(201, token.status.value, token.bodyAsText())
        val workflow = client.post("/api/v1/workflows") {
            session(devA.second)
            json(
                """
                {"id": "push-main", "name": "Push to main", "domain": "delivery", "status": "accepted",
                 "foundAt": "2026-11-02T17:00:00Z", "edges": [],
                 "nodes": [{"id": "push-main", "kind": "gate", "label": "Push to main", "approvers": ["rev-b"],
                            "actions": [{"class": "push-main", "label": "Push to the main branch", "patterns": [],
                                         "repos": ["acme/api"]}]}]}
                """.trimIndent(),
            )
        }
        assertEquals(true, workflow.status.value < 300, "workflow → ${workflow.status} ${workflow.bodyAsText()}")
        val asked = client.post("/api/v1/gates") {
            bearerAuth(token.json().getValue("secret").jsonPrimitive.content)
            json(
                """
                {"repo": "acme/api", "class": "push-main", "tool": "Bash", "command": "git push origin main",
                 "push": {"remote": "origin", "refs": [{"ref": "refs/heads/main", "sha": "$COMMIT"}]}}
                """.trimIndent(),
            )
        }
        assertEquals(200, asked.status.value, asked.bodyAsText())
        val inbox = client.get("/api/v1/approvals?mine=true&status=pending") { bearerAuth(phone(revB).token) }
        return inbox.json().getValue("items").jsonArray.single().jsonObject
    }

    /** The app's token and unlock key, for one sign-in on [user]'s phone. */
    private data class Phone(val token: String, val unlockKey: String)

    private val phones = mutableMapOf<Uuid, Phone>()

    private suspend fun TestServer.phone(user: User): Phone = phones[user.id] ?: run {
        val hasher = Sha256SecretHasher()
        val now = Clock.System.now()
        val code = "code-${user.id}"
        val challenge = hasher.pkceChallenge(VERIFIER)
        val stored = MobileCode(Uuid.random(), user.id, challenge, APP_REDIRECT_URI, now, now + 5.minutes)
        koin.get<MobileCodesRepository>().create(stored, hasher.hash(code))
        val exchanged = client.post("/api/v1/auth/mobile/token") {
            json("""{"code": "$code", "codeVerifier": "$VERIFIER", "redirectUri": "$APP_REDIRECT_URI"}""")
        }
        assertEquals(200, exchanged.status.value, exchanged.bodyAsText())
        val body = exchanged.json()
        Phone(body.getValue("token").jsonPrimitive.content, body.getValue("unlockKey").jsonPrimitive.content)
            .also { phones[user.id] = it }
    }

    /** The phone's biometric unlock, bound to [requestId] when given. */
    private suspend fun TestServer.unlock(phone: Phone, requestId: String?) {
        val bound = requestId?.let { ", \"requestId\": \"$it\"" }.orEmpty()
        val unlocked = client.post("/api/v1/auth/mobile/unlock") {
            bearerAuth(phone.token)
            json("""{"unlockKey": "${phone.unlockKey}"$bound}""")
        }
        assertEquals(200, unlocked.status.value, unlocked.bodyAsText())
    }

    private suspend fun TestServer.answer(
        phone: Phone,
        approval: JsonObject,
        answer: String,
        shown: String = approval.getValue("shownDigest").jsonPrimitive.content,
    ): HttpResponse = client.post("/api/v1/approvals/${approval.id()}/answer") {
        bearerAuth(phone.token)
        json("""{"answer": "$answer", "shownDigest": "$shown"}""")
    }

    private fun JsonObject.id() = getValue("id").jsonPrimitive.content

    /** The refusal that asks the phone to unlock again for this very request. */
    private suspend fun HttpResponse.assertNeedsABoundUnlock(why: String) {
        assertEquals(403, status.value, "$why: ${bodyAsText()}")
        val error = json().getValue("error").jsonObject
        assertEquals("step_up_required", error.getValue("type").jsonPrimitive.content, why)
        assertEquals(true, error["requestBound"]?.jsonPrimitive?.boolean, "$why: ${bodyAsText()}")
    }

    /** Ages every unlock of the app's tokens, as if the yes came [by] later. */
    private suspend fun TestServer.age(by: String) {
        driver.execute(
            "UPDATE identity_api_tokens SET authenticated_at = authenticated_at - INTERVAL '$by' " +
                "WHERE kind = 'MOBILE'",
        ).getOrThrow()
    }

    @Test
    fun aYesFromAnUnlockTwoMinutesOldIsRefusedThoughANoIsTaken() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val revB = server.signIn("rev-b").first
        val approval = server.pushWaitingFor(devA, revB)
        val phone = server.phone(revB)

        server.unlock(phone, approval.id())
        server.age("2 minutes")
        server.answer(phone, approval, "approve").assertNeedsABoundUnlock("an unlock two minutes old")

        // A no needs no fresh proof: the five minutes of the unlock still hold for it.
        assertEquals(200, server.answer(phone, approval, "reject").status.value)
    }

    @Test
    fun aYesFromAnUnlockForAnotherRequestIsRefused() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val revB = server.signIn("rev-b").first
        val approval = server.pushWaitingFor(devA, revB)
        val phone = server.phone(revB)

        server.unlock(phone, Uuid.random().toString())
        server.answer(phone, approval, "approve").assertNeedsABoundUnlock("an unlock for another request")
        server.unlock(phone, null)
        server.answer(phone, approval, "approve").assertNeedsABoundUnlock("an unlock bound to no request")

        server.unlock(phone, approval.id())
        assertEquals(200, server.answer(phone, approval, "approve").status.value, "an unlock for this request")
    }

    @Test
    fun anUnlockServesOneYesOnly() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val revB = server.signIn("rev-b").first
        val approval = server.pushWaitingFor(devA, revB)
        val phone = server.phone(revB)

        server.unlock(phone, approval.id())
        // The first yes sent with the unlock uses it, whatever its outcome: here, on what rev-b was not shown.
        assertEquals(409, server.answer(phone, approval, "approve", shown = "sha256:other").status.value)
        server.answer(phone, approval, "approve").assertNeedsABoundUnlock("an unlock already used")
    }

    @Test
    fun aFreshUnlockForTheRequestSaysYesOnce() = serverTest(serverModules()) { server ->
        val devA = server.signIn("dev-a")
        val revB = server.signIn("rev-b").first
        val approval = server.pushWaitingFor(devA, revB)
        val phone = server.phone(revB)

        server.unlock(phone, approval.id())
        val answered = server.answer(phone, approval, "approve")
        assertEquals(200, answered.status.value, answered.bodyAsText())
        val decided = answered.json().getValue("approval").jsonObject
        assertEquals("approved", decided.getValue("status").jsonPrimitive.content)
    }

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val VERIFIER = "app-verifier-" + "x".repeat(50)
    }
}
