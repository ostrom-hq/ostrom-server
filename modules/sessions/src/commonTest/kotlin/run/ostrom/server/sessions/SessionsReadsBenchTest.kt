// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions

import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.sessions.presentation.SessionsPage
import run.ostrom.server.testing.assertWithinBudget
import run.ostrom.server.testing.benchTest
import run.ostrom.server.testing.measureRead
import run.ostrom.server.testing.writeReadReport
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The sessions' read routes on 50 000 segments of 10 organizations: the list (its first page, an older page, one
 * person's, a person with 5 segments, the open ones, one repository's, a repository with 5 segments) and one segment.
 * Each keeps the read budget (ReadBench.kt) and returns the segments it should; its queries, plans and p95 are
 * written to `build/reports/perf/sessions.txt`.
 */
class SessionsReadsBenchTest {
    private val orgs = List(ORGS) { Uuid.random() }
    private val acme = orgs.first()

    // The token is the person's login: every caller is a person of acme.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) =
                    AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER))
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor =
                    GateActor.person(principal.subjectId, acme, OrgRole.OWNER)
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal =
                    OrgPrincipal(acme, Uuid.random(), OrgRole.OWNER)
            }
        }
    }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private fun sessionsOf(org: Uuid) =
        "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, started_at, " +
            "ended_at, last_seen_at, cost_usd) SELECT CAST('$org' AS uuid), 's' || i, 's' || i, 0, 'lch' || i, " +
            "'claude-code', CASE WHEN i % 1000 = 0 THEN 'ada' ELSE 'dev' || (i % 30) END, " +
            "CASE WHEN i % 1000 = 1 THEN 'acme/rare' ELSE 'acme/r' || (i % 20) END, " +
            "now() - i * INTERVAL '10 minutes', CASE WHEN i % 50 <> 0 THEN now() - i * INTERVAL '10 minutes' + " +
            "INTERVAL '5 minutes' END, now() - i * INTERVAL '10 minutes', 0.5 " +
            "FROM generate_series(1, $SESSIONS_PER_ORG) AS i"

    @Test
    fun theReadRoutesKeepTheirBudget() = benchTest(
        listOf(InstanceModule, ReceiptsModule, GatesModule, SessionsModule),
        overrides,
    ) { server ->
        val fixtures = server.database.superuser
        orgs.forEach { fixtures.execute(sessionsOf(it)).getOrThrow() }
        fixtures.execute("VACUUM ANALYZE sessions_sessions").getOrThrow()

        val reads = listOf(
            Read("/api/v1/sessions", "", PAGE),
            Read("/api/v1/sessions?before=s2500", "", PAGE),
            Read("/api/v1/sessions?dev=dev7", " (167 of 5 000)", PAGE, "sessions_by_dev"),
            Read("/api/v1/sessions?dev=ada", " (5 of 5 000)", RARE, "sessions_by_dev"),
            Read("/api/v1/sessions?open=true", " (100 of 5 000)", PAGE, "sessions_open"),
            Read("/api/v1/sessions?repo=acme/r3", " (250 of 5 000)", PAGE),
            // No index for the repository: its rarest value reads the organization's recent sessions.
            Read("/api/v1/sessions?repo=acme/rare", " (5 of 5 000, no index)", RARE),
            Read("/api/v1/sessions/s2500", " (one session)", null),
        )
        val measures = reads.map { read ->
            server.measureRead("GET ${read.path}${read.note}") { get(read.path) { bearerAuth("lyes") } }
        }
        writeReadReport("sessions", measures)
        measures.forEach { it.assertWithinBudget(setOf("sessions_sessions"), MAX_QUERIES) }
        reads.filter { it.items != null }.forEach { read ->
            val page = server.client.get(read.path) { bearerAuth("lyes") }.body<SessionsPage>()
            assertEquals(read.items, page.items.size, read.path)
        }
        // No N+1: a page of 50 sends the queries of a page of 5.
        val lists = reads.zip(measures).filter { (read, _) -> read.items != null }.map { it.second }
        assertEquals(1, lists.map { it.queries }.distinct().size, lists.joinToString("\n") { it.describe() })
        reads.zip(measures).forEach { (read, measure) ->
            read.index?.let { index -> assertTrue(measure.plans.any { index in it }, measure.describe()) }
        }
    }

    /**
     * A read of the bench.
     *
     * @property path The route and its filters.
     * @property note What it reads, for the report.
     * @property items The segments of its page; none for a single session.
     * @property index The index its plan must name (SessionsMigrations version 6), if any.
     */
    private data class Read(val path: String, val note: String, val items: Int?, val index: String? = null)

    private companion object {
        const val ORGS = 10

        // The queries of the busiest read today (one session, Build 41717982170):
        // one more is an N+1, on any runner.
        const val MAX_QUERIES = 2
        const val SESSIONS_PER_ORG = 5_000
        const val PAGE = 50
        const val RARE = 5
    }
}
