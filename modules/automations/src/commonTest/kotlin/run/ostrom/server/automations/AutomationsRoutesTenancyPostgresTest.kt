// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.Automation
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.ORG_B
import run.ostrom.server.automations.fakes.cronDefinition
import run.ostrom.server.automations.infrastructure.AutomationsRoutes
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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
        val jdbc = server.koin.get<JdbcDatabase>()
        val routes = AutomationsRoutes

        // The test server's pool is the serving role: without an organization, no route at all.
        assertEquals(0L, jdbc.query { routes.selectAll().count() })
        assertEquals(1L, jdbc.inOrg(ORG_A) { routes.selectAll().count() })
        assertEquals(0L, jdbc.inOrg(ORG_B) { routes.selectAll().count() })

        // Organization B can neither reschedule A's automation nor write a route in A's name.
        val moved = jdbc.inOrg(ORG_B) {
            routes.update({ routes.automationId eq ofA.id }) { it[routes.nextRunAt] = t0 }
        }
        assertEquals(0, moved)
        assertFailsWith<Exception> {
            jdbc.inOrg(ORG_B) {
                routes.insert {
                    it[routes.automationId] = ofA.id
                    it[routes.orgId] = ORG_A
                }
            }
        }

        // A webhook still finds the automation in its organization.
        assertEquals(ofA, repository.getById(ofA.id))
    }
}
