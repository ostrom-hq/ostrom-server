// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts

import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.receipts.domain.models.ReceiptAudience
import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.services.ReceiptOwners
import run.ostrom.server.receipts.domain.services.ReceiptReaders
import run.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import run.ostrom.server.receipts.infrastructure.ReceiptsQueries
import run.ostrom.server.receipts.presentation.ReceiptsPage
import run.ostrom.server.receipts.presentation.VerificationResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.benchTest
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The receipts routes read as TeamCode reads a session's (#238, parity TC-NEW-675a530-2): a receipt about a session
 * by its person, the owners and administrators, and the approvers of its gates; any other member gets a 404 and lists
 * only what they read, and a cursor naming a receipt they do not read is a 400 as a forged one.
 */
class ReceiptReadersPostgresTest {
    // `lyes` and `mia` own a session each, `rev` approves a gate of Lyes's, `root` is an administrator of A.
    private val people = mapOf(
        "lyes" to ReceiptReader(ORG_A, "lyes", readsAll = false),
        "mia" to ReceiptReader(ORG_A, "mia", readsAll = false),
        "rev" to ReceiptReader(ORG_A, "rev", readsAll = false),
        "root" to ReceiptReader(ORG_A, "root", readsAll = true),
        "other" to ReceiptReader(ORG_B, "other", readsAll = true),
    )
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) =
                    AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
            }
        }
        single<ReceiptReaders> { FakeReaders(approvals = mapOf("rev" to listOf("s-lyes")), readers = people) }
        single<ReceiptOwners> { FakeOwners(mapOf("s-lyes" to "lyes", "s-mia" to "mia")) }
    }

    private fun readersTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(InstanceModule, ReceiptsModule), overrides) { server -> block(server) }

    private suspend fun TestServer.call(token: String?, path: String): HttpResponse = client.get(path) {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun TestServer.ids(token: String, query: String = ""): List<String> =
        call(token, "/api/v1/receipts$query").body<ReceiptsPage>().items.map { it.text("id") }

    @Test
    fun aSessionsReceiptIsServedToItsPersonTheAdministratorsAndItsApproversOnly() = readersTest { server ->
        val sign = server.koin.get<SignReceiptUseCase>()
        val lyes = sign(ORG_A, draft("s-lyes", "session")).text("id")
        val gate = sign(ORG_A, draft("gate-1", session = "s-lyes")).text("id")
        val mia = sign(ORG_A, draft("s-mia", "session")).text("id")
        val harness = sign(ORG_A, draft("harness-1", "harness")).text("id")

        for (path in listOf("/api/v1/receipts/$lyes", "/api/v1/receipts/$lyes/verify")) {
            for (reader in listOf("lyes", "rev", "root")) {
                assertEquals(HttpStatusCode.OK, server.call(reader, path).status, "$reader: $path")
            }
            assertEquals(HttpStatusCode.NotFound, server.call("mia", path).status, "another member: $path")
            assertEquals(HttpStatusCode.NotFound, server.call("other", path).status, "another organization: $path")
            assertEquals(HttpStatusCode.NotFound, server.call("nobody", path).status, "no organization: $path")
            assertEquals(HttpStatusCode.Unauthorized, server.call(null, path).status, "no credential: $path")
        }
        assertTrue(server.call("rev", "/api/v1/receipts/$lyes/verify").body<VerificationResponse>().ok)
        assertEquals(lyes, server.call("lyes", "/api/v1/receipts/$lyes").body<JsonObject>().text("id"))
        assertEquals(HttpStatusCode.OK, server.call("mia", "/api/v1/receipts/$harness").status, "The organization's")

        assertEquals(listOf(harness, mia, gate, lyes), server.ids("root"))
        assertEquals(listOf(harness, gate, lyes), server.ids("lyes"))
        assertEquals(listOf(harness, gate, lyes), server.ids("rev"))
        assertEquals(listOf(harness, mia), server.ids("mia"))
        assertEquals(listOf(gate), server.ids("lyes", "?limit=1&before=$harness"))
        assertEquals(listOf(lyes), server.ids("rev", "?subjectKind=session"))
        assertEquals(emptyList(), server.ids("other"))
        assertEquals(HttpStatusCode.Unauthorized, server.call(null, "/api/v1/receipts").status)
        assertEquals(HttpStatusCode.NotFound, server.call("nobody", "/api/v1/receipts").status)

        // Another's receipt as a cursor names nothing the member reads: a 400, as a forged one.
        assertEquals(HttpStatusCode.BadRequest, server.call("mia", "/api/v1/receipts?before=$lyes").status)
        assertEquals(HttpStatusCode.BadRequest, server.call("mia", "/api/v1/receipts?before=rcpt_forged").status)
        assertEquals(listOf(mia), server.ids("mia", "?before=$harness"))
    }

    @Test
    fun aMembersPageReadsTheRowsItServes() = benchTest(listOf(InstanceModule, ReceiptsModule), overrides) { server ->
        // Fixtures only: 20 000 receipts of B, 10 000 of A (one session in 10 is Mia's, one in 50 has a gate she
        // approves, one receipt in 20 is about no session).
        val fixtures = server.database.superuser
        fixtures.execute(seed(ORG_B, 20_000)).getOrThrow()
        fixtures.execute(seed(ORG_A, 10_000)).getOrThrow()
        fixtures.execute("VACUUM ANALYZE receipts_receipts").getOrThrow()
        val approved = (0 until 200).map { "s-${it * 50 + 1}" }
        val mia = ReceiptAudience("mia", approved)
        val nobody = ReceiptAudience(null, emptyList())
        val plans = linkedMapOf(
            "newest page of an administrator" to server.driver.plan(ReceiptQuery()),
            "newest page of a member" to server.driver.plan(ReceiptQuery(audience = mia)),
            "a member's page by its cursor" to server.driver.plan(ReceiptQuery(before = "rcpt_5000", audience = mia)),
            "a member's sessions receipts" to server.driver.plan(ReceiptQuery(subjectKind = "session", audience = mia)),
            "a member whose login is unknown" to server.driver.plan(ReceiptQuery(audience = nobody)),
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        println("-- receipts plans\n$report")
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on receipts_receipts" in plan, "$name scans the table:\n$plan")
            val filtered = Regex("Rows Removed by Filter: (\\d+)").findAll(plan).sumOf { it.groupValues[1].toInt() }
            assertTrue(filtered < MAX_FILTERED, "$name filters $filtered rows of the organization:\n$plan")
        }
    }

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, count: Int): String =
        "INSERT INTO receipts_receipts (org_id, id, subject_kind, subject_id, body, created_at, session_id, member) " +
            "SELECT CAST('$org' AS uuid), 'rcpt_' || i, CASE WHEN i % 20 = 0 THEN 'harness' ELSE 'session' END, " +
            "'s-' || i, '{}', now() - make_interval(secs => i), CASE WHEN i % 20 = 0 THEN NULL ELSE 's-' || i END, " +
            "CASE WHEN i % 20 = 0 THEN NULL WHEN i % 10 = 3 THEN 'mia' ELSE 'dev-' || (i % 7) END " +
            "FROM generate_series(1, $count) AS i"

    private suspend fun Driver.plan(query: ReceiptQuery): String = transaction {
        fetchAll(
            Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", ORG_A.toString()),
        ).getOrThrow()
        val statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) ${ReceiptsQueries.list(query)}")
            .bind("org", ORG_A).bind("limit", ReceiptQuery.DEFAULT_PAGE)
        query.subjectKind?.let { statement.bind("kind", it) }
        query.before?.let { statement.bind("before", it) }
        query.audience?.member?.let { statement.bind("member", it) }
        query.audience?.let { audience ->
            statement.bind("sessions", audience.sessions.joinToString(",", "[", "]") { "\"$it\"" })
        }
        fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
    }

    private companion object {
        /** At most a few pages of rows read and dropped by a filter: never the organization's rows one by one. */
        const val MAX_FILTERED = 1_000
    }
}
