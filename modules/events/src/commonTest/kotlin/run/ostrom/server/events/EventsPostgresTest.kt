// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventPage
import run.ostrom.server.events.domain.OpenEventFeedUseCase
import run.ostrom.server.events.domain.OpenEventFeedUseCaseImpl
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.events.infrastructure.EventsPurgeWorker
import run.ostrom.server.events.infrastructure.JournalOrgEventPublisher
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: the journal, its sequence, its reads, its isolation, its purge and its stream. */
class EventsPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val adminA = Uuid.parse("0000000a-0000-0000-0000-0000000000a1")
    private val miaA = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")

    /** Each token's organization, member and role; a token absent here belongs to no organization. */
    private val members = mapOf(
        "admin-a" to OrgPrincipal(orgA, adminA, OrgRole.ADMIN),
        "mia-a" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
        "revoked-a" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
        "agent-a" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
    )

    private fun principal(token: String): AuthenticatedPrincipal? = when (token) {
        "agent-a" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf("agent:session"))
        "revoked-a" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("cloud:read"), credentialId = "gone")
        "admin-a", "mia-a", "stranger" ->
            AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER)
        else -> null
    }

    private fun overrides(instantPolls: Boolean = false): Module = module {
        // The webhooks the module also serves seal their secrets; the feed never opens one.
        single<SecretCipher> { ContextCipher() }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = principal(token)

                override suspend fun isLive(subjectId: String, credentialId: String): Boolean = credentialId != "gone"
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    members[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
        single<MemberLogins> {
            object : MemberLogins {
                override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

                override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
                    mapOf(miaA to "mia", adminA to "tom").filterKeys { it in memberIds }
            }
        }
        if (instantPolls) single<OpenEventFeedUseCase> { OpenEventFeedUseCaseImpl(get()) { yield() } }
    }

    private val modules = listOf(EventsModule)

    private fun draft(type: OrgEventType, subject: String, member: Uuid?, data: JsonObject = JsonObject(emptyMap())) =
        OrgEventDraft(type, subject, member, data)

    private suspend fun TestServer.publish(org: Uuid, vararg events: OrgEventDraft) =
        koin.get<OrgEventPublisher>().publish(org, events.toList())

    @Test
    fun publishersShareOneGapFreeSequencePerOrganization() = serverTest(modules, overrides()) { server ->
        val publisher = server.koin.get<OrgEventPublisher>()
        val events = server.koin.get<OrgEventsRepository>()
        assertTrue(publisher is JournalOrgEventPublisher, "The module binds it")
        assertEquals(0, events.head(orgA))
        assertNull(events.oldest(orgA))

        publisher.publish(orgA, emptyList())
        server.publish(
            orgA,
            draft(OrgEventType.WAIT_CREATED, "w-1", miaA, JsonObject(mapOf("title" to JsonPrimitive("Deploy?")))),
            draft(OrgEventType.WORKFLOW_RUN_UPDATED, "r-1", null),
        )
        // In the transaction of the change it reports: rolled back with it, committed with it.
        assertFailsWith<IllegalStateException> {
            server.driver.withOrganization(orgA) { tx ->
                publisher.publishIn(tx, orgA, listOf(draft(OrgEventType.TODO_UPDATED, "s-0", miaA)))
                error("The change fails")
            }
        }
        server.driver.withOrganization(orgA) { tx ->
            publisher.publishIn(tx, orgA, emptyList())
            publisher.publishIn(tx, orgA, listOf(draft(OrgEventType.TODO_UPDATED, "s-1", adminA)))
        }
        server.publish(orgB, draft(OrgEventType.WAIT_RESOLVED, "w-9", null))

        assertEquals(3, events.head(orgA), "The rolled-back event took no sequence")
        assertEquals(1, events.oldest(orgA))
        assertEquals(1, events.head(orgB), "Each organization counts on its own")
        // TeamCode #238: an admin reads the organization's events and their own, never another member's.
        val all = events.read(orgA, EventAudience(adminA, true), 0, null, 100)
        assertEquals(listOf(2L, 3L), all.events.map { it.seq }, "Not Mia's")
        assertEquals(3, all.through)
        assertNull(all.events.first().memberId)
        assertTrue(all.events.none { it.subjectId == "s-0" })

        val mine = events.read(orgA, EventAudience(miaA, false), 0, null, 100)
        assertEquals(listOf(1L), mine.events.map { it.seq }, "A member reads only their own events")
        assertEquals(3, mine.through, "and moves past the others")
        assertEquals("""{"title":"Deploy?"}""", mine.events.first().data)
        assertEquals("wait", mine.events.first().subject)
        assertEquals(miaA, mine.events.first().memberId)
        val types = setOf("cloud.todo", "cloud.wait.resolved")
        val todos = events.read(orgA, EventAudience(adminA, true), 0, types, 100)
        assertEquals(listOf(3L), todos.events.map { it.seq })
        val first = events.read(orgA, EventAudience(adminA, true), 0, null, 1)
        assertEquals(PageSummary(listOf(2L), 2, true), first.summary())
        val past = events.read(orgA, EventAudience(adminA, true), 3, null, 2)
        assertEquals(PageSummary(emptyList(), 3, false), past.summary())

        val tooLong = JsonObject(mapOf("text" to JsonPrimitive("x".repeat(5_000))))
        assertFailsWith<IllegalArgumentException> {
            server.publish(orgA, draft(OrgEventType.TODO_UPDATED, "s-2", miaA, tooLong))
        }
        assertEquals(3, events.head(orgA))
    }

    @Test
    fun anApproverReadsTheApprovalEventsThatNameThemWhateverTheirRole() = serverTest(modules, overrides()) { server ->
        val events = server.koin.get<OrgEventsRepository>()
        fun approval(type: OrgEventType, subject: String, approver: String) =
            draft(type, subject, null, JsonObject(mapOf("approver" to JsonPrimitive(approver))))
        server.publish(
            orgA,
            approval(OrgEventType.APPROVAL_REQUESTED, "r-1", "Mia"),
            approval(OrgEventType.APPROVAL_REQUESTED, "r-2", "tom"),
            approval(OrgEventType.APPROVAL_DECIDED, "r-1", "mia"),
            draft(OrgEventType.WAIT_RESOLVED, "w-1", null, JsonObject(mapOf("approver" to JsonPrimitive("mia")))),
        )
        val mia = events.read(orgA, EventAudience(miaA, false, "mia"), 0, null, 100)
        assertEquals(listOf("r-1", "r-1"), mia.events.map { it.subjectId }, "Hers, in any case, and no one else's")
        assertEquals(listOf(1L, 3L), mia.events.map { it.seq })
        val unknown = events.read(orgA, EventAudience(miaA, false, null), 0, null, 100)
        assertTrue(unknown.events.isEmpty(), "Without a login, none")
        val admin = events.read(orgA, EventAudience(adminA, true, "tom"), 0, null, 100)
        assertEquals(4, admin.events.size, "An administrator reads the whole organization's, as before")
    }

    @Test
    fun anOrganizationNeverReadsAnothersJournal() = serverTest(modules, overrides()) { server ->
        server.publish(
            orgA,
            draft(OrgEventType.WAIT_CREATED, "w-1", miaA),
            draft(OrgEventType.WAIT_CREATED, "w-2", null),
        )
        for (table in listOf("events_journal", "events_cursors")) {
            val seen = server.driver.withOrganization(orgB) { tx ->
                tx.fetchAll(Statement.create("SELECT count(*) AS n FROM $table")).getOrThrow().first().get("n").asLong()
            }
            assertEquals(0, seen, "Row-level security confines $table to the transaction's organization")
        }
        val events = server.koin.get<OrgEventsRepository>()
        assertEquals(0, events.head(orgB))
        assertTrue(events.read(orgB, EventAudience(adminA, true), 0, null, 100).events.isEmpty())
    }

    @Test
    fun oldEventsArePurgedAndADeletedOrganizationIsErased() = serverTest(modules, overrides()) { server ->
        server.publish(
            orgA,
            draft(OrgEventType.WAIT_CREATED, "w-1", miaA),
            draft(OrgEventType.WAIT_CREATED, "w-2", miaA),
        )
        server.publish(orgB, draft(OrgEventType.WAIT_CREATED, "w-3", null))
        server.database.superuser.execute(
            "UPDATE events_journal SET at = at - INTERVAL '8 days' WHERE org_id = '$orgA' AND seq = 1",
        )
            .getOrThrow()
        val events = server.koin.get<OrgEventsRepository>()

        val before = Clock.System.now() - 7.days
        assertEquals(1, events.purge(before, 1_000), "The serving role purges across organizations")
        assertEquals(2, events.oldest(orgA))
        assertEquals(2, events.head(orgA), "The cursor stays: the sequence never goes back")
        assertEquals(1, events.oldest(orgB), "A recent event stays")
        server.koin.get<EventsPurgeWorker>().pass()

        server.koin.get<OrgDeletionListener>(named("events")).purge(orgA)
        assertEquals(0, events.head(orgA))
        assertNull(events.oldest(orgA))
        assertEquals(1, events.head(orgB))
    }

    @Test
    fun theModuleServesTheFeedAndRefusesBeforeStreaming() = serverTest(modules, overrides()) { server ->
        assertEquals(setOf(ServerFeatures.EVENTS, ServerFeatures.WEBHOOKS), EventsModule.features(server.koin))
        server.publish(orgA, draft(OrgEventType.WAIT_CREATED, "w-1", miaA))

        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/events").status)
        assertEquals(HttpStatusCode.Forbidden, server.client.get("/api/v1/events") { bearerAuth("agent-a") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("/api/v1/events") { bearerAuth("stranger") }.status)
        for (query in listOf("after=x", "after=-1", "after=2", "types=cloud.unknown")) {
            val refused = server.client.get("/api/v1/events?$query") { bearerAuth("mia-a") }
            assertEquals(HttpStatusCode.BadRequest, refused.status, query)
            assertTrue("invalid_" in refused.bodyAsText(), query)
        }
        val badHeader = server.client.get("/api/v1/events?after=0") {
            bearerAuth("mia-a")
            header("Last-Event-ID", "nope")
        }
        assertEquals(HttpStatusCode.BadRequest, badHeader.status, "The header wins over the query")
    }

    @Test
    fun theStreamSendsTheVisibleEventsThenWhatComes() = serverTest(modules, overrides()) { server ->
        server.publish(
            orgA,
            draft(OrgEventType.WAIT_CREATED, "w-1", miaA),
            draft(OrgEventType.WAIT_CREATED, "w-2", adminA),
        )
        server.client.prepareGet("/api/v1/events?after=0") { bearerAuth("mia-a") }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
            assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                assertEquals(listOf("retry: 2000"), stream.frame())
                val first = stream.frame()
                assertEquals(listOf("id: 1", "event: cloud.wait.created"), first.take(2))
                assertTrue("\"subjectId\":\"w-1\"" in first[2], first[2])
                server.publish(orgA, draft(OrgEventType.WAIT_RESOLVED, "w-2", adminA))
                server.publish(orgA, draft(OrgEventType.WAIT_RESOLVED, "w-1", miaA))
                assertEquals(listOf("id: 4", "event: cloud.wait.resolved"), stream.frame().take(2), "Not the admin's")
            }
        }
        // An approver who is no administrator reads the approval event that names their login, in the stream too.
        server.publish(
            orgA,
            draft(
                OrgEventType.APPROVAL_REQUESTED,
                "r-1",
                null,
                JsonObject(mapOf("approver" to JsonPrimitive("Mia"))),
            ),
        )
        server.client.prepareGet("/api/v1/events?after=4") { bearerAuth("mia-a") }.execute { response ->
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                stream.frame()
                assertEquals(listOf("id: 5", "event: cloud.approval.requested"), stream.frame().take(2))
            }
        }
        server.client.prepareGet("/api/v1/events") {
            bearerAuth("admin-a")
            header("Last-Event-ID", "1")
        }.execute { response ->
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                stream.frame()
                // TeamCode #238: their own events only, Mia's 1 and 4 never.
                assertEquals("id: 2", stream.frame().first(), "An admin resumes after its cursor, with its own")
                assertEquals("id: 3", stream.frame().first())
            }
        }
    }

    @Test
    fun aPurgedCursorGetsAGapAndARevokedCredentialADeniedEnd() = serverTest(modules, overrides(true)) { server ->
        server.publish(
            orgA,
            draft(OrgEventType.WAIT_CREATED, "w-1", miaA),
            draft(OrgEventType.WAIT_CREATED, "w-2", miaA),
        )
        server.publish(orgA, draft(OrgEventType.WAIT_CREATED, "w-3", miaA))
        server.database.superuser.execute("DELETE FROM events_journal WHERE seq < 3").getOrThrow()
        server.client.prepareGet("/api/v1/events?after=1") { bearerAuth("revoked-a") }.execute { response ->
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                assertEquals(listOf("retry: 2000"), stream.frame())
                assertEquals(listOf("event: gap", """data: {"oldestSeq":3}"""), stream.frame())
                assertEquals("id: 3", stream.frame().first())
                assertEquals(listOf("event: denied", "data: {}"), stream.frame(), "The credential was revoked")
                assertNull(stream.readUTF8Line(), "and the stream ends")
            }
        }
    }

    @Test
    fun aQuietStreamSendsKeepAlives() = serverTest(modules, overrides(true)) { server ->
        server.client.prepareGet("/api/v1/events?after=0") { bearerAuth("mia-a") }.execute { response ->
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                do {
                    val line = stream.readUTF8Line()
                } while (line != ": keep-alive")
            }
        }
    }

    /** One SSE frame's lines, skipping keep-alive comments. */
    private suspend fun ByteReadChannel.frame(): List<String> {
        while (true) {
            val lines = mutableListOf<String>()
            while (true) {
                val line = requireNotNull(readUTF8Line()) { "The stream ended" }
                if (line.isEmpty()) break
                lines += line
            }
            if (lines.any { !it.startsWith(":") }) return lines
        }
    }

    private data class PageSummary(val seqs: List<Long>, val through: Long, val more: Boolean)

    private fun EventPage.summary() =
        PageSummary(events.map { it.seq }, through, more)
}
