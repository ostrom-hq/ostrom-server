// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.domain.CloudTransfersUseCaseImpl
import run.ostrom.server.runners.domain.ConversationAnnouncement
import run.ostrom.server.runners.domain.NoRunnerVariables
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.fakes.FakeTransferRecipients
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.InMemoryCloudTransfersRepository
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.SwitchedTransferPolicy
import run.ostrom.server.runners.infrastructure.ResolvedRunnerVariables
import run.ostrom.server.runners.presentation.CloudTransfersRoutesDependencies
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.infrastructure.runnerVariables
import run.ostrom.server.secrets.domain.EnvironmentValues
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RunnersFeaturesTest {
    /** What every cloud runtime serves: its agent tokens are bound to their cloud session. */
    private val cloud = setOf(
        "runners.v1", "runners.cloud.v1", "runners.cloud.agent.v1", "runners.cloud.idempotency.v1",
    )

    private fun koin(driver: RunnersConfig.DriverKind) = koinApplication {
        modules(module { single { RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = driver) } })
    }.koin

    @Test
    fun theConversationKeysAreAnnouncedByDefault() = runTest {
        val docker = RunnersConfig.DriverKind.DOCKER
        val config = RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = docker)
        fun koin(announced: Boolean) = koinApplication {
            modules(
                module {
                    single { config }
                    single { ConversationAnnouncement(announced) }
                },
            )
        }.koin
        assertTrue(ConversationAnnouncement().announced, "On: the member routes and the runner channel are served")
        assertEquals(cloud + RunnersModule.CONVERSATION_FEATURES, RunnersModule.features(koin(true)))
        assertEquals(cloud, RunnersModule.features(koin(false)), "Turned off, none is announced")
        assertEquals(
            setOf(
                "runners.cloud.conversation.v1", "runners.cloud.permissions.v1", "runners.cloud.permissions.bypass.v1",
                "runners.cloud.tasks.v1", "runners.cloud.transcript.live.v1", "runners.cloud.transcript.feeds.v1",
                "runners.cloud.model.v1", "runners.cloud.effort.v1",
            ),
            RunnersModule.CONVERSATION_FEATURES,
        )
        assertFalse("runners.cloud.files.v1" in RunnersModule.CONVERSATION_FEATURES, "Files are not served")
    }

    @Test
    fun cloudRunnersAreListedOnlyWithARuntimeDriver() = runTest {
        assertEquals(setOf("runners.v1"), RunnersModule.features(koin(RunnersConfig.DriverKind.NONE)))
        assertEquals(cloud, RunnersModule.features(koin(RunnersConfig.DriverKind.DOCKER)))
        assertEquals(
            cloud + "runners.cloud.profiles.v1" + "runners.cloud.retention.v1",
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

    @Test
    fun ownershipTransfersAreListedOnlyWhenTheirRoutesAreServed() = runTest {
        val sessions = InMemoryCloudSessionsRepository()
        val use = CloudTransfersUseCaseImpl(
            sessions,
            InMemoryCloudTransfersRepository(sessions),
            FakeTransferRecipients(),
            SwitchedTransferPolicy(),
            OrgAuditTrail { },
            SettableClock(),
        )
        val resolver = object : PrincipalOrgResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? = null
        }
        val served = TransferWiring(CloudTransfersRoutesDependencies(use, resolver), null)
        for (wiring in listOf(served, TransferWiring(null, null))) {
            val koin = koinApplication {
                modules(
                    module {
                        single {
                            val docker = RunnersConfig.DriverKind.DOCKER
                            RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = docker)
                        }
                        single { wiring }
                    },
                )
            }.koin
            val listed = setOfNotNull("runners.cloud.transfer.v1".takeIf { wiring.routes != null })
            assertEquals(cloud + listed, RunnersModule.features(koin))
        }
    }
}
