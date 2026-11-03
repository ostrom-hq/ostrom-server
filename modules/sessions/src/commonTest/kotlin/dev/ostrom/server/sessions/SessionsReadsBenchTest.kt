// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The sessions' read routes on 50 000 segments of 10 organizations: the list (its first page, an older page, one
 * person's, a person with 5 segments, the open ones, one repository's) and one segment. Each keeps the read budget
 * (ReadBench.kt); its queries, plans and p95 are written to `build/reports/perf/sessions.txt`.
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

        // Path, what it reads, and the index its plan must name (SessionsMigrations version 6), if any.
        val reads = listOf(
            Triple("/api/v1/sessions", "", null),
            Triple("/api/v1/sessions?before=s2500", "", null),
            Triple("/api/v1/sessions?dev=dev7", " (167 of 5 000)", "sessions_by_dev"),
            Triple("/api/v1/sessions?dev=ada", " (5 of 5 000)", "sessions_by_dev"),
            Triple("/api/v1/sessions?open=true", " (100 of 5 000)", "sessions_open"),
            Triple("/api/v1/sessions?repo=acme/r3", "", null),
            Triple("/api/v1/sessions?repo=acme/rare", " (5 of 5 000, no index)", null),
            Triple("/api/v1/sessions/s2500", " (one session)", null),
        )
        val measures = reads.map { (path, note, _) ->
            server.measureRead("GET $path$note") { get(path) { bearerAuth("lyes") } }
        }
        writeReadReport("sessions", measures)
        measures.forEach { it.assertWithinBudget(setOf("sessions_sessions")) }
        // No N+1: a page of 50 sends the queries of a page of 5.
        val lists = measures.dropLast(1)
        assertEquals(1, lists.map { it.queries }.distinct().size, lists.joinToString("\n") { it.describe() })
        reads.zip(measures).forEach { (read, measure) ->
            read.third?.let { index -> assertTrue(measure.plans.any { index in it }, measure.describe()) }
        }
    }

    private companion object {
        const val ORGS = 10
        const val SESSIONS_PER_ORG = 5_000
    }
}
