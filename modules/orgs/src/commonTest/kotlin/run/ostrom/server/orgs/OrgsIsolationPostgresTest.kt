// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.orgs.presentation.CreateLinkRequest
import run.ostrom.server.orgs.presentation.CreateOrgRequest
import run.ostrom.server.orgs.presentation.DeleteOrgRequest
import run.ostrom.server.orgs.presentation.InvitationLinkResponse
import run.ostrom.server.orgs.presentation.InvitationsResponse
import run.ostrom.server.orgs.presentation.MembersResponse
import run.ostrom.server.orgs.presentation.OrgResponse
import run.ostrom.server.orgs.presentation.TransferRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The owner of another organization, signed in to the console, can neither delete nor transfer an organization they
 * are not a member of, nor revoke one of its invitations, even through their own organization's path: every attempt
 * answers 404 and the organization, its members and its invitations are unchanged.
 */
class OrgsIsolationPostgresTest {
    private val modules = listOf(IdentityModule, OrgsModule)
    private val overrides = module {
        single<MailSender> {
            object : MailSender {
                override suspend fun send(mail: Mail) = Unit
            }
        }
    }

    private suspend fun TestServer.signIn(login: String, email: String = "$login@example.com"): StartedSession {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, email, true, login, null, login = login),
        )
        return koin.get<StartWebSessionUseCase>()(user.id)
    }

    // A browser write: the session cookie, the CSRF header repeating the readable cookie, from the console's origin.
    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.createOrg(started: StartedSession, name: String) =
        client.post("/api/v1/orgs") {
            session(started)
            json(CreateOrgRequest(name, null))
        }

    @Test
    fun anotherOrganizationsOwnerNeitherDeletesNorTransfersNorRevokesAnInvitation() =
        serverTest(modules, overrides) { server ->
            val ada = server.signIn("ada")
            val acme = server.createOrg(ada, "Acme").body<OrgResponse>()
            val link = server.client.post("/api/v1/orgs/acme/invitations/links") {
                session(ada)
                json(CreateLinkRequest())
            }.body<InvitationLinkResponse>()
            // Another company: the free plan holds one team per domain, so not example.com again.
            val mallory = server.signIn("mallory", "mallory@evil.test")
            assertEquals(HttpStatusCode.Created, server.createOrg(mallory, "Evil").status)
            val invitationsBefore =
                server.client.get("/api/v1/orgs/acme/invitations") { session(ada) }.body<InvitationsResponse>()
            assertEquals(listOf(link.invitation.id), invitationsBefore.items.map { it.id })

            val attempts = listOf(
                server.client.delete("/api/v1/orgs/acme") {
                    session(mallory)
                    json(DeleteOrgRequest("acme"))
                },
                server.client.delete("/api/v1/orgs/${acme.id}") {
                    session(mallory)
                    json(DeleteOrgRequest("acme"))
                },
                server.client.post("/api/v1/orgs/acme/transfer") {
                    session(mallory)
                    json(TransferRequest("mallory"))
                },
                server.client.post("/api/v1/orgs/acme/transfer") {
                    session(mallory)
                    json(TransferRequest("ada"))
                },
                server.client.delete("/api/v1/orgs/acme/invitations/${link.invitation.id}") { session(mallory) },
                // Through mallory's own organization, where she is the owner: the invitation is not one of Evil's.
                server.client.delete("/api/v1/orgs/evil/invitations/${link.invitation.id}") { session(mallory) },
            )
            attempts.forEach {
                assertEquals(HttpStatusCode.NotFound, it.status, it.call.request.url.toString())
            }

            assertEquals(acme, server.client.get("/api/v1/orgs/acme") { session(ada) }.body<OrgResponse>())
            val members = server.client.get("/api/v1/orgs/acme/members") { session(ada) }.body<MembersResponse>()
            assertEquals(listOf("ada" to "owner"), members.items.map { it.login to it.role }, "Still ada's alone")
            assertEquals(
                invitationsBefore,
                server.client.get("/api/v1/orgs/acme/invitations") { session(ada) }.body<InvitationsResponse>(),
                "The invitation was not revoked",
            )
        }

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
    }
}
