// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.RunnersSessionRoutes
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `runners_session_routes` under the serving role (RLS audit R2 and R3): a request reads and writes only its
 * organization's routes, which hold the hash of each runner token; the reconciler and the runner channel still reach
 * every session through the `ostrom_router` functions.
 */
class RunnersRoutesTenancyPostgresTest {
    private val t0 = Instant.parse("2026-11-02T10:00:00Z")

    @Test
    fun anotherOrganizationNeitherReadsNorRewritesARoute() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val ofA = session(createdAt = t0).copy(runnerTokenHash = "hash-a")
        repository.createWithinCap(ofA, 5)
        val jdbc = server.koin.get<JdbcDatabase>()
        val routes = RunnersSessionRoutes

        // The test server's pool is the serving role: without an organization, no route and no token hash at all.
        assertEquals(0L, jdbc.query { routes.selectAll().count() })
        assertEquals(1L, jdbc.inOrg(ORG_A) { routes.selectAll().count() })
        assertEquals(0L, jdbc.inOrg(ORG_B) { routes.selectAll().count() })

        // Organization B can neither swap A's runner token nor write a route in A's name.
        val swapped = jdbc.inOrg(ORG_B) {
            routes.update({ routes.sessionId eq ofA.id }) { it[routes.runnerTokenHash] = "b" }
        }
        assertEquals(0, swapped)
        assertFailsWith<Exception> {
            jdbc.inOrg(ORG_B) {
                routes.insert {
                    it[routes.sessionId] = Uuid.random()
                    it[routes.orgId] = ORG_A
                    it[routes.runnerTokenHash] = "forged"
                }
            }
        }

        // The runner channel and the reconciler still find A's session.
        assertEquals(ofA.id, repository.findByRunnerTokenHash("hash-a")?.id)
        assertEquals(ofA.id, repository.getById(ofA.id)?.id)
        assertEquals(listOf(ofA.id), repository.claimDue("r", t0, t0 + 60.seconds, 8).map { it.id })
    }
}
