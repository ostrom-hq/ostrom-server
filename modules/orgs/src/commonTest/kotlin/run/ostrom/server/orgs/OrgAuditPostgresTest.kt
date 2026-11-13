// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.CreateTokenRequest
import run.ostrom.server.identity.presentation.CreatedTokenResponse
import run.ostrom.server.identity.presentation.OrgAuditPageResponse
import run.ostrom.server.identity.presentation.SERVER_ACTOR
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.orgs.presentation.CreateOrgRequest
import run.ostrom.server.orgs.presentation.OrgResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/audit` on a real PostgreSQL: an owner reads their organization's log, filtered and by pages, with what
 * the other modules wrote through the kernel's trail; a plain member is refused, and nobody reads another
 * organization's log, even by naming it.
 */
class OrgAuditPostgresTest {
    private val modules = listOf(IdentityModule, OrgsModule)
    private val overrides = module {
        single<MailSender> {
            object : MailSender {
                override suspend fun send(mail: Mail) = Unit
            }
        }
    }

    private suspend fun TestServer.signIn(login: String): StartedSession {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@$login.test", true, login, null, login = login),
        )
        return koin.get<StartWebSessionUseCase>()(user.id)
    }

    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.createOrg(started: StartedSession, name: String): OrgResponse =
        client.post("/api/v1/orgs") {
            session(started)
            json(CreateOrgRequest(name, null))
        }.body()

    private suspend fun TestServer.audit(started: StartedSession, query: String = "") =
        client.get("/api/v1/audit$query") { session(started) }

    @Test
    fun anOwnerReadsTheLogTheModulesWrite() = serverTest(modules, overrides) { server ->
        val ada = server.signIn("ada")
        val acme = server.createOrg(ada, "Acme")
        val principal = AuthenticatedPrincipal(
            "${server.userId("ada")}", PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER,
        )
        server.koin.get<OrgAuditTrail>().record(
            OrgAuditRecord(Uuid.parse(acme.id), principal, "budget.changed", "budget", "organization"),
        )

        val page = server.audit(ada).body<OrgAuditPageResponse>()

        assertEquals(listOf("budget.changed", "org.created"), page.items.map { it.event })
        assertEquals(listOf("ada", "ada"), page.items.map { it.actor })
        assertEquals("budget:organization", page.items.first().subject)
        assertEquals("browser", page.items.first().meta["credential"])
        assertEquals("org:${acme.id}", page.items.last().subject)
        assertNull(page.next)
        val created = server.audit(ada, "?event=org.created").body<OrgAuditPageResponse>()
        assertEquals(listOf("org.created"), created.items.map { it.event })
        assertEquals(2, server.audit(ada, "?actor=ada").body<OrgAuditPageResponse>().items.size)
        assertEquals(emptyList(), server.audit(ada, "?actor=nobody").body<OrgAuditPageResponse>().items)
        assertEquals(HttpStatusCode.BadRequest, server.audit(ada, "?before=soon").status)
    }

    @Test
    fun aLongLogComesByPages() = serverTest(modules, overrides) { server ->
        val ada = server.signIn("ada")
        val acme = Uuid.parse(server.createOrg(ada, "Acme").id)
        val agent = AuthenticatedPrincipal("agent-7", PrincipalKind.AGENT, emptySet())
        server.koin.get<OrgAuditTrail>().record(OrgAuditRecord(acme, agent, "mcp.settings.changed", "settings", "mcp"))
        val log = server.koin.get<AuditLog>()
        repeat(PAGE) { log.record(AuditEntry(acme, null, "test.line", "test", "$it")) }

        val first = server.audit(ada).body<OrgAuditPageResponse>()
        assertEquals(PAGE, first.items.size)
        assertEquals(SERVER_ACTOR, first.items.first().actor)
        val second = server.audit(ada, "?before=${first.next}").body<OrgAuditPageResponse>()
        assertEquals(listOf("mcp.settings.changed", "org.created"), second.items.map { it.event })
        // A credential that is not a person's is named by its kind and id, never as the server.
        assertEquals(listOf("agent:agent-7", "ada"), second.items.map { it.actor })
        assertNull(second.next)
    }

    @Test
    fun aMemberIsRefusedAndAnotherOrganizationIsNeverRead() = serverTest(modules, overrides) { server ->
        val ada = server.signIn("ada")
        val acme = server.createOrg(ada, "Acme")
        val mallory = server.signIn("mallory")
        val evil = server.createOrg(mallory, "Evil")
        val bob = server.signIn("bob")
        val beta = Org(Uuid.random(), "beta", "Beta", null, null, Clock.System.now())
        val bobsMembership = Member(Uuid.random(), beta.id, server.userId("bob"), OrgRole.MEMBER, beta.createdAt)
        server.koin.get<OrgsRepository>().create(beta, bobsMembership) { false }

        // Bob is a member of Beta, not an owner or an admin; Carol administers Gamma.
        assertEquals(HttpStatusCode.Forbidden, server.audit(bob).status)
        val carol = server.signIn("carol")
        val gamma = Org(Uuid.random(), "gamma", "Gamma", null, null, Clock.System.now())
        val carolsMembership = Member(Uuid.random(), gamma.id, server.userId("carol"), OrgRole.ADMIN, gamma.createdAt)
        server.koin.get<OrgsRepository>().create(gamma, carolsMembership) { false }
        assertEquals(emptyList(), server.audit(carol).body<OrgAuditPageResponse>().items)
        // Mallory reads Evil's log only: her own organization by default, a 404 when she names Acme.
        val own = server.audit(mallory).body<OrgAuditPageResponse>()
        assertEquals(listOf("org:${evil.id}"), own.items.map { it.subject })
        val named = server.client.get("/api/v1/audit") {
            session(mallory)
            header(ORG_HEADER, acme.id)
        }
        assertEquals(HttpStatusCode.NotFound, named.status)
        // A token without audit:read is refused, one with it reads.
        val narrow = server.token(ada, "profile:read")
        assertEquals(HttpStatusCode.Forbidden, server.client.get("/api/v1/audit") { bearerAuth(narrow) }.status)
        val reader = server.token(ada, "audit:read")
        val read = server.client.get("/api/v1/audit") { bearerAuth(reader) }.body<OrgAuditPageResponse>()
        assertEquals(listOf("org:${acme.id}"), read.items.map { it.subject })
    }

    private suspend fun TestServer.userId(login: String): Uuid =
        koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@$login.test", true, login, null, login = login),
        ).id

    private suspend fun TestServer.token(started: StartedSession, scope: String): String =
        client.post("/api/v1/tokens") {
            session(started)
            json(CreateTokenRequest("audit", listOf(scope)))
        }.body<CreatedTokenResponse>().secret

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        const val PAGE = 50
    }
}
