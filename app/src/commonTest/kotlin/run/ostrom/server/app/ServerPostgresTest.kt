// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app

import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.kernel.http.HealthResponse
import run.ostrom.server.sessions.domain.services.SessionCosts
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.infrastructure.LedgerModelUsageSink
import run.ostrom.server.usage.infrastructure.LedgerSessionCosts
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Every registered module together, on a real PostgreSQL. */
class ServerPostgresTest {
    @Test
    fun everyModuleMigratesAndTheServerIsHealthy() = serverTest(serverModules()) { server ->
        val response = server.client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapOf("database" to "ok"), response.body<HealthResponse>().checks)
        // The probes at their contract paths, so that their answers are checked against it (ContractObserver).
        assertEquals(HttpStatusCode.OK, server.client.get("/api/v1/health").status)
        assertEquals(HttpStatusCode.OK, server.client.get("/ready").status)
        // The ports that fall back to dropping everything are bound in the full server: the costs reach the ledger.
        assertIs<LedgerModelUsageSink>(server.koin.get<ModelUsageSink>())
        assertIs<LedgerSessionCosts>(server.koin.get<SessionCosts>())
    }

    @Test
    fun moduleNamesAreUnique() {
        val names = serverModules().map { it.name }
        assertEquals(names.size, names.toSet().size, "Every module has its own name")
    }
}
