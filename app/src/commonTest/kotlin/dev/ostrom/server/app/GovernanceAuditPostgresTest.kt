// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.OrgAuditPageResponse
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every module together: the governance writes of the console (a budget, the model policy, the MCP settings) land in
 * the organization's audit log, each with the person who made it and the credential they used.
 */
class GovernanceAuditPostgresTest {
    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
        header(HttpHeaders.Origin, CONSOLE_ORIGIN)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun theConsolesGovernanceWritesAreAudited() = serverTest(serverModules()) { server ->
        val user = server.koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, "ada", "ada@acme.test", true, "ada", null, login = "ada"),
        )
        val ada = server.koin.get<StartWebSessionUseCase>()(user.id)
        val writes = listOf(
            server.client.post("/api/v1/orgs") {
                session(ada)
                json("""{"name":"Acme"}""")
            },
            server.client.put("/api/v1/budgets") {
                session(ada)
                json("""{"scope":"organization","usdPerMonth":100.0}""")
            },
            server.client.put("/api/v1/policy/models") {
                session(ada)
                json("{}")
            },
            server.client.put("/api/v1/mcp/settings") {
                session(ada)
                json("""{"agentPrefix":"acme"}""")
            },
        )
        writes.forEach { assertEquals(true, it.status.value in SUCCESS, "${it.call.request.url}: ${it.status}") }

        val log = server.client.get("/api/v1/audit") { session(ada) }
        assertEquals(HttpStatusCode.OK, log.status)
        val items = log.body<OrgAuditPageResponse>().items
        assertEquals(
            listOf("mcp.settings.changed", "inference.policy.changed", "budget.changed", "org.created"),
            items.map { it.event },
        )
        assertEquals(setOf("ada"), items.map { it.actor }.toSet())
        assertEquals(listOf("browser", "browser", "browser"), items.take(3).map { it.meta["credential"] })
        assertEquals(listOf("settings:mcp", "policy:models", "budget:organization"), items.take(3).map { it.subject })
        // The org's limit: its amount, and no member written as the text "null".
        val budgetMeta = mapOf("credential" to "browser", "scope" to "organization", "usd_per_month" to "100.0")
        assertEquals(budgetMeta, items[2].meta)
    }

    private companion object {
        const val CONSOLE_ORIGIN = "http://localhost:5173"
        val SUCCESS = 200..299
    }
}
