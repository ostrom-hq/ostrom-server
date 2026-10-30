// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.kernel.http.HealthResponse
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every registered module together, on a real PostgreSQL. */
class ServerPostgresTest {
    @Test
    fun everyModuleMigratesAndTheServerIsHealthy() = serverTest(serverModules()) { server ->
        val response = server.client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapOf("database" to "ok"), response.body<HealthResponse>().checks)
    }

    @Test
    fun moduleNamesAreUnique() {
        val names = serverModules().map { it.name }
        assertEquals(names.size, names.toSet().size, "Every module has its own name")
    }
}
