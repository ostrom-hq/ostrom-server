// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.ORG_B
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.infrastructure.inTenant
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
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
        val count = Statement.create("SELECT COUNT(*) AS n FROM runners_session_routes")

        // The test server's driver is the serving role: without an organization, no route and no token hash at all.
        assertEquals(0L, server.driver.fetchAll(count).getOrThrow().first().get("n").asLong())
        assertEquals(1L, server.driver.inTenant(ORG_A) { fetchAll(count).getOrThrow().first().get("n").asLong() })
        assertEquals(0L, server.driver.inTenant(ORG_B) { fetchAll(count).getOrThrow().first().get("n").asLong() })

        // Organization B can neither swap A's runner token nor write a route in A's name.
        val swapped = server.driver.inTenant(ORG_B) {
            execute(
                Statement.create("UPDATE runners_session_routes SET runner_token_hash = 'b' WHERE session_id = :id")
                    .bind("id", ofA.id),
            ).getOrThrow()
        }
        assertEquals(0L, swapped)
        assertFailsWith<Exception> {
            server.driver.inTenant(ORG_B) {
                execute(
                    Statement.create(
                        "INSERT INTO runners_session_routes (session_id, org_id, runner_token_hash) " +
                            "VALUES (:id, :org, 'forged')",
                    ).bind("id", Uuid.random()).bind("org", ORG_A),
                ).getOrThrow()
            }
        }

        // The runner channel and the reconciler still find A's session.
        assertEquals(ofA.id, repository.findByRunnerTokenHash("hash-a")?.id)
        assertEquals(ofA.id, repository.getById(ofA.id)?.id)
        assertEquals(listOf(ofA.id), repository.claimDue("r", t0, t0 + 60.seconds, 8).map { it.id })
    }
}
