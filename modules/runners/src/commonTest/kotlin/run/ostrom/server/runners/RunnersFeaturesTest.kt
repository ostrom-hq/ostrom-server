// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudFileBody
import run.ostrom.server.runners.domain.CloudFileService
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.CloudTransfersUseCaseImpl
import run.ostrom.server.runners.domain.ConversationAnnouncement
import run.ostrom.server.runners.domain.NoRunnerVariables
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.fakes.FakeInferenceStore
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.auth.DeviceDaemons
import run.ostrom.server.runners.fakes.ContextCipher
import run.ostrom.server.runners.fakes.FakeDeviceDaemons
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.FakeTransferRecipients
import run.ostrom.server.runners.fakes.session
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
        // Remote sessions are hosted on the devices of sign-ins: served with the conversation, and an identity.
        val withDevices = koin(true).also {
            it.loadModules(listOf(module { single<SignedInDevices> { FakeSignedInDevices() } }))
        }
        assertEquals(
            cloud + RunnersModule.CONVERSATION_FEATURES + "runners.cloud.remote.v1",
            RunnersModule.features(withDevices),
        )
        // Sessions started by a device's daemon: once the identity module keeps the daemons and a cipher seals the
        // listings.
        val withDaemons = koin(true).also {
            it.loadModules(
                listOf(
                    module {
                        single<SignedInDevices> { FakeSignedInDevices() }
                        single<DeviceDaemons> { FakeDeviceDaemons() }
                        single<SecretCipher> { ContextCipher }
                    },
                ),
            )
        }
        assertEquals(
            cloud + RunnersModule.CONVERSATION_FEATURES + "runners.cloud.remote.v1" + "runners.cloud.remote.daemon.v1" +
                "runners.cloud.remote.resume.v1",
            RunnersModule.features(withDaemons),
        )
        val cipherless = koin(true).also {
            it.loadModules(
                listOf(
                    module {
                        single<SignedInDevices> { FakeSignedInDevices() }
                        single<DeviceDaemons> { FakeDeviceDaemons() }
                    },
                ),
            )
        }
        assertFalse("runners.cloud.remote.daemon.v1" in RunnersModule.features(cipherless))
        assertFalse("runners.cloud.remote.resume.v1" in RunnersModule.features(cipherless))
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
    fun theInferenceCredentialIsListedOnceTheLaunchesModuleIssuesItsTokens() = runTest {
        val docker = RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = RunnersConfig.DriverKind.DOCKER)
        fun koin(store: FakeInferenceStore?) = koinApplication {
            modules(
                module {
                    single { docker }
                    single { InferenceWiring(store) }
                },
            )
        }.koin
        assertEquals(cloud + "runners.cloud.inference.v1", RunnersModule.features(koin(FakeInferenceStore())))
        assertEquals(cloud, RunnersModule.features(koin(null)), "Without the launches module")
        val store = FakeInferenceStore()
        val written = InferenceWiring(store).written
        val ended = session()
        written.written(ended)
        assertEquals(listOf(ended), store.ended, "A session write tells the store")
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
    fun ownershipTransfersAndWritesOnBehalfAreListedOnlyWhenTheMembersAreKnown() = runTest {
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
            // A write on behalf of another member needs the same members as a transfer.
            val listed = if (wiring.routes == null) emptySet() else setOf(
                "runners.cloud.transfer.v1",
                "runners.cloud.onbehalf.v1",
            )
            assertEquals(cloud + listed, RunnersModule.features(koin))
        }
    }

    @Test
    fun filesAreAnnouncedOnceTheirBytesHaveABucket() = runTest {
        val docker = RunnersConfig.from(MapConfigSource(emptyMap())).copy(driver = RunnersConfig.DriverKind.DOCKER)
        val files = object : CloudFileService {
            override suspend fun policy(caller: Caller) = error("Not called")

            override suspend fun upload(
                caller: Caller,
                principal: AuthenticatedPrincipal,
                sessionId: Uuid,
                rawName: String?,
                announcedBytes: Long?,
                clientKey: String?,
                body: CloudFileBody,
            ) = error("Not called")

            override suspend fun runnerFile(execution: RunnerExecution, fileId: Uuid) = error("Not called")

            override suspend fun sweep(limit: Int) = 0
        }
        fun koin(announced: Boolean, service: CloudFileService?) = koinApplication {
            modules(
                module {
                    single { docker }
                    single { ConversationAnnouncement(announced) }
                    single { FilesWiring(service) }
                },
            )
        }.koin
        assertTrue("runners.cloud.files.v1" in RunnersModule.features(koin(true, files)))
        assertFalse("runners.cloud.files.v1" in RunnersModule.features(koin(true, null)), "No bucket for the bytes")
        assertFalse("runners.cloud.files.v1" in RunnersModule.features(koin(false, files)), "No conversation")
    }
}
