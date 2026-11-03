// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import dev.ostrom.server.app.serverModules
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.LayeredConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.server.testing.ApplicationTestBuilder
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The isolation of organizations, route by route (CONVENTIONS.md section 16, AUDIT-SECU-ISOLATION): every route the
 * server mounts has a case in [ISOLATION_REGISTRY], and no credential of an organization A reads, changes, deletes, or
 * learns the existence of a resource of an organization B, by its id, a guessed id, a list or a total.
 */
class IsolationMatrixPostgresTest {
    // The artifact pages are mounted on their content origin, and the secrets key is given rather than written to a
    // file of the working directory.
    private val overrides = module {
        single<ConfigSource> {
            val matrix = MapConfigSource(
                mapOf(
                    "OSTROM_ARTIFACTS_CONTENT_ORIGIN" to "http://$CONTENT_HOST",
                    "OSTROM_SECRETS_KEY" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                ),
            )
            LayeredConfigSource(listOf(matrix, EnvironmentConfigSource()))
        }
    }

    @Test
    fun everyMountedRouteHasItsCaseInTheIsolationRegistry() = serverTest(serverModules(), overrides) {
        val mounted = application.mountedRoutes()
        val missing = mounted - ISOLATION_REGISTRY.keys
        val stale = ISOLATION_REGISTRY.keys - mounted
        assertTrue(
            missing.isEmpty() && stale.isEmpty(),
            "Routes without a case (add one to IsolationRegistry.kt):\n${missing.joinToString("\n")}\n" +
                "Cases without a route:\n${stale.joinToString("\n")}",
        )
    }

    @Test
    fun noCredentialOfAnOrganizationReadsChangesOrDetectsAnothersResources() =
        benchTest(serverModules(), overrides) { server ->
            val a = server.party("a")
            val b = server.party("b")
            server.seed(a, spends = false)
            server.seed(b, spends = true)
            val matrix = IsolationMatrix(direct(), ISOLATION_REGISTRY, b.values, b.canaries())
            val changes = matrix.watch(b.browser) { matrix.run(a.credentials) }
            report(a.problems + b.problems + matrix.findings + changes, matrix)
        }

    @Test
    fun aSimpleMemberMakesNoneOfTheWritesReservedToOwnersAndAdmins() =
        benchTest(serverModules(), overrides) { server ->
            val a = server.party("a")
            server.seed(a, spends = false)
            val matrix = IsolationMatrix(direct(), ISOLATION_REGISTRY, a.values, emptySet())
            val findings = mutableListOf<String>()
            val changes = matrix.watch(a.browser) {
                findings += matrix.adminWrites(ISOLATION_REGISTRY, a.memberBrowser)
            }
            report(a.problems + findings + changes, matrix)
        }

    @Test
    fun anAgentTokenReachesNothingOfAnotherLaunch() = benchTest(serverModules(), overrides) { server ->
        val a = server.party("a")
        server.seed(a, spends = false)
        val other = server.anotherLaunch(a)
        val agent = a.credentials.filter { it.name == "agent token" }
        val cases = casesNaming(ISOLATION_REGISTRY, LAUNCH_SEEDS)
        val matrix = IsolationMatrix(direct(), cases, a.values + other, other.values.toSet())
        val changes = matrix.watch(a.browser) { matrix.run(agent) }
        val missing = LAUNCH_SEEDS.filter { it != Seed.APPROVAL && it != Seed.WAIT && it !in other }
        report(a.problems + missing.map { "second launch: no $it" } + matrix.findings + changes, matrix)
    }

    @Test
    fun aServiceKeyReachesOnlyTheRoutesOfItsScope() = benchTest(serverModules(), overrides) { server ->
        val a = server.party("a")
        server.seed(a, spends = false)
        val matrix = IsolationMatrix(direct(), ISOLATION_REGISTRY, a.values, emptySet())
        val anonymous = a.credentials.first()
        val findings = SERVICE_KEY_ROUTES.keys.flatMap { scope ->
            val secret = server.token(a.owner.user, a.org, TokenKind.SERVICE, setOf(scope)).second
            val key = Credential("service key ($scope)") { bearerAuth(secret) }
            matrix.serviceKeyReach(ISOLATION_REGISTRY, scope, key, anonymous)
        }
        report(a.problems + findings, matrix)
    }

    // The matrix reads redirections as answers: following one to GitHub would leave the test server.
    private fun ApplicationTestBuilder.direct(): HttpClient = createClient { followRedirects = false }

    private fun report(findings: List<String>, matrix: IsolationMatrix) = assertTrue(
        findings.isEmpty(),
        "${findings.size} findings in ${matrix.requests} requests:\n${findings.joinToString("\n")}",
    )
}
