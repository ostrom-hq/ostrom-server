// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.Automation
import dev.ostrom.server.automations.domain.AutomationsRepository
import dev.ostrom.server.automations.fakes.ALICE
import dev.ostrom.server.automations.fakes.ORG_A
import dev.ostrom.server.automations.fakes.ORG_B
import dev.ostrom.server.automations.fakes.cronDefinition
import dev.ostrom.server.automations.infrastructure.inTenant
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `automations_routes` under the serving role (RLS audit R2 and R3): a request reads and writes only its
 * organization's routes; a webhook and the scheduler still find any automation through the `ostrom_router` functions.
 */
class AutomationsRoutesTenancyPostgresTest {
    private val t0 = Instant.parse("2026-11-02T10:00:00Z")

    @Test
    fun anotherOrganizationNeitherReadsNorRewritesARoute() = serverTest(listOf(AutomationsModule)) { server ->
        val repository = server.koin.get<AutomationsRepository>()
        val ofA =
            Automation(Uuid.random(), ORG_A, ALICE.toString(), cronDefinition("A"), false, 1, 1, null, null, t0, t0)
        repository.createWithinLimit(ofA, 20)
        val count = Statement.create("SELECT COUNT(*) AS n FROM automations_routes")

        // The test server's driver is the serving role: without an organization, no route at all.
        assertEquals(0L, server.driver.fetchAll(count).getOrThrow().first().get("n").asLong())
        assertEquals(1L, server.driver.inTenant(ORG_A) { fetchAll(count).getOrThrow().first().get("n").asLong() })
        assertEquals(0L, server.driver.inTenant(ORG_B) { fetchAll(count).getOrThrow().first().get("n").asLong() })

        // Organization B can neither reschedule A's automation nor write a route in A's name.
        val moved = server.driver.inTenant(ORG_B) {
            execute(
                Statement.create("UPDATE automations_routes SET next_run_at = now() WHERE automation_id = :id")
                    .bind("id", ofA.id),
            ).getOrThrow()
        }
        assertEquals(0L, moved)
        assertFailsWith<Exception> {
            server.driver.inTenant(ORG_B) {
                execute(
                    Statement.create("INSERT INTO automations_routes (automation_id, org_id) VALUES (:id, :org)")
                        .bind("id", ofA.id).bind("org", ORG_A),
                ).getOrThrow()
            }
        }

        // A webhook still finds the automation in its organization.
        assertEquals(ofA, repository.getById(ofA.id))
    }
}
