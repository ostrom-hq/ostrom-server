// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.InvalidEmailException
import run.ostrom.server.kernel.module.TestAccountSeeder
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The QA account of `ostrom-server seed-test-account`: a person, their team and a token that works on the API. */
class TestAccountSeederPostgresTest {
    private val modules = listOf(IdentityModule, OrgsModule)

    @Test
    fun seedingCreatesAPersonTheirTeamAndAWorkingTokenAndSeedingAgainKeepsThem() = serverTest(modules) { server ->
        val seeder = server.koin.get<TestAccountSeeder>()

        val first = seeder.seed("  QA.Billing@Example.com ")
        val again = seeder.seed("qa.billing@example.com")

        assertEquals("qa.billing@example.com", first.email)
        val org = assertNotNull(first.organizationId)
        assertEquals(org, again.organizationId, "Seeding twice finds the same team")
        assertNotEquals(first.token, again.token, "Each seeding issues its own token")
        for (token in listOf(first.token, again.token)) {
            val orgs = server.client.get("/api/v1/orgs") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, orgs.status)
            assertTrue(org in orgs.bodyAsText())
        }
    }

    @Test
    fun anAddressThatIsNotOneIsRefused() = serverTest(modules) { server ->
        assertFailsWith<InvalidEmailException> { server.koin.get<TestAccountSeeder>().seed("not an address") }
    }
}
