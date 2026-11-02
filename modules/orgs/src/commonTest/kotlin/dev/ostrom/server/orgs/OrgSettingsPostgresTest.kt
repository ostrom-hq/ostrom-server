// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.module.OrgDeletionListener
import dev.ostrom.server.orgs.domain.DeletionPass
import dev.ostrom.server.orgs.domain.OrgDeletionsRepository
import dev.ostrom.server.orgs.domain.SettleOrgDeletionsUseCase
import dev.ostrom.server.orgs.fakes.SettableClock
import dev.ostrom.server.orgs.presentation.CreateLinkRequest
import dev.ostrom.server.orgs.presentation.CreateOrgRequest
import dev.ostrom.server.orgs.presentation.DeleteOrgRequest
import dev.ostrom.server.orgs.presentation.InvitationLinkResponse
import dev.ostrom.server.orgs.presentation.InvitationTokenRequest
import dev.ostrom.server.orgs.presentation.OrgResponse
import dev.ostrom.server.orgs.presentation.OrgsResponse
import dev.ostrom.server.orgs.presentation.UpdateOrgRequest
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
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
            clock.now += 30.days
            assertEquals(DeletionPass(settled = 0, purged = 1, failed = 0), server.settle())
            assertEquals(DeletionPass(0, 0, 0), server.settle())
            assertEquals(listOf("deleted ${acme.id}", "purge ${acme.id}"), told)

            assertEquals(
                1L,
                server.count(
                    "SELECT count(*) AS n FROM orgs_organizations WHERE id = '${acme.id}' " +
                        "AND name = '' AND slug = 'acme' AND purged_at IS NOT NULL"
                ),
                "A tombstone keeps the short name taken",
            )
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
