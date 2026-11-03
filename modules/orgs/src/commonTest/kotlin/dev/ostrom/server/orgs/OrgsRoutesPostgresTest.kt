// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.IssueAgentTokenUseCase
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.MeOrganizationResponse
import dev.ostrom.server.identity.presentation.MeResponse
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.orgs.presentation.CreateLinkRequest
import dev.ostrom.server.orgs.presentation.CreateOrgRequest
import dev.ostrom.server.orgs.presentation.DeleteOrgRequest
import dev.ostrom.server.orgs.presentation.InvitationLinkResponse
import dev.ostrom.server.orgs.presentation.InvitationPreviewResponse
import dev.ostrom.server.orgs.presentation.InvitationTokenRequest
import dev.ostrom.server.orgs.presentation.InvitationsResponse
import dev.ostrom.server.orgs.presentation.InviteRequestBody
import dev.ostrom.server.orgs.presentation.InvitedResponse
import dev.ostrom.server.orgs.presentation.MemberResponse
import dev.ostrom.server.orgs.presentation.MembersResponse
import dev.ostrom.server.orgs.presentation.MyOrganizationsResponse
import dev.ostrom.server.orgs.presentation.OrgResponse
import dev.ostrom.server.orgs.presentation.OrgsResponse
import dev.ostrom.server.orgs.presentation.TransferRequest
import dev.ostrom.server.orgs.presentation.UpdateMemberRequest
import dev.ostrom.server.orgs.presentation.UpdateOrgRequest
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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid
import org.koin.dsl.module

/**
 * The organization journeys end to end: real HTTP through the kernel, real PostgreSQL, real
 * credentials.
 */
class OrgsRoutesPostgresTest {
    private val mail = mutableListOf<Mail>()
    private val modules = listOf(IdentityModule, OrgsModule)
    private val overrides = module {
        single<MailSender> {
            object : MailSender {
                override suspend fun send(mail: Mail) {
                    this@OrgsRoutesPostgresTest.mail += mail
                }
            }
        }
    }

    private suspend fun TestServer.signIn(
        login: String,
        email: String = "$login@example.com",
        avatarUrl: String? = null,
    ): Pair<User, StartedSession> {
        val user =
            koin.get<ResolveUserUseCase>()(
                VerifiedIdentity(IdentityProvider.GITHUB, login, email, true, login, avatarUrl, login = login)
            )
        return user to koin.get<StartWebSessionUseCase>()(user.id)
    }

    private fun HttpRequestBuilder.session(started: StartedSession) {
        // #58: a browser write needs the CSRF header repeating the readable cookie (double submit), from the console's
        // origin (the default console URL of the test configuration).
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.createOrg(
        started: StartedSession,
        name: String,
        slug: String? = null,
    ) =
        client.post("/api/v1/orgs") {
            session(started)
            json(CreateOrgRequest(name, slug))
        }

    private fun tokenOf(link: String) = link.substringAfter("#token=")

    @Test
    fun meShowsTheMembershipOfTheOrganizationTheHeaderNamesAndIgnoresAnyOther() =
        serverTest(modules, overrides) { server ->
            val (ada, session) = server.signIn("ada")
            val (bob, _) = server.signIn("bob")
            val orgs = server.koin.get<OrgsRepository>()
            val now = Clock.System.now()
            val alpha = Org(Uuid.random(), "alpha", "Alpha", null, null, now)
            orgs.create(alpha, Member(Uuid.random(), alpha.id, ada.id, OrgRole.MEMBER, now)) { false }
            val beta = Org(Uuid.random(), "beta", "Beta", 42L, null, now)
            val adaInBeta = Member(Uuid.random(), beta.id, ada.id, OrgRole.OWNER, now)
            orgs.create(beta, adaInBeta) { false }
            val foreign = Org(Uuid.random(), "foreign", "Foreign", null, null, now)
            orgs.create(foreign, Member(Uuid.random(), foreign.id, bob.id, OrgRole.OWNER, now)) { false }
            suspend fun me(org: String?) =
                server.client.get("/api/v1/auth/me") {
                    session(session)
                    if (org != null) header(ORG_HEADER, org)
                }

            val inBeta = me(beta.id.toString())
            assertEquals(HttpStatusCode.OK, inBeta.status)
            // The contract's `Me`: Ada's membership in Beta, and Beta with its GitHub organization's avatar.
            val profile = inBeta.body<MeResponse>()
            assertEquals(adaInBeta.id.toString(), profile.member?.id)
            assertEquals("owner", profile.member?.role)
            assertEquals(ada.login, profile.member?.login)
            val avatar = "https://avatars.githubusercontent.com/u/42"
            assertEquals(MeOrganizationResponse(beta.id.toString(), "Beta", avatar, "github-app"), profile.organization)
            assertEquals(beta.id.toString(), profile.dev.organizationId)
            val inAlpha = me(alpha.id.toString()).body<MeResponse>()
            assertEquals("member", inAlpha.member?.role)
            // Alpha is linked to no GitHub organization: no avatar, and no connection rather than a false one.
            assertEquals(MeOrganizationResponse(alpha.id.toString(), "Alpha", "", null), inAlpha.organization)
            // Not a membership, not an id, or no header: 200 without a member, never a 404 or a 409.
            listOf(foreign.id.toString(), "not-an-id", null).forEach { org ->
                val ignored = me(org)
                assertEquals(HttpStatusCode.OK, ignored.status)
                val unnamed = ignored.body<MeResponse>()
                assertNull(unnamed.member)
                assertNull(unnamed.organization)
                assertNull(unnamed.dev.organizationId)
            }
        }

    @Test
    fun aPersonInTwoOrganizationsReadsThemWithoutNamingOne() =
        serverTest(modules, overrides) { server ->
            val (ada, session) = server.signIn("ada")
            val orgs = server.koin.get<OrgsRepository>()
            val now = Clock.System.now()
            listOf("acme", "globex").forEach { slug ->
                val org = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)
                orgs.create(org, Member(Uuid.random(), org.id, ada.id, OrgRole.OWNER, now)) { false }
            }

            // Two memberships and no Ostrom-Org header: neither route asks for one.
            assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/auth/me") { session(session) }.status)
            val mine = server.client.get("/api/v1/me/organizations") { session(session) }
            assertEquals(HttpStatusCode.OK, mine.status)
            assertEquals(
                listOf("ACME" to "owner", "GLOBEX" to "owner"),
                mine.body<MyOrganizationsResponse>().items.map { it.name to it.role },
            )
        }

    @Test
    @Suppress("LongMethod") // One end-to-end story through the HTTP API, read top to bottom.
    fun aPersonCreatesATeamInvitesByEmailAndTheInviteeJoins() =
        serverTest(modules, overrides) { server ->
            val (_, ada) = server.signIn("ada", "ada@acme.com", "https://avatars.test/ada")

            val created = server.createOrg(ada, "Acme")
            assertEquals(HttpStatusCode.Created, created.status)
            val org = created.body<OrgResponse>()
            assertEquals("acme" to "owner", org.slug to org.role)
            assertEquals(
                listOf(org),
                server.client.get("/api/v1/orgs") { session(ada) }.body<OrgsResponse>().items,
            )
            assertEquals(
                org,
                server.client.get("/api/v1/orgs/${org.id}") { session(ada) }.body<OrgResponse>(),
            )

            val invited =
                server.client
                    .post("/api/v1/orgs/acme/members/invites") {
                        session(ada)
                        json(
                            InviteRequestBody(
                                listOf(
                                    mapOf("email" to "Dana@Acme.com", "role" to "approver"),
                                    mapOf("email" to "eve@acme.com"),
                                )
                            )
                        )
                    }
                    .body<InvitedResponse>()
            assertEquals(listOf("dana@acme.com", "eve@acme.com"), invited.invited)
            val danaMail = mail.first { it.to == "dana@acme.com" }
            assertEquals("ada invited you to Acme on Ostrom", danaMail.subject)
            val link = danaMail.text.lines().first { it.startsWith("http") }
            assertTrue(link.startsWith("http://localhost:5173/invite#token="), link)

            val preview =
                server.client.post("/api/v1/invitations/preview") {
                    json(InvitationTokenRequest(tokenOf(link)))
                }
            assertEquals(
                InvitationPreviewResponse(
                    "Acme",
                    "acme",
                    "approver",
                    "dana@acme.com",
                    preview.body<InvitationPreviewResponse>().expiresAt,
                ),
                preview.body(),
            )
            // Someone else signed in with the link is told nothing.
            val (_, mallory) = server.signIn("mallory")
            val stolen =
                server.client.post("/api/v1/invitations/accept") {
                    session(mallory)
                    json(InvitationTokenRequest(tokenOf(link)))
                }
            assertEquals(HttpStatusCode.NotFound, stolen.status)

            val (_, dana) = server.signIn("dana", "dana@acme.com")
            val joined =
                server.client.post("/api/v1/invitations/accept") {
                    session(dana)
                    json(InvitationTokenRequest(tokenOf(link)))
                }
            assertEquals(HttpStatusCode.OK, joined.status)
            assertEquals("approver", joined.body<OrgResponse>().role)
            val members =
                server.client
                    .get("/api/v1/orgs/acme/members") { session(dana) }
                    .body<MembersResponse>()
                    .items
            assertEquals(
                listOf("ada" to "owner", "dana" to "approver"),
                members.map { it.login to it.role },
            )
            assertEquals(listOf("https://avatars.test/ada", ""), members.map { it.avatarUrl })
            val pending =
                server.client
                    .get("/api/v1/orgs/acme/invitations") { session(ada) }
                    .body<InvitationsResponse>()
                    .items
            assertEquals(listOf("eve@acme.com"), pending.map { it.email })
        }

    @Test
    @Suppress("LongMethod") // One end-to-end story through the HTTP API, read top to bottom.
    fun aLinkBringsPeopleInAndAdminsManageRolesRemovalsAndRevocations() =
        serverTest(modules, overrides) { server ->
            val (_, ada) = server.signIn("ada")
            server.createOrg(ada, "Acme")

            val created =
                server.client.post("/api/v1/orgs/acme/invitations/links") {
                    session(ada)
                    json(CreateLinkRequest(role = "member", maxUses = 3))
                }
            assertEquals(HttpStatusCode.Created, created.status)
            val issued = created.body<InvitationLinkResponse>()
            val (_, bob) = server.signIn("bob")
            val (_, carl) = server.signIn("carl")
            listOf(bob, carl).forEach { session ->
                val accepted =
                    server.client.post("/api/v1/invitations/accept") {
                        session(session)
                        json(InvitationTokenRequest(tokenOf(issued.url)))
                    }
                assertEquals(HttpStatusCode.OK, accepted.status)
            }
            val again =
                server.client.post("/api/v1/invitations/accept") {
                    session(bob)
                    json(InvitationTokenRequest(tokenOf(issued.url)))
                }
            assertEquals(HttpStatusCode.Conflict, again.status)

            val promoted =
                server.client.patch("/api/v1/orgs/acme/members/bob") {
                    session(ada)
                    json(UpdateMemberRequest("admin"))
                }
            val bobAsAdmin = promoted.body<MemberResponse>()
            assertEquals("admin", bobAsAdmin.role)
            val bobSeen = server.client.get("/api/v1/orgs/acme/members/bob") { session(carl) }.body<MemberResponse>()
            assertEquals("admin", bobSeen.role)
            // The contract's `Member.id`: the member row's id, the same on every route that shows the member.
            assertEquals(bobAsAdmin.id, bobSeen.id)
            assertNotNull(Uuid.parseOrNull(bobSeen.id))
            val listed = server.client.get("/api/v1/orgs/acme/members") { session(carl) }.body<MembersResponse>()
            assertEquals(bobSeen.id, listed.items.single { it.login == "bob" }.id)
            // A member reads their own address only; an owner or an admin reads everyone's (audit 2 of the contract, F2).
            assertEquals(
                listOf("ada" to null, "bob" to null, "carl" to "carl@example.com"),
                listed.items.map { it.login to it.email },
            )
            assertNull(bobSeen.email)
            val adaSeen = server.client.get("/api/v1/orgs/acme/members/ada") { session(carl) }.body<MemberResponse>()
            assertNull(adaSeen.email)
            val carlSeen = server.client.get("/api/v1/orgs/acme/members/carl") { session(carl) }.body<MemberResponse>()
            assertEquals("carl@example.com", carlSeen.email)
            val carlByAdmin = server.client.get("/api/v1/orgs/acme/members/carl") { session(bob) }.body<MemberResponse>()
            assertEquals("carl@example.com", carlByAdmin.email)
            val byOwner = server.client.get("/api/v1/orgs/acme/members") { session(ada) }.body<MembersResponse>()
            assertEquals(
                listOf("ada@example.com", "bob@example.com", "carl@example.com"),
                byOwner.items.map { it.email },
            )
            val badRole =
                server.client.patch("/api/v1/orgs/acme/members/carl") {
                    session(bob)
                    json(UpdateMemberRequest("king"))
                }
            assertEquals(HttpStatusCode.BadRequest, badRole.status)
            val notAllowed =
                server.client.patch("/api/v1/orgs/acme/members/bob") {
                    session(carl)
                    json(UpdateMemberRequest("member"))
                }
            assertEquals(HttpStatusCode.Forbidden, notAllowed.status)

            assertEquals(
                HttpStatusCode.NoContent,
                server.client.delete("/api/v1/orgs/acme/members/carl") { session(bob) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.get("/api/v1/orgs/acme") { session(carl) }.status,
            )

            val links =
                server.client
                    .get("/api/v1/orgs/acme/invitations") { session(bob) }
                    .body<InvitationsResponse>()
                    .items
            assertEquals(2, links.single().uses)
            assertEquals(
                HttpStatusCode.NoContent,
                server.client
                    .delete("/api/v1/orgs/acme/invitations/${links.single().id}") { session(bob) }
                    .status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client
                    .delete("/api/v1/orgs/acme/invitations/${links.single().id}") { session(bob) }
                    .status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client
                    .delete("/api/v1/orgs/acme/invitations/not-an-id") { session(bob) }
                    .status,
            )
            val dead =
                server.client.post("/api/v1/invitations/preview") {
                    json(InvitationTokenRequest(tokenOf(issued.url)))
                }
            assertEquals(HttpStatusCode.NotFound, dead.status)
            val missingEmail =
                server.client.post("/api/v1/orgs/acme/members/invites") {
                    session(bob)
                    json(InviteRequestBody(listOf(mapOf("role" to "member"))))
                }
            assertEquals(HttpStatusCode.BadRequest, missingEmail.status)
        }

    @Test
    @Suppress("LongMethod") // One end-to-end story through the HTTP API, read top to bottom.
    fun theOwnerRenamesTransfersLeavesAndDeletesWithABrowserSessionOnly() =
        serverTest(modules, overrides) { server ->
            val (ada, adaSession) = server.signIn("ada")
            server.createOrg(adaSession, "Acme")
            val issued =
                server.client
                    .post("/api/v1/orgs/acme/invitations/links") {
                        session(adaSession)
                        json(CreateLinkRequest())
                    }
                    .body<InvitationLinkResponse>()
            val (_, bobSession) = server.signIn("bob")
            server.client.post("/api/v1/invitations/accept") {
                session(bobSession)
                json(InvitationTokenRequest(tokenOf(issued.url)))
            }

            val renamed =
                server.client.patch("/api/v1/orgs/acme") {
                    session(adaSession)
                    json(UpdateOrgRequest("Acme Labs"))
                }
            assertEquals("Acme Labs", renamed.body<OrgResponse>().name)

            // A token, even with orgs:write, never transfers nor deletes.
            val token =
                server.koin.get<CreatePersonalTokenUseCase>()(
                        ada.id,
                        "ci",
                        listOf("orgs:read", "orgs:write"),
                        null,
                    )
                    .secret
            assertEquals(
                HttpStatusCode.OK,
                server.client.get("/api/v1/orgs/acme") { bearerAuth(token) }.status,
            )
            val byToken =
                server.client.post("/api/v1/orgs/acme/transfer") {
                    bearerAuth(token)
                    json(TransferRequest("bob"))
                }
            assertEquals(HttpStatusCode.Forbidden, byToken.status)
            val readOnly =
                server.koin.get<CreatePersonalTokenUseCase>()(
                        ada.id,
                        "ro",
                        listOf("orgs:read"),
                        null,
                    )
                    .secret
            val noScope =
                server.client.patch("/api/v1/orgs/acme") {
                    bearerAuth(readOnly)
                    json(UpdateOrgRequest("Nope"))
                }
            assertEquals(HttpStatusCode.Forbidden, noScope.status)

            val transferred =
                server.client.post("/api/v1/orgs/acme/transfer") {
                    session(adaSession)
                    json(TransferRequest("bob"))
                }
            assertEquals(HttpStatusCode.NoContent, transferred.status)
            assertEquals(
                "admin",
                server.client
                    .get("/api/v1/orgs/acme") { session(adaSession) }
                    .body<OrgResponse>()
                    .role,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                server.client.post("/api/v1/orgs/acme/leave") { session(adaSession) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.get("/api/v1/orgs/acme") { session(adaSession) }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                server.client.post("/api/v1/orgs/acme/leave") { session(bobSession) }.status,
            )

            val unconfirmed =
                server.client.delete("/api/v1/orgs/acme") {
                    session(bobSession)
                    json(DeleteOrgRequest("ACME"))
                }
            assertEquals(HttpStatusCode.BadRequest, unconfirmed.status)
            val deleted =
                server.client.delete("/api/v1/orgs/acme") {
                    session(bobSession)
                    json(DeleteOrgRequest("acme"))
                }
            assertEquals(HttpStatusCode.NoContent, deleted.status)
            assertEquals(
                emptyList(),
                server.client.get("/api/v1/orgs") { session(bobSession) }.body<OrgsResponse>().items,
            )
        }

    @Test
    @Suppress("LongMethod") // One end-to-end story through the HTTP API, read top to bottom.
    fun anotherOrganizationsPeopleAndAgentsSeeNothingOfIt() =
        serverTest(modules, overrides) { server ->
            val (ada, adaSession) = server.signIn("ada")
            val acme = server.createOrg(adaSession, "Acme").body<OrgResponse>()
            // Another company: the free plan holds one team per domain, so not example.com again.
            val (_, mallory) = server.signIn("mallory", "mallory@evil.test")
            assertEquals(HttpStatusCode.Created, server.createOrg(mallory, "Evil").status)

            // Every org route answers a non-member as if the organization did not exist.
            val probes =
                listOf(
                    server.client.get("/api/v1/orgs/acme") { session(mallory) },
                    server.client.get("/api/v1/orgs/${acme.id}") { session(mallory) },
                    server.client.get("/api/v1/orgs/${Uuid.random()}") { session(mallory) },
                    server.client.get("/api/v1/orgs/acme/members") { session(mallory) },
                    server.client.get("/api/v1/orgs/acme/members/ada") { session(mallory) },
                    server.client.get("/api/v1/orgs/acme/invitations") { session(mallory) },
                    server.client.patch("/api/v1/orgs/acme") {
                        session(mallory)
                        json(UpdateOrgRequest("Owned"))
                    },
                    server.client.patch("/api/v1/orgs/acme/members/ada") {
                        session(mallory)
                        json(UpdateMemberRequest("member"))
                    },
                    server.client.delete("/api/v1/orgs/acme/members/ada") { session(mallory) },
                    server.client.post("/api/v1/orgs/acme/members/invites") {
                        session(mallory)
                        json(InviteRequestBody(listOf(mapOf("email" to "x@example.com"))))
                    },
                    server.client.post("/api/v1/orgs/acme/invitations/links") {
                        session(mallory)
                        json(CreateLinkRequest())
                    },
                    server.client.post("/api/v1/orgs/acme/leave") { session(mallory) },
                )
            probes.forEach {
                assertEquals(HttpStatusCode.NotFound, it.status, it.call.request.url.toString())
            }
            assertEquals(
                listOf("evil"),
                server.client
                    .get("/api/v1/orgs") { session(mallory) }
                    .body<OrgsResponse>()
                    .items
                    .map { it.slug },
            )

            // An agent token never manages an organization (audit A2), even its own session's.
            val agent =
                server.koin.get<IssueAgentTokenUseCase>()(
                        ada.id,
                        Uuid.parse(acme.id),
                        "run-1",
                        1.hours,
                    )
                    .secret
            assertEquals(
                HttpStatusCode.Forbidden,
                server.client.get("/api/v1/orgs") { bearerAuth(agent) }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                server.client.get("/api/v1/orgs/acme/members") { bearerAuth(agent) }.status,
            )
            // Without credentials, 401; a browser write without the CSRF token, 401.
            assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/orgs").status)
            val forged =
                server.client.post("/api/v1/orgs") {
                    header(HttpHeaders.Cookie, "$SESSION_COOKIE=${adaSession.secret}")
                    json(CreateOrgRequest("Forged"))
                }
            assertEquals(HttpStatusCode.Unauthorized, forged.status)
        }

    @Test
    fun theFreePlanIsOneTeamPerDomainAndFivePeople() =
        serverTest(modules, overrides) { server ->
            val (_, ada) = server.signIn("ada", "ada@acme.com")
            server.createOrg(ada, "Acme")
            val (_, bob) = server.signIn("bob", "bob@acme.com")
            assertEquals(HttpStatusCode.PaymentRequired, server.createOrg(bob, "Acme bis").status)
            assertEquals(HttpStatusCode.Conflict, server.createOrg(bob, "Other", "acme").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                server.createOrg(bob, "Other", "Bad Slug").status,
            )

            val invites = (1..4).map { mapOf("email" to "p$it@acme.com") }
            val four =
                server.client.post("/api/v1/orgs/acme/members/invites") {
                    session(ada)
                    json(InviteRequestBody(invites))
                }
            assertEquals(4, four.body<InvitedResponse>().invited.size)
            val sixth =
                server.client.post("/api/v1/orgs/acme/members/invites") {
                    session(ada)
                    json(InviteRequestBody(listOf(mapOf("email" to "p5@acme.com"))))
                }
            assertEquals(HttpStatusCode.PaymentRequired, sixth.status)
        }
}

private const val CONSOLE_ORIGIN = "http://localhost:5173"
