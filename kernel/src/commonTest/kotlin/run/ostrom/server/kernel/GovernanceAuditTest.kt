// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceAudit
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.auditGovernance
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/** A token's governance write leaves its trace before it happens; without an audit log, it does not happen. */
class GovernanceAuditTest {
    private val principals = mapOf(
        "elevated" to AuthenticatedPrincipal("ada", PrincipalKind.USER, GovernanceScopes.ALL),
        "browser" to AuthenticatedPrincipal(
            "ada",
            PrincipalKind.USER,
            setOf(ALL_USER_SCOPES),
            channel = PrincipalChannel.BROWSER,
        ),
    )

    /** A governance route: records, then writes (counted). */
    private class WritesModule : ServerModule {
        override val name: String = "writes"
        override val koinModule: Module = module {}
        var writes = 0

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                post("/rules/{id}") {
                    call.auditGovernance(call.requirePrincipal(), GovernanceScopes.POLICY_WRITE)
                    writes++
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    private fun server(
        writes: WritesModule,
        audit: GovernanceAudit?,
        block: suspend (suspend (String) -> HttpStatusCode) -> Unit,
    ) = testApplication {
        val koin = koinApplication {
            modules(
                module {
                    single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                    single<TokenAuthenticator> {
                        object : TokenAuthenticator {
                            override suspend fun authenticate(token: String) = principals[token]
                        }
                    }
                    audit?.let { value -> single { value } }
                },
            )
        }.koin
        application { configureHttp(koin, listOf(writes)) }
        block { token -> client.post("/rules/r1") { bearerAuth(token) }.status }
    }

    @Test
    fun aTokensWriteIsRecordedFirstAndTheBrowsersIsLeftToItsSignedDecision() {
        val recorded = mutableListOf<String>()
        val audit = object : GovernanceAudit {
            override suspend fun record(principal: AuthenticatedPrincipal, scope: String, target: String) {
                recorded += "${principal.subjectId} $scope $target"
            }
        }
        val writes = WritesModule()
        server(writes, audit) { post ->
            assertEquals(HttpStatusCode.NoContent, post("elevated"))
            assertEquals(HttpStatusCode.NoContent, post("browser"))
        }
        assertEquals(listOf("ada policy:write POST /rules/r1"), recorded)
        assertEquals(2, writes.writes)
    }

    @Test
    fun withoutAnAuditLogATokenWritesNothingAndTheBrowserStillDoes() {
        val writes = WritesModule()
        server(writes, null) { post ->
            assertEquals(HttpStatusCode.Forbidden, post("elevated"))
            assertEquals(HttpStatusCode.NoContent, post("browser"))
        }
        assertEquals(1, writes.writes)
    }
}
