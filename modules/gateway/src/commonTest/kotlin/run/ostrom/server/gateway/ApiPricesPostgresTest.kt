// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.presentation.ApiPriceDto
import run.ostrom.server.gateway.presentation.ApiPriceTableDto
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The org's price table over HTTP on PostgreSQL: who reads, who changes, what is kept per org, the audit. */
class ApiPricesPostgresTest : GatewayHttpFixture() {
    private val sonnet =
        ApiPriceDto("anthropic", "claude-sonnet-4", "2026-10-21", 3_000_000, 3_750_000, 300_000, 15_000_000)
    private val path = "/api/v1/policy/api-prices"

    @Test
    fun membersReadItAdministratorsReplaceItAndEachOrgKeepsItsOwn() = serverTest(modules, overrides) { server ->
        with(server) {
            val builtIn = client.get(path) { bearerAuth("member-a") }.body<ApiPriceTableDto>()
            assertEquals(true, builtIn.defaults)
            assertTrue(builtIn.prices.any { it.model == "claude-opus-5" && it.effectiveFrom == "2026-10-21" })
            for (token in listOf("agent-a", "policy-a")) {
                assertEquals(HttpStatusCode.OK, client.get(path) { bearerAuth(token) }.status, token)
            }
            for (token in listOf("reader-a", "use-a", "device-a")) {
                val status = client.get(path) { bearerAuth(token) }.status
                assertEquals(HttpStatusCode.Forbidden, status, token)
            }
            val table = ApiPriceTableDto(listOf(sonnet))
            // A member, a key (even inference:admin) and an agent do not change it; a stale sign-in must renew.
            for (token in listOf("member-a", "inference-admin-a", "agent-a", "stale-admin-a")) {
                assertEquals(HttpStatusCode.Forbidden, putJson(path, token, table).status, token)
            }
            val saved = putJson(path, "admin-a", table)
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals(ApiPriceTableDto(listOf(sonnet)), saved.body<ApiPriceTableDto>())
            val read = client.get(path) { bearerAuth("member-a") }.body<ApiPriceTableDto>()
            assertEquals(listOf(sonnet), read.prices)
            assertFalse(read.defaults == true)
            val other = client.get(path) { bearerAuth("admin-b") }.body<ApiPriceTableDto>()
            assertEquals(true, other.defaults, "Another org keeps the built-in prices")

            val free = ApiPriceTableDto(listOf(sonnet.copy(inputMicros = 0)))
            val refused = putJson(path, "admin-a", free)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertEquals("invalid_api_price", errorField(refused, "type"))
            val dated = ApiPriceTableDto(listOf(sonnet.copy(effectiveFrom = "21/10")))
            val badDate = putJson(path, "admin-a", dated)
            assertEquals("invalid_api_price", errorField(badDate, "type"))
            val kept = client.get(path) { bearerAuth("member-a") }.body<ApiPriceTableDto>()
            assertEquals(listOf(sonnet), kept.prices)

            val memberLoad = client.post("$path/load-defaults") { bearerAuth("member-a") }
            assertEquals(HttpStatusCode.Forbidden, memberLoad.status)
            val loaded = client.post("$path/load-defaults") { bearerAuth("admin-a") }
            assertEquals(HttpStatusCode.OK, loaded.status)
            val rows = loaded.body<ApiPriceTableDto>().prices
            assertTrue(sonnet in rows && rows.size > 1)
            assertNull(loaded.body<ApiPriceTableDto>().defaults)
            val again = client.post("$path/load-defaults") { bearerAuth("admin-a") }.body<ApiPriceTableDto>()
            assertEquals(rows, again.prices, "Loading twice changes nothing")
            val cleared = putJson(path, "admin-a", ApiPriceTableDto(emptyList()))
            assertEquals(emptyList(), cleared.body<ApiPriceTableDto>().prices)
            val back = client.get(path) { bearerAuth("member-a") }.body<ApiPriceTableDto>()
            assertEquals(true, back.defaults, "An empty table goes back to the built-in prices")
        }
    }
}
