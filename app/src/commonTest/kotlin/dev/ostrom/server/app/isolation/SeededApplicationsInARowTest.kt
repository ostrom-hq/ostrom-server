// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import dev.ostrom.server.app.serverModules
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.config.LayeredConfigSource
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.testing.benchTest
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A server started after others in the same process still answers (P1, server#247 runs 41702574486 and 41709000222).
 *
 * On Kotlin/Native, after two isolation benches, a third test application never answered a request: each seeding
 * request waited its full 10 s while the database stayed idle and the test reached it directly, then the test thread
 * itself stopped for good. The JVM answers every one. These tests start the same seeded applications one after the
 * other, printing each request first, so the last printed line names the first one left without an answer.
 */
class SeededApplicationsInARowTest {
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
    fun seededApplicationsInARowEachAnswerEveryRequest() = repeat(ROUNDS) { round ->
        benchTest(serverModules(), overrides) { server ->
            val party = server.party("r$round")
            party.trace = ::println
            server.seed(party, spends = false)
            println("round $round seeded")
            assertTrue(party.problems.isEmpty(), "round $round:\n${party.problems.joinToString("\n")}")
        }
    }

    @Test
    fun applicationsReadAndRefusedInARowEachAnswerEveryRequest() = repeat(ROUNDS) { round ->
        benchTest(serverModules(), overrides) { server ->
            val party = server.party("w$round")
            party.trace = ::println
            server.seed(party, spends = false)
            val matrix = IsolationMatrix(direct(), ISOLATION_REGISTRY, party.values, emptySet())
            matrix.trace = ::println
            val findings = mutableListOf<String>()
            val changes = matrix.watch(party.browser) {
                findings += matrix.adminWrites(ISOLATION_REGISTRY, party.memberBrowser)
            }
            println("round $round read and refused")
            val all = party.problems + findings + changes
            assertTrue(all.isEmpty(), "round $round:\n${all.joinToString("\n")}")
        }
    }

    private fun ApplicationTestBuilder.direct(): HttpClient = createClient { followRedirects = false }

    private companion object {
        const val ROUNDS = 6
    }
}
