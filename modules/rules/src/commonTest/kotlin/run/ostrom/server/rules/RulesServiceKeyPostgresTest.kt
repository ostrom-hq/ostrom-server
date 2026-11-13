// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.ServiceKeyAudit
import run.ostrom.server.kernel.auth.ServiceKeyCall
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.rules.domain.repositories.RulesRepository
import run.ostrom.server.rules.fakes.RulesCallers
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.RulesFixtures.orgA
import run.ostrom.server.rules.fakes.RulesFixtures.orgB
import run.ostrom.server.rules.fakes.TestClock
import run.ostrom.server.rules.fakes.call
import run.ostrom.server.rules.presentation.GatePolicyDto
import run.ostrom.server.rules.presentation.NewRuleRequest
import run.ostrom.server.rules.presentation.RuleDto
import run.ostrom.server.rules.presentation.RulesPage
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A service key reads the rules for its organization's CI, on a real PostgreSQL and real HTTP: its own organization
 * only, whatever `Ostrom-Org` names, with `rules:read`, each call audited in its name; nothing else of the rules
 * routes. The keys are no members: no caller resolves for them, as the orgs module answers for a key.
 */
class RulesServiceKeyPostgresTest {
    private val clock = TestClock()
    private val calls = mutableListOf<ServiceKeyCall>()

    // token -> (organization, scopes): what the identity module authenticates for an `oss_` key.
    private val keys = mapOf(
        "key-a" to (orgA to setOf("rules:read")),
        "key-b" to (orgB to setOf("rules:read")),
        "key-a-ci" to (orgA to setOf("sessions:write")),
    )

    private val overrides = module {
        // The gates' open sessions and receipts, as the other rules tests load them with the compiler.
        includes(RulesCallers.gateSessions)
        single<Clock> { clock }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = keys[token]?.let {
                    AuthenticatedPrincipal(token, PrincipalKind.SERVICE, it.second, token, it.first.toString())
                }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? = null
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? = null
            }
        }
        single<ServiceKeyAudit> {
            object : ServiceKeyAudit {
                override suspend fun record(call: ServiceKeyCall) {
                    calls += call
                }
            }
        }
    }

    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)

    private fun keysTest(block: suspend (TestServer) -> Unit) =
        serverTest(modules, overrides) { server -> block(server) }

    private suspend fun TestServer.rules() {
        val repository = koin.get<RulesRepository>()
        repository.transaction(orgA) {
            insertRule(RulesFixtures.rule("rule_1"))
            insertRule(RulesFixtures.rule("rule_2", group = "Releases"))
        }
        repository.transaction(orgB) { insertRule(RulesFixtures.rule("rule_9", orgB)) }
    }

    @Test
    fun aKeyReadsTheRulesOfItsOwnOrganizationEachCallAudited() = keysTest { server ->
        server.rules()
        val listed = server.call("GET", "/api/v1/rules?group=Releases", "key-a")
        assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
        assertEquals(listOf("rule_2"), listed.body<RulesPage>().items.map { it.id })
        val read = server.call("GET", "/api/v1/rules/rule_1", "key-a")
        assertEquals("rule_1", read.body<RuleDto>().id)
        assertEquals(
            listOf(
                ServiceKeyCall("key-a", orgA, "listRules", "/api/v1/rules"),
                ServiceKeyCall("key-a", orgA, "getRule", "/api/v1/rules/rule_1"),
            ),
            calls,
        )
    }

    @Test
    fun aKeyOfOneOrganizationReachesNothingOfAnother() = keysTest { server ->
        server.rules()
        val own = server.call("GET", "/api/v1/rules", "key-b").body<RulesPage>()
        assertEquals(listOf("rule_9"), own.items.map { it.id })
        assertEquals(HttpStatusCode.NotFound, server.call("GET", "/api/v1/rules/rule_1", "key-b").status)
        // Naming the other organization changes nothing: a key acts in its own.
        val named = server.client.get("/api/v1/rules") {
            bearerAuth("key-b")
            header(ORG_HEADER, orgA.toString())
        }
        assertEquals(listOf("rule_9"), named.body<RulesPage>().items.map { it.id })
        assertTrue(calls.all { it.orgId == orgB && it.keyId == "key-b" })
    }

    @Test
    fun aKeyWithoutTheScopeOrWithoutAKeyIsRefusedUnaudited() = keysTest { server ->
        server.rules()
        assertEquals(HttpStatusCode.Forbidden, server.call("GET", "/api/v1/rules", "key-a-ci").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("GET", "/api/v1/rules/rule_1", "key-a-ci").status)
        assertEquals(HttpStatusCode.Unauthorized, server.call("GET", "/api/v1/rules", null).status)
        assertEquals(HttpStatusCode.Unauthorized, server.call("GET", "/api/v1/rules/rule_1", "revoked").status)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun aKeyNeverWritesDecidesNorReadsTheDecisionsOrThePolicy() = keysTest { server ->
        server.rules()
        val refused = listOf(
            server.call("POST", "/api/v1/rules", "key-a", NewRuleRequest("Ship", "Releases")),
            server.call("PATCH", "/api/v1/rules/rule_1", "key-a", buildJsonObject { put("statement", "x") }),
            server.call("DELETE", "/api/v1/rules/rule_1", "key-a"),
            server.call("POST", "/api/v1/rules/bulk", "key-a", buildJsonObject { put("status", "accepted") }),
            server.call("PUT", "/api/v1/gates/policy", "key-a", GatePolicyDto(selfApproval = "never")),
        )
        assertTrue(refused.all { it.status == HttpStatusCode.Forbidden }, refused.map { it.status }.toString())
        // A key is no member: the reads of the console answer as for an unknown organization.
        assertEquals(HttpStatusCode.NotFound, server.call("GET", "/api/v1/decisions", "key-a").status)
        assertEquals(HttpStatusCode.NotFound, server.call("GET", "/api/v1/gates/policy", "key-a").status)
        val rules = server.call("GET", "/api/v1/rules", "key-a").body<RulesPage>().items
        assertEquals(listOf("rule_1", "rule_2"), rules.map { it.id }.sorted())
        assertEquals(1, calls.size, "Only the read it may make is audited")
    }
}
