// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.domain.NoRunnerVariables
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.infrastructure.ResolvedRunnerVariables
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.infrastructure.runnerVariables
import run.ostrom.server.secrets.domain.EnvironmentValues
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class RunnersFeaturesTest {
    /** What every cloud runtime serves. */
    private val cloud = setOf("runners.v1", "runners.cloud.v1")

    private fun koin(driver: RunnersConfig.DriverKind) = koinApplication {
        modules(module { single { RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = driver) } })
    }.koin

    @Test
    fun cloudRunnersAreListedOnlyWithARuntimeDriver() = runTest {
        assertEquals(setOf("runners.v1"), RunnersModule.features(koin(RunnersConfig.DriverKind.NONE)))
        assertEquals(cloud, RunnersModule.features(koin(RunnersConfig.DriverKind.DOCKER)))
        assertEquals(
            cloud + "runners.cloud.profiles.v1",
            RunnersModule.features(koin(RunnersConfig.DriverKind.KUBERNETES)),
            "Sessions on the console's clusters run on the organizations' profiles",
        )
        assertEquals(
            cloud,
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
            val expected = cloud + github
            assertEquals(expected, RunnersModule.features(koin))
        }
    }

    @Test
    fun cloudVariablesAreListedOnceTheSecretsModuleResolvesThem() = runTest {
        for (bound in listOf(true, false)) {
            val koin = koinApplication {
                modules(
                    module {
                        single {
                            val docker = RunnersConfig.DriverKind.DOCKER
                            RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = docker)
                        }
                        if (bound) {
                            single<ResolveEnvironmentUseCase> {
                                ResolveEnvironmentUseCase { _, _ -> EnvironmentValues(emptyMap(), emptyList()) }
                            }
                        }
                    },
                )
            }.koin
            val listed = setOfNotNull("runners.cloud.variables.v1".takeIf { bound })
            assertEquals(cloud + listed, RunnersModule.features(koin))
            val variables = runnerVariables(koin.getOrNull<ResolveEnvironmentUseCase>())
            assertEquals(bound, variables is ResolvedRunnerVariables, "Resolved by the secrets module, or none")
            assertEquals(!bound, variables === NoRunnerVariables)
        }
    }

    @Test
    fun theRecipeIsListedOnceTheOrgsModuleSaysWhoIsAMember() = runTest {
        val koin = koinApplication {
            modules(
                module {
                    single {
                        RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = RunnersConfig.DriverKind.DOCKER)
                    }
                    single<MemberDirectory> {
                        object : MemberDirectory {
                            override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = true
                        }
                    }
                },
            )
        }.koin
        assertEquals(cloud + "runners.cloud.environment.v1", RunnersModule.features(koin))
    }
}
