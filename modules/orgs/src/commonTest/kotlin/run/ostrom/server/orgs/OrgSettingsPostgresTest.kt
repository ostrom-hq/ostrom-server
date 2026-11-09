// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.orgs.domain.DeletionPass
import run.ostrom.server.orgs.domain.OrgDeletionsRepository
import run.ostrom.server.orgs.domain.SettleOrgDeletionsUseCase
import run.ostrom.server.orgs.fakes.SettableClock
import run.ostrom.server.orgs.presentation.CreateLinkRequest
import run.ostrom.server.orgs.presentation.CreateOrgRequest
import run.ostrom.server.orgs.presentation.DeleteOrgRequest
import run.ostrom.server.orgs.presentation.InvitationLinkResponse
import run.ostrom.server.orgs.presentation.InvitationTokenRequest
import run.ostrom.server.orgs.presentation.OrgResponse
import run.ostrom.server.orgs.presentation.OrgsResponse
import run.ostrom.server.orgs.presentation.UpdateOrgRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An organization's settings (name, time zone, a short name that never changes) and its deletion (owner only, a fresh
 * sign-in, 30 days kept, then erased with the audit kept), end to end: real HTTP, real PostgreSQL.
 */
class OrgSettingsPostgresTest {
    private val clock = SettableClock(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()))
    private val told = mutableListOf<String>()
    private val overrides = module {
        single<Clock> { clock }
        single<OrgDeletionListener>(named("probe")) {
            object : OrgDeletionListener {
                override suspend fun deleted(orgId: Uuid) {
                    told += "deleted $orgId"
                }

                override suspend fun purge(orgId: Uuid) {
                    told += "purge $orgId"
                }
            }
        }
    }
    private val modules = listOf(IdentityModule, OrgsModule)

    // Each person under a domain of their own: two free teams on one e-mail domain are refused (402).
    private suspend fun TestServer.signIn(login: String): StartedSession {
        val user =
            koin.get<ResolveUserUseCase>()(
                VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@$login.test", true, login, login = login)
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

    private suspend fun TestServer.patch(started: StartedSession?, slug: String, body: UpdateOrgRequest) =
        client.patch("/api/v1/orgs/$slug") {
            started?.let { session(it) }
            json(body)
        }

    private suspend fun TestServer.delete(started: StartedSession, slug: String, confirm: String) =
        client.delete("/api/v1/orgs/$slug") {
            session(started)
            json(DeleteOrgRequest(confirm))
        }

    private suspend fun TestServer.createOrg(started: StartedSession, name: String): OrgResponse =
        client
            .post("/api/v1/orgs") {
                session(started)
                json(CreateOrgRequest(name, null))
            }.body()

    private suspend fun TestServer.createWithShortName(started: StartedSession, slug: String) =
        client.post("/api/v1/orgs") {
            session(started)
            json(CreateOrgRequest("Acme", slug))
        }

    // Bob joins Acme as a member through a link.
    private suspend fun TestServer.join(owner: StartedSession, slug: String, member: StartedSession) {
        val link =
            client
                .post("/api/v1/orgs/$slug/invitations/links") {
                    session(owner)
                    json(CreateLinkRequest())
                }.body<InvitationLinkResponse>()
        client.post("/api/v1/invitations/accept") {
            session(member)
            json(InvitationTokenRequest(link.url.substringAfter("#token=")))
        }
    }

    private suspend fun HttpResponse.assertRefused(expected: HttpStatusCode, type: String) {
        assertEquals(expected, status)
        val error = Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(type, error.getValue("type").jsonPrimitive.content)
    }

    // A check across organizations and modules, as no request does: the superuser's pool.
    private suspend fun TestServer.count(sql: String): Long =
        database.superuser.fetchAll(sql).getOrThrow().single().get("n").asLong()

    @Test
    fun anOwnerOrAdminSetsTheTimeZoneAndTheShortNameNeverChanges() =
        serverTest(modules, overrides) { server ->
            val ada = server.signIn("ada")
            val bob = server.signIn("bob")
            val acme = server.createOrg(ada, "Acme")
            assertEquals("UTC", acme.timeZone, "UTC until set")
            server.join(ada, "acme", bob)

            val zoned = server.patch(ada, "acme", UpdateOrgRequest(timeZone = "Europe/Paris"))
            assertEquals(HttpStatusCode.OK, zoned.status)
            assertEquals(acme.copy(timeZone = "Europe/Paris"), zoned.body<OrgResponse>())
            assertEquals(
                "Europe/Paris",
                server.client.get("/api/v1/orgs/acme") { session(ada) }.body<OrgResponse>().timeZone,
            )
            assertEquals(
                listOf("Europe/Paris"),
                server.client.get("/api/v1/orgs") { session(ada) }.body<OrgsResponse>().items.map { it.timeZone },
            )
            val both = server.patch(ada, "acme", UpdateOrgRequest("Acme Labs", "Asia/Tokyo", "acme"))
            assertEquals("Acme Labs" to "Asia/Tokyo", both.body<OrgResponse>().let { it.name to it.timeZone })

            server.patch(ada, "acme", UpdateOrgRequest(slug = "acme-labs"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_request")
            server.patch(ada, "acme", UpdateOrgRequest(timeZone = "Mars/Olympus"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_request")
            server.patch(ada, "acme", UpdateOrgRequest(timeZone = "+02:00"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_request")
            server.patch(bob, "acme", UpdateOrgRequest(timeZone = "UTC"))
                .assertRefused(HttpStatusCode.Forbidden, "forbidden")
            assertEquals(HttpStatusCode.Unauthorized, server.patch(null, "acme", UpdateOrgRequest("X")).status)
            assertEquals(HttpStatusCode.NotFound, server.patch(ada, "nobody", UpdateOrgRequest("X")).status)
            val after = server.client.get("/api/v1/orgs/acme") { session(ada) }.body<OrgResponse>()
            assertEquals(Triple("acme", "Acme Labs", "Asia/Tokyo"), Triple(after.slug, after.name, after.timeZone))
            assertEquals(
                3L,
                server.count(
                    "SELECT count(*) AS n FROM identity_audit_events WHERE org_id = '${acme.id}' " +
                        "AND action IN ('org.renamed', 'org.time_zone.changed')"
                ),
                "Paris, then Tokyo and the name: three changes",
            )
        }

    @Test
    fun anOwnerOrAdminSetsHowLongTheLauncherWaitsForTheHooks() =
        serverTest(modules, overrides) { server ->
            val ada = server.signIn("ada")
            val bob = server.signIn("bob")
            val acme = server.createOrg(ada, "Acme")
            assertEquals(15, acme.hooksDeadlineSeconds, "15 seconds until set")
            server.join(ada, "acme", bob)

            val slower = server.patch(ada, "acme", UpdateOrgRequest(hooksDeadlineSeconds = 45))
            assertEquals(HttpStatusCode.OK, slower.status)
            assertEquals(acme.copy(hooksDeadlineSeconds = 45), slower.body<OrgResponse>())
            assertEquals(
                45,
                server.client.get("/api/v1/orgs/acme") { session(ada) }.body<OrgResponse>().hooksDeadlineSeconds,
            )
            assertEquals(
                listOf(45),
                server.client.get("/api/v1/orgs") { session(ada) }.body<OrgsResponse>().items
                    .map { it.hooksDeadlineSeconds },
            )
            server.patch(ada, "acme", UpdateOrgRequest(hooksDeadlineSeconds = 4))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_request")
            server.patch(ada, "acme", UpdateOrgRequest(hooksDeadlineSeconds = 301))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_request")
            server.patch(bob, "acme", UpdateOrgRequest(hooksDeadlineSeconds = 20))
                .assertRefused(HttpStatusCode.Forbidden, "forbidden")
            val after = server.client.get("/api/v1/orgs/acme") { session(ada) }.body<OrgResponse>()
            assertEquals(45, after.hooksDeadlineSeconds, "A refused change changes nothing")
            assertEquals(
                1L,
                server.count(
                    "SELECT count(*) AS n FROM identity_audit_events WHERE org_id = '${acme.id}' " +
                        "AND action = 'org.hooks_deadline.changed'"
                ),
            )
            assertEquals(
                1L,
                server.count("SELECT count(*) AS n FROM orgs_organizations WHERE hooks_deadline_seconds = 45"),
            )
        }

    @Test
    fun anotherOrganizationNeitherChangesNorDeletesIt() =
        serverTest(modules, overrides) { server ->
            val ada = server.signIn("ada")
            val mallory = server.signIn("mallory")
            server.createOrg(ada, "Acme")
            server.createOrg(mallory, "Evil")

            val change = server.patch(mallory, "acme", UpdateOrgRequest(timeZone = "UTC"))
            assertEquals(HttpStatusCode.NotFound, change.status)
            assertEquals(HttpStatusCode.NotFound, server.delete(mallory, "acme", "acme").status)
            val acme = server.client.get("/api/v1/orgs/acme") { session(ada) }.body<OrgResponse>()
            assertEquals("Acme" to "UTC", acme.name to acme.timeZone)
            server.settle()
            assertEquals(emptyList(), told)
        }

    private fun OrgResponse.uuid(): Uuid = Uuid.parse(id)

    private suspend fun TestServer.settle(): DeletionPass = koin.get<SettleOrgDeletionsUseCase>()()

    @Test
    fun theOwnerDeletesWithAFreshSignInThenTheOrganizationIsErasedAfterThirtyDaysAndItsAuditStays() =
        serverTest(modules, overrides) { server ->
            val ada = server.signIn("ada")
            val bob = server.signIn("bob")
            val acme = server.createOrg(ada, "Acme")
            server.join(ada, "acme", bob)

            server.delete(bob, "acme", "acme").assertRefused(HttpStatusCode.Forbidden, "forbidden")
            clock.now += 5.minutes + 1.minutes
            val stale = server.delete(ada, "acme", "acme")
            stale.assertRefused(HttpStatusCode.Forbidden, "step_up_required")
            assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/orgs/acme") { session(ada) }.status)
            assertEquals(DeletionPass(0, 0, 0), server.settle())

            val again = server.signIn("ada")
            server.delete(again, "acme", "Acme").assertRefused(HttpStatusCode.BadRequest, "invalid_request")
            assertEquals(HttpStatusCode.NoContent, server.delete(again, "acme", "acme").status)
            assertEquals(HttpStatusCode.NotFound, server.client.get("/api/v1/orgs/acme") { session(again) }.status)
            assertEquals(emptyList(), server.client.get("/api/v1/orgs") { session(bob) }.body<OrgsResponse>().items)

            // The module's worker tells every module within a pass, the first one at once.
            val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                OrgsModule.start(worker, server.koin)
                // Until the deletion is marked settled: the worker tells the modules, then marks it.
                val deletions = server.koin.get<OrgDeletionsRepository>()
                withContext(Dispatchers.Default) {
                    withTimeout(30.seconds) {
                        while (acme.uuid() in deletions.deletionsToSettle(clock.now, 10)) delay(20)
                    }
                }
            } finally {
                worker.cancel()
            }
            assertEquals(listOf("deleted ${acme.id}"), told)
            assertEquals(DeletionPass(0, 0, 0), server.settle(), "Told once")
            // Its short name stays taken while it may be restored: the same 409 as any name taken.
            server.createWithShortName(again, "acme").assertRefused(HttpStatusCode.Conflict, "conflict")
            clock.now += 30.days
            assertEquals(DeletionPass(settled = 0, purged = 1, failed = 0), server.settle())
            assertEquals(DeletionPass(0, 0, 0), server.settle())
            assertEquals(listOf("deleted ${acme.id}", "purge ${acme.id}"), told)

            assertEquals(
                1L,
                server.count(
                    "SELECT count(*) AS n FROM orgs_organizations WHERE id = '${acme.id}' " +
                        "AND name = '' AND slug = '~${acme.id}' AND purged_at IS NOT NULL"
                ),
                "A tombstone keeps a name nobody can choose",
            )
            val reborn = server.createWithShortName(server.signIn("ada"), "acme")
            assertEquals(HttpStatusCode.Created, reborn.status, "Erased, it gives its short name back")
            assertEquals("acme", reborn.body<OrgResponse>().slug)
            assertEquals(
                1L,
                server.count(
                    "SELECT count(*) AS n FROM identity_audit_events WHERE org_id = '${acme.id}' " +
                        "AND action = 'org.deleted'"
                ),
                "The audit is kept",
            )
        }
}
