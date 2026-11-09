// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.auth.GovernanceConfirmations
import run.ostrom.server.kernel.auth.GovernanceConfirmer
import run.ostrom.server.kernel.auth.GovernancePreview
import run.ostrom.server.kernel.auth.GovernanceRequest
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.confirmsByConsole
import run.ostrom.server.kernel.auth.holdForConfirmation
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** A token's governance write is held for a confirmation on the console; without the governance module, refused. */
class GovernanceConfirmationsTest {
    private val org = Uuid.random()

    private val principals = mapOf(
        "elevated" to AuthenticatedPrincipal("ada", PrincipalKind.USER, GovernanceScopes.ALL),
        "device" to AuthenticatedPrincipal("ada", PrincipalKind.USER, setOf("rules:read")),
        "browser" to AuthenticatedPrincipal(
            "ada",
            PrincipalKind.USER,
            setOf(ALL_USER_SCOPES),
            channel = PrincipalChannel.BROWSER,
        ),
    )

    /** A governance route: a token's write is held, a browser's is made (counted). */
    private inner class WritesModule : ServerModule {
        override val name: String = "writes"
        override val koinModule: Module = module {}
        var writes = 0

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                post("/rules/{id}") {
                    if (call.requirePrincipal().confirmsByConsole) {
                        val payload = JsonObject(mapOf("id" to JsonPrimitive(call.parameters["id"])))
                        val write = GovernanceWrite("rules.update", payload)
                        call.holdForConfirmation(org, GovernanceScopes.POLICY_WRITE, write)
                    } else {
                        writes++
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }

    private fun server(
        writes: WritesModule,
        confirmations: GovernanceConfirmations?,
        block: suspend (suspend (String) -> HttpResponse) -> Unit,
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
                    confirmations?.let { value -> single { value } }
                },
            )
        }.koin
        application { configureHttp(koin, listOf(writes)) }
        block { token -> client.post("/rules/r1") { bearerAuth(token) } }
    }

    @Test
    fun aTokensWriteOpensAConfirmationAndWritesNothingWhileTheBrowsersIsMade() {
        val opened = mutableListOf<GovernanceRequest>()
        val confirmations = object : GovernanceConfirmations {
            override suspend fun open(request: GovernanceRequest): GovernanceConfirmationPending {
                opened += request
                return GovernanceConfirmationPending("gcf_1", URL, EXPIRES)
            }
        }
        val writes = WritesModule()
        server(writes, confirmations) { post ->
            for (token in listOf("elevated", "device")) {
                val held = post(token)
                assertEquals(HttpStatusCode.Accepted, held.status, token)
                val expected = """{"confirmationId":"gcf_1","confirmUrl":"$URL","expiresAt":"$EXPIRES"}"""
                assertEquals(expected, held.bodyAsText())
            }
            assertEquals(HttpStatusCode.NoContent, post("browser").status)
        }
        assertEquals(1, writes.writes)
        val first = opened.first()
        assertEquals(listOf(true, false), opened.map { GovernanceScopes.POLICY_WRITE in it.principal.scopes })
        assertEquals(org, first.orgId)
        assertEquals("policy:write", first.scope)
        assertEquals("POST", first.method)
        assertEquals("/rules/r1", first.path)
        assertEquals(GovernanceWrite("rules.update", JsonObject(mapOf("id" to JsonPrimitive("r1")))), first.write)
    }

    @Test
    fun withoutTheGovernanceModuleATokenWritesNothingAndTheBrowserStillDoes() {
        val writes = WritesModule()
        server(writes, null) { post ->
            val refused = post("elevated")
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertTrue("sign in to the console" in refused.bodyAsText())
            assertEquals(HttpStatusCode.NoContent, post("browser").status)
        }
        assertEquals(1, writes.writes)
    }

    @Test
    fun onlyAPersonsApiTokenWaitsForTheConsole() {
        assertTrue(principals.getValue("elevated").confirmsByConsole)
        assertTrue(principals.getValue("device").confirmsByConsole)
        assertFalse(principals.getValue("browser").confirmsByConsole)
        val app = AuthenticatedPrincipal("ada", PrincipalKind.USER, emptySet(), channel = PrincipalChannel.MOBILE_APP)
        assertFalse(app.confirmsByConsole)
        for (kind in listOf(PrincipalKind.AGENT, PrincipalKind.SERVICE, PrincipalKind.DEVICE)) {
            assertFalse(AuthenticatedPrincipal("x", kind, GovernanceScopes.ALL).confirmsByConsole, kind.name)
        }
    }

    @Test
    fun aChangeIsShownBeforeAndAfterThenMadeAsItsConfirmersDecision() {
        val member = OrgPrincipal(org, Uuid.random(), OrgRole.ADMIN)
        val confirmer = GovernanceConfirmer(principals.getValue("browser"), member)
        val preview = GovernancePreview("Set the gate policy", JsonPrimitive("sole-approver"), JsonPrimitive("never"))
        assertEquals("Set the gate policy", preview.summary)
        assertEquals(JsonPrimitive("sole-approver"), preview.before)
        assertEquals(JsonPrimitive("never"), preview.after)
        assertEquals(PrincipalChannel.BROWSER, confirmer.principal.channel)
        assertEquals(OrgRole.ADMIN, confirmer.member.role)
        assertEquals(org, confirmer.member.orgId)
    }

    @Test
    fun aChangeKeepsItsWholeResultUnlessItSaysWhatItsSecretIs() {
        val change = object : GovernanceChange {
            override val kinds: Set<String> = setOf("gates.policy")

            override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview =
                GovernancePreview("Set it", null, null)

            override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer) = null
        }
        assertNull(change.shownOnce("gates.policy", JsonPrimitive("never")))
    }

    private companion object {
        const val EXPIRES = "2026-11-02T20:15:00Z"
        const val URL = "https://app.test/confirm/gcf_1"
    }
}
