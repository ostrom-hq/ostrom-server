// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.CreateServiceKeyRequest
import dev.ostrom.server.identity.presentation.CreateTokenRequest
import dev.ostrom.server.identity.presentation.CreatedTokenResponse
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.auth.STEP_UP_REQUIRED
import dev.ostrom.server.orgs.fakes.SettableClock
import dev.ostrom.server.orgs.presentation.CreateLinkRequest
import dev.ostrom.server.orgs.presentation.CreateOrgRequest
import dev.ostrom.server.orgs.presentation.InvitationLinkResponse
import dev.ostrom.server.orgs.presentation.InvitationTokenRequest
import dev.ostrom.server.orgs.presentation.InviteRequestBody
import dev.ostrom.server.orgs.presentation.TransferRequest
import dev.ostrom.server.orgs.presentation.UpdateMemberRequest
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The sensitive writes of the console need a sign-in of less than five minutes, as a yes and an organization's deletion
 * do (web audit n°2 M3): a session left open, or stolen, creates no token, replaces no secret, changes no role, removes
 * no member and transfers nothing. Each route: the old session gets 403 `step_up_required`, a fresh one passes. Real
 * HTTP, real PostgreSQL, the identity and orgs modules.
 */
class ConsoleStepUpPostgresTest {
    private val clock = SettableClock(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()))
    private val overrides = module {
        single<Clock> { clock }
        single<MailSender> {
            object : MailSender {
                override suspend fun send(mail: Mail) = Unit
            }
        }
    }
    private val modules = listOf(IdentityModule, OrgsModule)

    /** Ada owns Acme, Bob is a member, Ada has a personal token. */
    private class Team(val ada: StartedSession, val tokenId: String)

    private suspend fun TestServer.signIn(login: String): StartedSession {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@$login.test", true, login, login = login),
        )
        return koin.get<StartWebSessionUseCase>()(user.id)
    }

    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, "http://localhost:5173")
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.team(): Team {
        val ada = signIn("ada")
        client.post("/api/v1/orgs") {
            session(ada)
            json(CreateOrgRequest("Acme", null))
        }
        val link = client.post("/api/v1/orgs/acme/invitations/links") {
            session(ada)
            json(CreateLinkRequest())
        }.body<InvitationLinkResponse>()
        client.post("/api/v1/invitations/accept") {
            session(signIn("bob"))
            json(InvitationTokenRequest(link.url.substringAfter("#token=")))
        }
        val token = client.post("/api/v1/tokens") {
            session(ada)
            json(CreateTokenRequest("laptop", listOf("profile:read")))
        }
        assertEquals(HttpStatusCode.Created, token.status, token.bodyAsText())
        return Team(ada, token.body<CreatedTokenResponse>().token.id)
    }

    // Ada's session grows six minutes old: [write] is refused with step_up_required, the session still reads; signed in
    // again, the same write gets [expected].
    private fun stepUp(expected: HttpStatusCode, write: suspend TestServer.(StartedSession, Team) -> HttpResponse) =
        serverTest(modules, overrides) { server ->
            with(server) {
                val team = team()
                clock.now += 6.minutes
                val stale = write(team.ada, team)
                assertEquals(HttpStatusCode.Forbidden, stale.status)
                val error = Json.parseToJsonElement(stale.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals(STEP_UP_REQUIRED, error.getValue("type").jsonPrimitive.content)
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/orgs") { session(team.ada) }.status, "Reads pass")
                val fresh = write(signIn("ada"), team)
                assertEquals(expected, fresh.status, fresh.bodyAsText())
            }
        }

    @Test
    fun creatingATokenNeedsAFreshSignIn() = stepUp(HttpStatusCode.Created) { ada, _ ->
        client.post("/api/v1/tokens") {
            session(ada)
            json(CreateTokenRequest("ci", listOf("profile:read")))
        }
    }

    @Test
    fun replacingATokensSecretNeedsAFreshSignIn() = stepUp(HttpStatusCode.Created) { ada, team ->
        client.post("/api/v1/tokens/${team.tokenId}/rotate") { session(ada) }
    }

    @Test
    fun changingAMembersRoleNeedsAFreshSignIn() = stepUp(HttpStatusCode.OK) { ada, _ ->
        client.patch("/api/v1/orgs/acme/members/bob") {
            session(ada)
            json(UpdateMemberRequest("admin"))
        }
    }

    @Test
    fun removingAMemberNeedsAFreshSignIn() = stepUp(HttpStatusCode.NoContent) { ada, _ ->
        client.delete("/api/v1/orgs/acme/members/bob") { session(ada) }
    }

    @Test
    fun transferringTheOrganizationNeedsAFreshSignIn() = stepUp(HttpStatusCode.NoContent) { ada, _ ->
        client.post("/api/v1/orgs/acme/transfer") {
            session(ada)
            json(TransferRequest("bob"))
        }
    }

    // An invitation adds a member with any role: an old session would otherwise invite, as an admin, an account it
    // holds, and get the role it cannot give by a PATCH.
    @Test
    fun invitingByEMailNeedsAFreshSignIn() = stepUp(HttpStatusCode.OK) { ada, _ ->
        client.post("/api/v1/orgs/acme/members/invites") {
            session(ada)
            json(InviteRequestBody(listOf(mapOf("email" to "eve@eve.test", "role" to "admin"))))
        }
    }

    @Test
    fun anInvitationLinkNeedsAFreshSignIn() = stepUp(HttpStatusCode.Created) { ada, _ ->
        client.post("/api/v1/orgs/acme/invitations/links") {
            session(ada)
            json(CreateLinkRequest(role = "admin"))
        }
    }

    // A sign-in link starts a new session, signed in now: made from an old session, it would pass every step-up.
    @Test
    fun aSignInLinkNeedsAFreshSignIn() = stepUp(HttpStatusCode.Created) { ada, _ ->
        client.post("/api/v1/auth/links") { session(ada) }
    }

    @Test
    fun creatingAServiceKeyNeedsAFreshSignIn() = stepUp(HttpStatusCode.Created) { ada, _ ->
        client.post("/api/v1/service-keys") {
            session(ada)
            json(CreateServiceKeyRequest("ci", listOf("sessions:write")))
        }
    }
}
