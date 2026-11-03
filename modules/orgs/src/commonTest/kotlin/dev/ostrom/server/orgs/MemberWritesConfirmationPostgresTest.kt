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
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.orgs.domain.InviteRequest
import dev.ostrom.server.orgs.domain.MemberWrite
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
import io.ktor.client.statement.HttpResponse
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
 * present, never changes a role, removes a member or invites anyone by itself. The write is checked as the asker's
 * before it is held, then made as the confirmer's. Real HTTP, real PostgreSQL, the identity and orgs modules, a fake
 * confirmation service that records what it is asked to hold.
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
    private class Team(val ada: Person, val bob: Person, val orgId: Uuid, val token: String)

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

    private suspend fun TestServer.writer(person: Person): String =
        koin.get<CreatePersonalTokenUseCase>()(person.id, "laptop", listOf("orgs:read", "orgs:write"), null).secret

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
        val bob = signIn("bob")
        client.post("/api/v1/invitations/accept") {
            session(bob)
            json(InvitationTokenRequest(link.url.substringAfter("#token=")))
        }
        return Team(ada, bob, Uuid.parse(org.id), writer(ada))
    }

    private suspend fun TestServer.members(team: Team): MembersResponse =
        client.get("/api/v1/orgs/acme/members") { session(team.ada) }.body<MembersResponse>()

    private suspend fun TestServer.roles(team: Team): Map<String, String> =
        members(team).items.associate { it.login to it.role }

    private suspend fun TestServer.liveInvitations(team: Team): Int =
        client.get("/api/v1/orgs/acme/invitations") { session(team.ada) }.body<InvitationsResponse>().items.size

    // What the preview shows of a member: their membership, login and role, never their address.
    private suspend fun TestServer.shown(team: Team, login: String, role: String): JsonObject = buildJsonObject {
        put("id", members(team).items.single { it.login == login }.id)
        put("login", login)
        put("role", role)
    }

    private fun confirmer(team: Team) = GovernanceConfirmer(
        browser(team.ada),
        OrgPrincipal(team.orgId, team.ada.id, OrgRole.OWNER),
    )

    private fun browser(person: Person) = AuthenticatedPrincipal(
        person.id.toString(),
        PrincipalKind.USER,
        setOf("orgs:write"),
        channel = PrincipalChannel.BROWSER,
    )

    private suspend fun TestServer.changeRole(token: String, login: String, role: String): HttpResponse =
        client.patch("/api/v1/orgs/acme/members/$login") {
            bearerAuth(token)
            json(UpdateMemberRequest(role))
        }

    private suspend fun TestServer.invite(token: String, email: String, role: String): HttpResponse =
        client.post("/api/v1/orgs/acme/members/invites") {
            bearerAuth(token)
            json(InviteRequestBody(listOf(mapOf("email" to email, "role" to role))))
        }

    private suspend fun TestServer.link(token: String, role: String, maxUses: Int): HttpResponse =
        client.post("/api/v1/orgs/acme/invitations/links") {
            bearerAuth(token)
            json(CreateLinkRequest(role = role, maxUses = maxUses))
        }

    @Test
    fun aTokensFourMemberWritesAreHeldForTheConsoleAndNothingIsWritten() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val invitationsBefore = liveInvitations(team)
            val role = changeRole(team.token, "bob", "admin")
            assertEquals(HttpStatusCode.Accepted, role.status, role.bodyAsText())
            assertEquals("gcf_1", role.body<GovernanceConfirmationPending>().confirmationId)
            val removal = client.delete("/api/v1/orgs/acme/members/bob") { bearerAuth(team.token) }
            assertEquals(HttpStatusCode.Accepted, removal.status, removal.bodyAsText())
            val invite = invite(team.token, "eve@eve.test", "admin")
            assertEquals(HttpStatusCode.Accepted, invite.status, invite.bodyAsText())
            val link = link(team.token, "admin", 3)
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
    fun aTokensWriteIsCheckedAsTheAskersBeforeAnythingIsHeld() = serverTest(modules, overrides) { server ->
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
            // The write's own refusals, before anything is held: a bad role, the owner role, an unknown member, a bad
            // address, a link for nobody.
            assertEquals(HttpStatusCode.BadRequest, changeRole(team.token, "bob", "emperor").status)
            assertEquals(HttpStatusCode.BadRequest, changeRole(team.token, "bob", "owner").status)
            assertEquals(HttpStatusCode.NotFound, changeRole(team.token, "nobody", "admin").status)
            assertEquals(HttpStatusCode.BadRequest, invite(team.token, "not-an-address", "member").status)
            assertEquals(HttpStatusCode.BadRequest, link(team.token, "member", 0).status)
            // An admin's token asks nothing an admin cannot do, so an owner's confirmation never carries it.
            val promoted = client.patch("/api/v1/orgs/acme/members/bob") {
                session(team.ada)
                json(UpdateMemberRequest("admin"))
            }
            assertEquals(HttpStatusCode.OK, promoted.status, promoted.bodyAsText())
            val bobs = writer(team.bob)
            assertEquals(HttpStatusCode.Forbidden, changeRole(bobs, "ada", "member").status)
            val removeOwner = client.delete("/api/v1/orgs/acme/members/ada") { bearerAuth(bobs) }
            assertEquals(HttpStatusCode.Forbidden, removeOwner.status)
            assertEquals(HttpStatusCode.Forbidden, invite(bobs, "eve@eve.test", "admin").status)
            assertEquals(HttpStatusCode.Forbidden, link(bobs, "admin", 1).status)
            assertEquals(emptyList(), held)
            // What an admin may do is held.
            assertEquals(HttpStatusCode.Accepted, invite(bobs, " Eve@Eve.test ", "member").status)
            // Held as it will be made: the address cleaned.
            val heldInvite = held.single().write.payload.getValue("invites").jsonArray.single().jsonObject
            assertEquals("eve@eve.test", heldInvite.getValue("email").jsonPrimitive.content)
        }
    }

    @Test
    fun theConsoleShowsEachHeldWriteAndMakesItAsTheConfirmers() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val invitationsBefore = liveInvitations(team)
            val change = koin.get<GovernanceChange>(named("orgs"))
            assertEquals(setOf("members.role", "members.remove", "members.invite", "members.link"), change.kinds)
            changeRole(team.token, "bob", "admin")
            val role = held.singleOrNull()?.write ?: fail("The token's role change was not held")
            val shown = change.preview(team.orgId, role)
            assertEquals(shown(team, "bob", "member"), shown.before)
            assertEquals(shown(team, "bob", "admin"), shown.after)
            val made = change.apply(team.orgId, role, confirmer(team))
            assertEquals("admin", made?.jsonObject?.getValue("role")?.jsonPrimitive?.content)
            assertEquals(mapOf("ada" to "owner", "bob" to "admin"), roles(team))
            // What it replaced has changed: the console reads it again and sees the difference.
            assertEquals(shown(team, "bob", "admin"), change.preview(team.orgId, role).before)

            val eve = InviteRequest("eve@eve.test", OrgRole.APPROVER)
            val invite = OrgsGovernanceChange.of(MemberWrite.Invites(listOf(eve)))
            val invitePreview = change.preview(team.orgId, invite)
            assertEquals("Invite by e-mail: eve@eve.test", invitePreview.summary)
            assertNull(invitePreview.before)
            assertEquals(
                "approver",
                invitePreview.after?.jsonArray?.single()?.jsonObject?.getValue("role")?.jsonPrimitive?.content,
            )
            val invited = change.apply(team.orgId, invite, confirmer(team))
            val invitedEmails = invited?.jsonObject?.getValue("invited")?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("eve@eve.test"), invitedEmails)

            val link = OrgsGovernanceChange.of(MemberWrite.Link(OrgRole.MEMBER, 2))
            val linkPreview = change.preview(team.orgId, link)
            assertNull(linkPreview.before)
            assertEquals(link.payload, linkPreview.after)
            val issued = change.apply(team.orgId, link, confirmer(team))?.jsonObject
            assertTrue(issued?.getValue("url")?.jsonPrimitive?.content.orEmpty().contains("#token="))
            assertEquals(invitationsBefore + 2, liveInvitations(team), "The e-mail invitation and the new link")
            // Shown once, to the person who asked: then only the invitation is kept, never the address.
            val shownOnce = change.shownOnce("members.link", issued ?: fail("No link"))?.jsonObject
            assertEquals(setOf("invitation", "shown"), shownOnce?.keys)
            assertEquals(issued?.getValue("invitation"), shownOnce?.getValue("invitation"))
            assertNull(change.shownOnce("members.invite", invited ?: fail("No invitation")), "No secret to hide")

            val removal = OrgsGovernanceChange.of(MemberWrite.Removal("bob"))
            assertEquals(shown(team, "bob", "admin"), change.preview(team.orgId, removal).before)
            assertNull(change.preview(team.orgId, removal).after)
            assertNull(change.apply(team.orgId, removal, confirmer(team)))
            assertEquals(mapOf("ada" to "owner"), roles(team))
        }
    }

    @Test
    fun aWriteThatCannotBeMadeIsRefusedAtTheConsole() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val change = koin.get<GovernanceChange>(named("orgs"))
            // Nobody named so: the preview fails, so the confirmation service refuses to open it, or to confirm it.
            val nobody = OrgsGovernanceChange.of(MemberWrite.Removal("nobody"))
            assertFailsWith<OstromException> { change.preview(team.orgId, nobody) }
            // The owner role, read again at the confirmation: the ownership moves by a transfer.
            val owner = OrgsGovernanceChange.of(MemberWrite.Role("bob", OrgRole.OWNER))
            assertFailsWith<OstromException> { change.preview(team.orgId, owner) }
            // A kind this change does not own: the governance module never hands it one.
            assertFailsWith<IllegalStateException> {
                change.preview(team.orgId, GovernanceWrite("members.unknown", JsonObject(emptyMap())))
            }
            // A confirmer who may not make it: the use case refuses it as the confirmer's.
            val asBob = GovernanceConfirmer(browser(team.bob), OrgPrincipal(team.orgId, team.bob.id, OrgRole.MEMBER))
            assertFailsWith<OstromException> {
                change.apply(team.orgId, OrgsGovernanceChange.of(MemberWrite.Role("ada", OrgRole.MEMBER)), asBob)
            }
            assertEquals(mapOf("ada" to "owner", "bob" to "member"), roles(team))
        }
    }
}
