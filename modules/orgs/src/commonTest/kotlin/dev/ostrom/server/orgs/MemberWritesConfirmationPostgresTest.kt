// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.auth.GovernanceConfirmationPending
import dev.ostrom.server.kernel.auth.GovernanceConfirmations
import dev.ostrom.server.kernel.auth.GovernanceConfirmer
import dev.ostrom.server.kernel.auth.GovernanceRequest
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.domain.InviteRequest
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.orgs.presentation.CreateLinkRequest
import dev.ostrom.server.orgs.presentation.CreateOrgRequest
import dev.ostrom.server.orgs.presentation.InvitationLinkResponse
import dev.ostrom.server.orgs.presentation.InvitationTokenRequest
import dev.ostrom.server.orgs.presentation.InvitationsResponse
import dev.ostrom.server.orgs.presentation.InviteRequestBody
import dev.ostrom.server.orgs.presentation.MembersResponse
import dev.ostrom.server.orgs.presentation.OrgResponse
import dev.ostrom.server.orgs.presentation.OrgsGovernanceChange
import dev.ostrom.server.orgs.presentation.UpdateMemberRequest
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * The four writes on the members made with a person's API token (`orgs:write`) are held for a confirmation on the
 * console, as the rules' writes are (contract audit 2, F1): a token on a developer's machine, which an agent can
 * present, never changes a role, removes a member or invites anyone by itself. Confirmed, the write is made as the
 * confirmer's. Real HTTP, real PostgreSQL, the identity and orgs modules, a fake confirmation service that records what
 * it is asked to hold.
 */
class MemberWritesConfirmationPostgresTest {
    private val held = mutableListOf<GovernanceRequest>()
    private val overrides = module {
        single<MailSender> {
            object : MailSender {
                override suspend fun send(mail: Mail) = Unit
            }
        }
        single<GovernanceConfirmations> {
            object : GovernanceConfirmations {
                override suspend fun open(request: GovernanceRequest): GovernanceConfirmationPending {
                    held += request
                    return GovernanceConfirmationPending("gcf_${held.size}", "http://console/confirm", "later")
                }
            }
        }
    }
    private val modules = listOf(IdentityModule, OrgsModule)

    /** A person signed in to the console. */
    private class Person(val id: Uuid, val session: StartedSession)

    /** Ada owns Acme, Bob is a member of it, Ada has a personal token with `orgs:write`. */
    private class Team(val ada: Person, val orgId: Uuid, val token: String)

    private suspend fun TestServer.signIn(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@$login.test", true, login, login = login),
        )
        return Person(user.id, koin.get<StartWebSessionUseCase>()(user.id))
    }

    private fun HttpRequestBuilder.session(person: Person) {
        val started = person.session
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
        val org = client.post("/api/v1/orgs") {
            session(ada)
            json(CreateOrgRequest("Acme", null))
        }.body<OrgResponse>()
        val link = client.post("/api/v1/orgs/acme/invitations/links") {
            session(ada)
            json(CreateLinkRequest())
        }.body<InvitationLinkResponse>()
        client.post("/api/v1/invitations/accept") {
            session(signIn("bob"))
            json(InvitationTokenRequest(link.url.substringAfter("#token=")))
        }
        val token = koin.get<CreatePersonalTokenUseCase>()(ada.id, "laptop", listOf("orgs:read", "orgs:write"), null)
        return Team(ada, Uuid.parse(org.id), token.secret)
    }

    private suspend fun TestServer.roles(team: Team): Map<String, String> =
        client.get("/api/v1/orgs/acme/members") { session(team.ada) }.body<MembersResponse>()
            .items.associate { it.login to it.role }

    private suspend fun TestServer.liveInvitations(team: Team): Int =
        client.get("/api/v1/orgs/acme/invitations") { session(team.ada) }.body<InvitationsResponse>().items.size


    private fun confirmer(team: Team) = GovernanceConfirmer(
        browser(team.ada),
        OrgPrincipal(team.orgId, team.ada.id, OrgRole.OWNER),
    )

    private fun browser(person: Person) =
        AuthenticatedPrincipal(
            person.id.toString(),
            PrincipalKind.USER,
            setOf("orgs:write"),
            channel = PrincipalChannel.BROWSER,
        )

    @Test
    fun aTokensFourMemberWritesAreHeldForTheConsoleAndNothingIsWritten() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val invitationsBefore = liveInvitations(team)
            val role = client.patch("/api/v1/orgs/acme/members/bob") {
                bearerAuth(team.token)
                json(UpdateMemberRequest("admin"))
            }
            assertEquals(HttpStatusCode.Accepted, role.status, role.bodyAsText())
            val removal = client.delete("/api/v1/orgs/acme/members/bob") { bearerAuth(team.token) }
            assertEquals(HttpStatusCode.Accepted, removal.status, removal.bodyAsText())
            val invite = client.post("/api/v1/orgs/acme/members/invites") {
                bearerAuth(team.token)
                json(InviteRequestBody(listOf(mapOf("email" to "eve@eve.test", "role" to "admin"))))
            }
            assertEquals(HttpStatusCode.Accepted, invite.status, invite.bodyAsText())
            val link = client.post("/api/v1/orgs/acme/invitations/links") {
                bearerAuth(team.token)
                json(CreateLinkRequest(role = "admin", maxUses = 3))
            }
            assertEquals(HttpStatusCode.Accepted, link.status, link.bodyAsText())
            assertEquals(
                listOf(
                    "members.role PATCH /api/v1/orgs/acme/members/bob orgs:write",
                    "members.remove DELETE /api/v1/orgs/acme/members/bob orgs:write",
                    "members.invite POST /api/v1/orgs/acme/members/invites orgs:write",
                    "members.link POST /api/v1/orgs/acme/invitations/links orgs:write",
                ),
                held.map { "${it.write.kind} ${it.method} ${it.path} ${it.scope}" },
            )
            assertTrue(held.all { it.orgId == team.orgId })
            assertEquals(mapOf("ada" to "owner", "bob" to "member"), roles(team), "No role changed, nobody removed")
            assertEquals(invitationsBefore, liveInvitations(team), "Nobody invited")
        }
    }

    @Test
    fun aTokenIsRefusedBeforeAnythingIsHeldWhenItCannotWrite() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val readOnly = koin.get<CreatePersonalTokenUseCase>()(team.ada.id, "ro", listOf("orgs:read"), null).secret
            val noScope = client.delete("/api/v1/orgs/acme/members/bob") { bearerAuth(readOnly) }
            assertEquals(HttpStatusCode.Forbidden, noScope.status)
            // Another organization answers 404, as if it did not exist.
            val carl = signIn("carl")
            client.post("/api/v1/orgs") {
                session(carl)
                json(CreateOrgRequest("Globex", null))
            }
            val elsewhere = client.delete("/api/v1/orgs/globex/members/carl") { bearerAuth(team.token) }
            assertEquals(HttpStatusCode.NotFound, elsewhere.status)
            val badRole = client.patch("/api/v1/orgs/acme/members/bob") {
                bearerAuth(team.token)
                json(UpdateMemberRequest("emperor"))
            }
            assertEquals(HttpStatusCode.BadRequest, badRole.status)
            assertEquals(emptyList(), held)
        }
    }

    @Test
    fun theConsoleShowsEachHeldWriteAndMakesItAsTheConfirmers() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val invitationsBefore = liveInvitations(team)
            val change = koin.get<GovernanceChange>(named("orgs"))
            assertEquals(setOf("members.role", "members.remove", "members.invite", "members.link"), change.kinds)
            client.patch("/api/v1/orgs/acme/members/bob") {
                bearerAuth(team.token)
                json(UpdateMemberRequest("admin"))
            }
            val role = held.singleOrNull()?.write ?: fail("The token's role change was not held")
            val shown = change.preview(team.orgId, role)
            assertEquals(member("bob", "member"), shown.before)
            assertEquals(member("bob", "admin"), shown.after)
            val made = change.apply(team.orgId, role, confirmer(team))
            assertEquals("admin", made?.jsonObject?.getValue("role")?.jsonPrimitive?.content)
            assertEquals(mapOf("ada" to "owner", "bob" to "admin"), roles(team))
            // What it replaced has changed: the console reads it again and sees the difference.
            assertEquals(member("bob", "admin"), change.preview(team.orgId, role).before)

            val invite = OrgsGovernanceChange.invites(
                listOf(InviteRequest("eve@eve.test", OrgRole.APPROVER)),
            )
            val invitePreview = change.preview(team.orgId, invite)
            assertNull(invitePreview.before)
            assertEquals(
                "approver",
                invitePreview.after?.jsonArray?.single()?.jsonObject?.getValue("role")?.jsonPrimitive?.content,
            )
            val invited = change.apply(team.orgId, invite, confirmer(team))
            val invitedEmails = invited?.jsonObject?.getValue("invited")?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("eve@eve.test"), invitedEmails)

            val link = OrgsGovernanceChange.link(OrgRole.MEMBER, 2)
            val linkPreview = change.preview(team.orgId, link)
            assertNull(linkPreview.before)
            assertEquals(link.payload, linkPreview.after)
            val issued = change.apply(team.orgId, link, confirmer(team))?.jsonObject
            assertTrue(issued?.getValue("url")?.jsonPrimitive?.content.orEmpty().contains("#token="))
            assertEquals(invitationsBefore + 2, liveInvitations(team), "The e-mail invitation and the new link")

            val removal = OrgsGovernanceChange.removal("bob")
            assertEquals(member("bob", "admin"), change.preview(team.orgId, removal).before)
            assertNull(change.preview(team.orgId, removal).after)
            assertNull(change.apply(team.orgId, removal, confirmer(team)))
            assertEquals(mapOf("ada" to "owner"), roles(team))
        }
    }

    @Test
    fun aWriteThatCannotBeMadeOpensNoConfirmation() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val change = koin.get<GovernanceChange>(named("orgs"))
            // Nobody named so: the preview fails, so the confirmation service refuses to open it.
            assertFailsWith<OstromException> { change.preview(team.orgId, OrgsGovernanceChange.removal("nobody")) }
            // Ownership changes hands by a transfer, never by a role.
            assertFailsWith<OstromException> {
                change.preview(team.orgId, OrgsGovernanceChange.role("bob", OrgRole.OWNER))
            }
            // A confirmer who may not make it: the use case refuses it as the confirmer's.
            val bob = signIn("bob")
            val asBob = GovernanceConfirmer(
                browser(bob),
                OrgPrincipal(team.orgId, bob.id, OrgRole.MEMBER),
            )
            assertFailsWith<OstromException> {
                change.apply(team.orgId, OrgsGovernanceChange.role("ada", OrgRole.MEMBER), asBob)
            }
            assertEquals(mapOf("ada" to "owner", "bob" to "member"), roles(team))
        }
    }

    private fun member(login: String, role: String): JsonObject = buildJsonObject {
        put("login", login)
        put("role", role)
    }

}
