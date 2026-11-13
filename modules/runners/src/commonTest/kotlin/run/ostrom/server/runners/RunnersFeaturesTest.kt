// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.infrastructure.RunnersConfig
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class RunnersFeaturesTest {
    private fun koin(driver: RunnersConfig.DriverKind) = koinApplication {
        modules(module { single { RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = driver) } })
    }.koin

    @Test
    fun cloudRunnersAreListedOnlyWithARuntimeDriver() = runTest {
        assertEquals(setOf("runners.v1"), RunnersModule.features(koin(RunnersConfig.DriverKind.NONE)))
        for (driver in listOf(RunnersConfig.DriverKind.KUBERNETES, RunnersConfig.DriverKind.DOCKER)) {
            assertEquals(setOf("runners.v1", "runners.cloud.v1"), RunnersModule.features(koin(driver)), "$driver")
        }
        assertEquals(
            setOf("runners.v1", "runners.cloud.v1"),
            RunnersModule.features(koin(RunnersConfig.DriverKind.REGISTERED)),
            "Cloud sessions on the organization's registered runners",
        )
    }

    @Test
    fun cloudGitHubAccessIsListedOnceMembersCanLinkAnAccount() = runTest {
        for (available in listOf(true, false)) {
            val koin = koinApplication {
                modules(
                    module {
                        single {
                            val docker = RunnersConfig.DriverKind.DOCKER
                            RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = docker)
                        }
                        single<RunnerGitHubTokens> {
                            object : RunnerGitHubTokens {
                                override suspend fun available(): Boolean = available

                                override suspend fun token(orgId: Uuid, memberId: String) =
                                    RunnerGitHubToken.NotLinked
                            }
                        }
                    },
                )
            }.koin
            val github = setOfNotNull("runners.cloud.github.v1".takeIf { available })
            val expected = setOf("runners.v1", "runners.cloud.v1") + github
            assertEquals(expected, RunnersModule.features(koin))
        }
    }
}
