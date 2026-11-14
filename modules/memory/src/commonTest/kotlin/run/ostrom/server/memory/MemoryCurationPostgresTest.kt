// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.core.api.models.MemoryCuration
import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemoryReviews
import run.ostrom.core.api.models.SupersededMemories
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.kernel.database.inOrg
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryCurationRuns
import run.ostrom.server.memory.domain.MemoryCurationService
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryProposalWaits
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.NewMemory
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.BEN
import run.ostrom.server.memory.fakes.Callers
import run.ostrom.server.memory.fakes.EVE
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.Tokens
import run.ostrom.server.memory.fakes.caller
import run.ostrom.server.memory.infrastructure.supersede
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import run.ostrom.core.api.models.MemoryReview as MemoryReviewDto

/**
 * The curation on a real PostgreSQL (TeamCode #273): off until an administrator turns it on, never mixing scopes, the
 * newest entry winning when it is safe, a person deciding otherwise, restorable, and the retention of every
 * organization.
 */
class MemoryCurationPostgresTest {
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<CallerResolver> { Callers }
        single<MemoryProposalWaits> { FakeProposalWaits() }
        // The instance module's lease is not under test here: no pass is due.
        single<ScheduledWork> {
            object : ScheduledWork {
                override suspend fun runDue(
                    name: String,
                    interval: Duration,
                    timeout: Duration,
                    block: suspend () -> Unit,
                ) = false
            }
        }
    }

    private fun curationTest(block: suspend Fixture.() -> Unit) =
        serverTest(listOf(MemoryModule), overrides) { server -> Fixture(server).block() }

    private class Fixture(val server: TestServer) {
        val client: HttpClient = server.client
        val repository = server.koin.get<MemoryRepository>()
        val curation = server.koin.get<MemoryCurationService>()
        val runs = server.koin.get<MemoryCurationRuns>()
        private var minutesAgo = 600

        /** An entry written [MINUTE] after the one before, so that the newest of a pair is known. */
        suspend fun note(
            title: String,
            content: String = "Connect to the VPN first.",
            by: MemoryCaller = caller(),
            scope: MemoryScope = MemoryScope.PERSONAL,
            tags: List<String> = emptyList(),
            origin: MemoryOrigin = MemoryOrigin.HUMAN,
        ): Uuid {
            val id = repository.create(by, NewMemory(scope, title, "About $title", content, tags), origin, null).id
            minutesAgo--
            sql("UPDATE memory_entries SET updated_at = now() - INTERVAL '$minutesAgo minutes' WHERE id = '$id'")
            return id
        }

        suspend fun sql(statement: String) {
            server.database.superuser.execute(statement).getOrThrow()
        }

        suspend fun count(where: String): Int =
            server.database.superuser.fetchAll("SELECT id FROM memory_entries WHERE $where").getOrThrow().toList().size

        suspend fun index(token: String): List<String> =
            client.get("$MEMORY/index") { bearerAuth(token) }.body<MemoryIndexPage>().entries.map { it.id }

        suspend fun settings(token: String): HttpResponse = client.get("$MEMORY/curation") { bearerAuth(token) }

        suspend fun enable(token: String, enabled: Boolean): HttpResponse = client.put("$MEMORY/curation") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"enabled": $enabled}""")
        }

        suspend fun reviews(token: String) =
            client.get("$MEMORY/reviews") { bearerAuth(token) }.body<MemoryReviews>().reviews

        suspend fun answer(token: String, id: String, verb: String): HttpResponse =
            client.post("$MEMORY/reviews/$id/$verb") { bearerAuth(token) }

        suspend fun superseded(token: String) =
            client.get("$MEMORY/superseded") { bearerAuth(token) }.body<SupersededMemories>().entries

        suspend fun restore(token: String, id: Uuid): HttpResponse =
            client.post("$MEMORY/$id/restore") { bearerAuth(token) }
    }

    private suspend fun HttpResponse.errorType(): String = body<ApiError>().error.type

    @Test
    fun equalEntriesAreSupersededOnlyOnceAnAdministratorTurnsTheCurationOn() = curationTest {
        val dup1 = note("Deploy needs a VPN")
        val dup2 = note("deploy  needs a vpn", "connect to the   VPN first.")
        assertEquals(0, curation.run("run-0").scopes, "off by default")
        assertEquals(false, settings("root").body<MemoryCuration>().enabled)
        for (token in listOf("ada", "adakey", "agent")) {
            assertEquals(HttpStatusCode.Forbidden, settings(token).status, token)
            assertEquals(HttpStatusCode.Forbidden, enable(token, true).status, token)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("$MEMORY/curation").status)
        assertEquals(true, enable("root", true).body<MemoryCuration>().enabled)

        // Two equal personal entries: the newest wins, the older is kept as superseded, its owner's alone.
        assertEquals(1, curation.run("run-1").superseded)
        assertEquals(listOf(dup2.toString()), index("ada"))
        val replaced = superseded("ada").single()
        assertEquals(dup1.toString() to dup2.toString(), replaced.id to replaced.supersededById)
        assertTrue(superseded("root").isEmpty() && superseded("ben").isEmpty(), "nobody else sees it")
        assertEquals(HttpStatusCode.NotFound, restore("root", dup1).status)
        assertEquals(HttpStatusCode.NotFound, restore("ben", dup1).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("$MEMORY/superseded") { bearerAuth("adakey") }.status)
        assertEquals(0, curation.run("run-2").scopes, "nothing changed since: the scope is not read again")
        val last = settings("root").body<MemoryCuration>()
        assertEquals(Triple("run-1", 1, 0), Triple(last.lastRunId, last.lastSuperseded, last.lastQueued))

        // The same text in two members' personal memories is never compared.
        note("Deploy needs a VPN", by = caller(BEN))
        assertEquals(0, curation.run("run-3").superseded)

        // Restoring: the entry comes back, and the one that replaced it is superseded in turn.
        val restored = restore("ada", dup1)
        assertEquals("active" to dup2.toString(), restored.body<MemoryEntry>().let { it.status to it.supersedesId })
        assertEquals(listOf(dup1.toString()), index("ada"))
        assertEquals(listOf(dup2.toString()), superseded("ada").map { it.id })
        assertEquals(HttpStatusCode.NotFound, restore("ada", dup1).status)
        assertEquals(HttpStatusCode.NotFound, client.post("$MEMORY/not-an-id/restore") { bearerAuth("ada") }.status)
    }

    @Test
    fun aPersonDecidesWhatCurationCannotAndAPairIsQueuedOnce() = curationTest {
        enable("root", true)
        val old = note("Staging URL", "It is staging.example.com.")
        val updated = note("Staging URL", "It moved to stg.example.com.")
        assertEquals(1, curation.run("run-1").queued)
        val review = reviews("ada").single()
        val pair = listOf(review.kind, review.olderId, review.newerId)
        assertEquals(listOf("same_title", old.toString(), updated.toString()), pair)
        assertTrue(reviews("root").isEmpty() && reviews("ben").isEmpty(), "a personal review is its owner's")
        assertEquals(HttpStatusCode.Forbidden, client.get("$MEMORY/reviews") { bearerAuth("agent") }.status)
        note("Later", "Anything.")
        assertEquals(0, curation.run("run-2").queued, "a pair is queued once")
        assertEquals(HttpStatusCode.NotFound, answer("root", review.id, "apply").status)
        assertEquals(HttpStatusCode.NotFound, answer("ada", "not-an-id", "apply").status)
        assertEquals(HttpStatusCode.NotFound, answer("ada", Uuid.random().toString(), "dismiss").status)
        assertEquals(old.toString(), answer("ada", review.id, "apply").body<MemoryReviewDto>().olderId)
        assertTrue(old.toString() !in index("ada"))
        assertEquals("memory_review_closed", answer("ada", review.id, "dismiss").errorType())

        // Dismissed: both stay, and the pair is never queued again.
        val vim = note("Editor", "Vim.")
        note("Editor", "Emacs.")
        curation.run("run-3")
        assertEquals(HttpStatusCode.OK, answer("ada", reviews("ada").single().id, "dismiss").status)
        sql("DELETE FROM memory_curation_state")
        assertEquals(0, curation.run("run-4").queued)
        assertTrue(vim.toString() in index("ada"))

        // A person's older entry against an agent's newer one, or anything tagged external, is queued, never applied.
        val human = note("Branch naming", "Use feat/ prefixes.")
        note("Branch naming", "Use feat/ prefixes.", caller(agent = true), origin = MemoryOrigin.AGENT)
        val outside = note("Release freeze", "No Friday releases.", scope = MemoryScope.TEAM, tags = listOf("external"))
        note("Release freeze", "No Friday releases.", scope = MemoryScope.TEAM)
        val run = curation.run("run-5")
        assertEquals(0 to 2, run.superseded to run.queued)
        val personal = reviews("ada").single { it.olderId == human.toString() }
        val team = reviews("root").single { it.scope == "team" }
        assertEquals(outside.toString() to "duplicate", team.olderId to team.kind)
        assertTrue(reviews("ada").none { it.scope == "team" }, "the organization's reviews are its administrators'")

        // A pair whose entry is no longer active is stale: dismiss it.
        sql("UPDATE memory_entries SET status = 'rejected' WHERE id = '${team.newerId}'")
        assertEquals("memory_review_stale", answer("root", team.id, "apply").errorType())
        sql("UPDATE memory_entries SET status = 'rejected' WHERE id = '$human'")
        assertEquals("memory_review_stale", answer("ada", personal.id, "apply").errorType())
        assertEquals(HttpStatusCode.OK, answer("ada", personal.id, "dismiss").status)
    }

    @Test
    fun aTeamEntryIsRestoredByItsAuthorOrAnAdministratorWithinTheLimit() = curationTest {
        enable("root", true)
        val older = note("Deploy order", by = caller(), scope = MemoryScope.TEAM, tags = listOf("deploy"))
        val newer = note("Deploy order", by = caller(BEN), scope = MemoryScope.TEAM)
        curation.run("run-1")
        assertEquals(listOf(older.toString()), superseded("ada").map { it.id }, "its author's")
        assertEquals(listOf(older.toString()), superseded("root").map { it.id })
        assertTrue(superseded("ben").isEmpty())
        assertEquals(HttpStatusCode.Forbidden, restore("ben", older).status)

        // Its replacement is gone: nothing leaves to make room, so the restore counts against the limit.
        repository.delete(caller(BEN), newer) {}
        val restored = restore("root", older).body<MemoryEntry>()
        assertEquals("active" to listOf("deploy"), restored.status to restored.tags)
        sql("UPDATE memory_entries SET status = 'superseded' WHERE id = '$older'")
        sql(
            "INSERT INTO memory_entries (id, org_id, scope, owner_id, title, summary, content, refs, status, origin, " +
                "author_id, version, created_at, updated_at) SELECT gen_random_uuid(), '$ORG_A', 'team', '', 'n', " +
                "'s', 'c', '[]', 'active', 'human', '$ADA', 1, now(), now() FROM generate_series(1, 5000)",
        )
        assertEquals("memory_limit", restore("ada", older).errorType())

        // A change since curation read the entry wins: the entry is left as it is.
        val now = Clock.System.now()
        assertTrue(server.driver.inOrg(ORG_A) { supersede(ORG_A, older, 99, newer, now) }.isEmpty())
    }

    @Test
    fun theRetentionKeepsEveryOrganizationCurationOnOrOff() = curationTest {
        val soon = repository.create(
            caller(),
            NewMemory(MemoryScope.PERSONAL, "Short lived", "s", "c", expiresAt = Clock.System.now() + 1.minutes),
            MemoryOrigin.HUMAN,
            null,
        ).id
        val recent = note("Recently expired")
        val old = note("Old superseded")
        val eves = note("Eve's", by = caller(EVE, ORG_B))
        sql("UPDATE memory_entries SET expires_at = now() - INTERVAL '8 days' WHERE id = '$soon'")
        sql("UPDATE memory_entries SET expires_at = now() - INTERVAL '1 day' WHERE id = '$recent'")
        sql("UPDATE memory_entries SET status = 'superseded', updated_at = now() - INTERVAL '91 days' " +
            "WHERE id = '$old'")
        assertEquals(setOf(ORG_A, ORG_B), runs.organizations(null, 10).toSet())
        val first = runs.organizations(null, 1).single()
        assertEquals(setOf(ORG_A, ORG_B) - first, runs.organizations(first, 10).toSet(), "by id, after the cursor")

        val report = curation.run("run-1")
        assertEquals(1 to 0, report.expired to report.scopes)
        assertEquals(0, count("id IN ('$soon', '$old')"))
        assertEquals(2, count("id IN ('$recent', '$eves')"), "an entry expired recently is only hidden")

        // An answered review is kept 30 days.
        enable("root", true)
        note("Pair", "One.")
        note("Pair", "Two.")
        curation.run("run-2")
        answer("ada", reviews("ada").single().id, "dismiss")
        sql("UPDATE memory_reviews SET resolved_at = now() - INTERVAL '31 days'")
        curation.run("run-3")
        assertEquals(
            0,
            server.database.superuser.fetchAll("SELECT id FROM memory_reviews").getOrThrow().toList().size,
        )

        // Turned off again: nothing more is read; the organization's deletion takes its setting with it.
        enable("root", false)
        note("Late", "Same.")
        note("Late", "Same.")
        assertEquals(0, curation.run("run-4").scopes)
        server.koin.get<OrgDeletionListener>(named("memory")).purge(ORG_A)
        val left = server.database.superuser.fetchAll(
            "SELECT org_id FROM memory_settings UNION ALL SELECT org_id FROM memory_curation_state",
        ).getOrThrow().toList()
        assertTrue(left.isEmpty())
    }

    private companion object {
        const val MEMORY = "/api/v1/memory"
    }
}
