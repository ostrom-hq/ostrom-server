// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AllowlistedCloudPlanProvider
import run.ostrom.server.runners.domain.AllowedOrgs
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.FreeCloudPlanProvider
import run.ostrom.server.runners.domain.MissingScopeException
import run.ostrom.server.runners.domain.NotAMemberException
import run.ostrom.server.runners.domain.RuntimeNames
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.SessionStateMachine
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.DisabledRuntimeDriver
import run.ostrom.server.runners.infrastructure.KubernetesSettings
import run.ostrom.server.runners.infrastructure.RunnerTokensImpl
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.runners.presentation.CLOUD_READ
import run.ostrom.server.runners.presentation.CLOUD_WRITE
import run.ostrom.server.runners.presentation.callerOf
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.TestOrgResolver
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.server.runners.infrastructure.SettingsAllowedOrgs
import run.ostrom.server.runners.infrastructure.CLOUD_ACCESS_SETTINGS
import run.ostrom.server.testing.TestInstanceSettings
import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ModelTest {
    @Test
    fun theStateMachineAllowsOnlyItsTransitions() {
        assertTrue(SessionStateMachine.canMove(SessionState.CREATING, SessionState.RUNNING))
        assertTrue(SessionStateMachine.canMove(SessionState.PAUSED, SessionState.PAUSED))
        assertFalse(SessionStateMachine.canMove(SessionState.TERMINATED, SessionState.RUNNING))
        assertFalse(SessionStateMachine.canMove(SessionState.PAUSED, SessionState.RUNNING))
        assertFalse(SessionStateMachine.canMove(SessionState.FAILED, SessionState.RESUMING))
        SessionStateMachine.requireTransition(SessionState.PAUSING, SessionState.RUNNING)
        assertFailsWith<IllegalStateException> { SessionStateMachine.requireTransition(
            SessionState.RUNNING,
            SessionState.CREATING,
        ) }
    }

    @Test
    fun statesAndAgentsReadTheirWireNames() {
        SessionState.entries.forEach { assertEquals(it, SessionState.fromWire(it.wire)) }
        DesiredState.entries.forEach { assertEquals(it, DesiredState.fromWire(it.wire)) }
        AgentKind.entries.forEach { assertEquals(it, AgentKind.fromWire(it.wire)) }
        assertFailsWith<IllegalArgumentException> { SessionState.fromWire("sleeping") }
        assertFailsWith<IllegalArgumentException> { DesiredState.fromWire("sleeping") }
        assertNull(AgentKind.fromWire("cursor"))
        assertEquals(
            listOf(SessionState.CREATING, SessionState.RUNNING, SessionState.PAUSING, SessionState.RESUMING),
            SessionState.entries.filter { it.holdsExecution },
        )
        assertEquals(listOf(SessionState.TERMINATED, SessionState.FAILED), SessionState.entries.filter { it.isFinal })
        assertEquals("bash", AgentKind.SHELL.command)
    }

    @Test
    fun theFreePlanIsTheDecidedOne() = runTest {
        assertEquals(CloudPlan(5, 300, 30, 240, true, true), FreeCloudPlanProvider().planFor(ORG_A))
    }

    @Test
    fun runtimeNamesAreDeterministicAndFitKubernetes() {
        val id = Uuid.parse("0123456789abcdef0123456789abcdef")
        assertEquals("ostrom-ws-0123456789abcdef0123456789abcdef", RuntimeNames.workspace(id))
        assertEquals("ostrom-s-0123456789abcdef0123456789abcdef-e12", RuntimeNames.execution(id, 12))
        assertTrue(RuntimeNames.execution(id, Long.MAX_VALUE).length <= 63)
    }

    @Test
    fun anExecutionSpecNeverPrintsItsToken() {
        val spec = ExecutionSpec(
            Uuid.random(),
            ORG_A,
            1,
            AgentKind.CLAUDE,
            null,
            "ostrom_runner_secret",
            CloudPlan.FREE.sessionLimits(),
        )
        assertFalse("secret" in spec.toString())
        val withPrompt = spec.copy(prompt = "private words")
        assertFalse("private" in withPrompt.toString())
    }

    @Test
    fun runnerTokensAreRandomAndHashedWithSha256() {
        val tokens = RunnerTokensImpl()
        val token = tokens.mint()
        assertTrue(token.startsWith("ostrom_runner_"))
        assertEquals(14 + 64, token.length)
        assertNotEquals(token, tokens.mint())
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", tokens.hash("hello"))
    }

    @Test
    fun theDisabledDriverStartsNothing() = runTest {
        val driver = DisabledRuntimeDriver()
        val session = session()
        driver.ensureWorkspace(session)
        driver.startExecution(ExecutionSpec(session.id, ORG_A, 1, AgentKind.CLAUDE, null, "t", session.limits))
        driver.stopExecutions(session)
        assertEquals(run.ostrom.server.runners.domain.ExecutionStatus.FAILED, driver.executionStatus(session))
        assertTrue(driver.deleteWorkspace(session))
    }

    @Test
    fun theCallerIsTheMemberTheCredentialActsFor() = runTest {
        val alice = Caller(ORG_A, ALICE.toString())
        assertEquals(alice, TestOrgResolver.callerOf(principal("user:*"), CLOUD_WRITE))
        assertEquals(alice, TestOrgResolver.callerOf(principal(CLOUD_READ), CLOUD_READ))
        assertEquals(alice, TestOrgResolver.callerOf(principal(CLOUD_WRITE), CLOUD_READ))
        assertEquals(alice, TestOrgResolver.callerOf(principal(CLOUD_WRITE), CLOUD_WRITE))
        assertFailsWith<MissingScopeException> { TestOrgResolver.callerOf(principal(CLOUD_READ), CLOUD_WRITE) }
        assertFailsWith<MissingScopeException> { TestOrgResolver.callerOf(principal("artifacts:write"), CLOUD_READ) }
        assertFailsWith<MissingScopeException> { TestOrgResolver.callerOf(principal(), CLOUD_READ) }
        // Only a user's browser session carries every scope: a service token naming it does not.
        val service = AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("user:*"))
        assertFailsWith<MissingScopeException> { TestOrgResolver.callerOf(service, CLOUD_READ) }
        val stranger = AuthenticatedPrincipal("nobody", PrincipalKind.USER, setOf("user:*"))
        assertFailsWith<NotAMemberException> { TestOrgResolver.callerOf(stranger, CLOUD_READ) }
    }

    @Test
    fun aServiceKeyAdministersTheCloudSessionsOnlyThroughCloudAdmin() = runTest {
        fun key(vararg scopes: String) = AuthenticatedPrincipal("dana", PrincipalKind.SERVICE, scopes.toSet())
        // cloud:admin is write and read, and the administrator's view over every session.
        assertTrue(TestOrgResolver.callerOf(key("cloud:admin"), CLOUD_WRITE).admin)
        assertTrue(TestOrgResolver.callerOf(key("cloud:admin"), CLOUD_READ).admin)
        // cloud:write on the key of an administrator acts as the owner alone.
        assertFalse(TestOrgResolver.callerOf(key(CLOUD_WRITE), CLOUD_WRITE).admin)
        assertFalse(TestOrgResolver.callerOf(key(CLOUD_READ), CLOUD_READ).admin)
        assertFailsWith<MissingScopeException> { TestOrgResolver.callerOf(key("team:read"), CLOUD_READ) }
        // Only a service key carries it: a person's token naming it is not allowed more.
        val named = AuthenticatedPrincipal("dana", PrincipalKind.USER, setOf("cloud:admin"))
        assertFailsWith<MissingScopeException> { TestOrgResolver.callerOf(named, CLOUD_READ) }
        // A person who is an administrator keeps theirs.
        val person = AuthenticatedPrincipal("dana", PrincipalKind.USER, setOf("user:*"))
        assertTrue(TestOrgResolver.callerOf(person, CLOUD_READ).admin)
    }

    private fun principal(vararg scopes: String) = AuthenticatedPrincipal("alice", PrincipalKind.USER, scopes.toSet())

    @Test
    fun theModuleReadsTheBoundConfiguration() {
        val bound = module { single<ConfigSource> { MapConfigSource(mapOf("OSTROM_RUNNERS_IMAGE" to "runner:1")) } }
        val koin = koinApplication { modules(bound, RunnersModule.koinModule) }
        assertEquals("runner:1", koin.koin.get<RunnersConfig>().runnerImage)
        koin.close()
    }

    @Test
    fun aKubernetesRunnerCallsThePublicUrlWhileTheRuntimeNamesNone() = runTest {
        val public = module {
            single<ConfigSource> { MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://api.acme.dev/")) }
        }
        val koin = koinApplication { modules(public, RunnersModule.koinModule) }
        assertEquals("https://api.acme.dev", koin.koin.get<RunnerDeliverySource>().current().serverUrl)
        koin.close()
    }

    @Test
    fun anUpgradedInstallsRunnersServerUrlComesBeforeThePublicOne() = runTest {
        val upgraded = module {
            single<ConfigSource> {
                MapConfigSource(
                    mapOf(
                        "OSTROM_RUNNERS_DRIVER" to "kubernetes",
                        "OSTROM_RUNNERS_SERVER_URL" to "http://ostrom.ostrom.svc:8080/",
                        "OSTROM_PUBLIC_URL" to "https://api.acme.dev/",
                    ),
                )
            }
        }
        val koin = koinApplication { modules(upgraded, RunnersModule.koinModule) }
        assertEquals("http://ostrom.ostrom.svc:8080", koin.koin.get<RunnerDeliverySource>().current().serverUrl)
        koin.close()
        assertFailsWith<InvalidConfigException>("A URL set for Kubernetes is still checked") {
            val unusable = mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes", "OSTROM_RUNNERS_SERVER_URL" to "ostrom")
            RunnersConfig.from(MapConfigSource(unusable), cloudModule = true)
        }
    }

    @Test
    fun theConfigurationDefaultsToNoRuntime() {
        val config = RunnersConfig.from(MapConfigSource(emptyMap()))
        assertEquals(RunnersConfig.DriverKind.NONE, config.driver)
        assertNull(config.runtimeLabel)
        assertEquals(RunnersConfig.DEFAULT_RUNNER_IMAGE, config.runnerImage)
        assertNull(config.kubernetes.priorityClass)
        assertEquals("http://127.0.0.1:2375", config.docker.apiUrl)
        assertNull(config.docker.network)
        assertEquals(
            RunnersConfig.DriverKind.NONE,
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "none"))).driver,
        )
    }

    @Test
    fun kubernetesRunsOnTheConsolesClustersWithTheCloudModuleAndUnsetStaysOffAsBeforeTheUpgrade() {
        fun driver(raw: String?, cloud: Boolean) = RunnersConfig.from(
            MapConfigSource(listOfNotNull(raw?.let { "OSTROM_RUNNERS_DRIVER" to it }).toMap()),
            cloud,
        ).driver
        // An upgrade never switches a Docker or bare-metal install, nor a pod with a mounted service account.
        assertEquals(RunnersConfig.DriverKind.NONE, driver(null, cloud = true))
        assertEquals(RunnersConfig.DriverKind.KUBERNETES, driver("kubernetes", cloud = true))
        assertEquals(RunnersConfig.DriverKind.NONE, driver("kubernetes", cloud = false))
        assertEquals(RunnersConfig.DriverKind.NONE, driver("none", cloud = true))
        // Their runner calls the cloud runtime's URL: OSTROM_RUNNERS_SERVER_URL is not needed.
        val kubernetes = MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes"))
        assertEquals("", RunnersConfig.from(kubernetes, cloudModule = true).serverUrl)
        assertFailsWith<InvalidConfigException> {
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker")), cloudModule = true)
        }
    }

    @Test
    fun theConfigurationReadsEveryKey() {
        val config = RunnersConfig.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_RUNNERS_DRIVER" to "Kubernetes",
                    "OSTROM_RUNNERS_SERVER_URL" to "http://ostrom-server.ostrom.svc:8080/",
                    "OSTROM_RUNNERS_IMAGE" to "ghcr.io/x/runner:1",
                    "OSTROM_RUNNERS_KUBERNETES_PRIORITY_CLASS" to "ostrom-low",
                    "OSTROM_RUNNERS_KUBERNETES_SERVER_SELECTOR" to " app.kubernetes.io/instance=prod , role=api",
                    "OSTROM_RUNNERS_DOCKER_API_URL" to "http://docker:2375",
                    "OSTROM_RUNNERS_DOCKER_NETWORK" to "ostrom",
                ),
            ),
            cloudModule = true,
        )
        assertEquals(RunnersConfig.DriverKind.KUBERNETES, config.driver)
        assertEquals("kubernetes", config.runtimeLabel)
        assertEquals("http://ostrom-server.ostrom.svc:8080", config.serverUrl)
        assertEquals("ghcr.io/x/runner:1", config.runnerImage)
        assertEquals("ostrom-low", config.kubernetes.priorityClass)
        assertEquals(mapOf("app.kubernetes.io/instance" to "prod", "role" to "api"), config.kubernetes.serverSelector)
        assertEquals("http://docker:2375", config.docker.apiUrl)
        assertEquals("ostrom", config.docker.network)
        assertEquals(
            RunnersConfig.DriverKind.DOCKER,
            RunnersConfig.from(
                MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "https://x")),
            ).driver,
        )
    }

    @Test
    fun theReplicaUrlIsReadWithoutItsSlashAndChecked() {
        assertNull(RunnersConfig.from(MapConfigSource(emptyMap())).replicaUrl, "One replica: every terminal here")
        assertNull(RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_REPLICA_URL" to "/"))).replicaUrl)
        val pod = MapConfigSource(mapOf("OSTROM_RUNNERS_REPLICA_URL" to "http://10.0.0.7:8080/"))
        assertEquals("http://10.0.0.7:8080", RunnersConfig.from(pod).replicaUrl)
        assertEquals(
            "https://a",
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_REPLICA_URL" to "https://a"))).replicaUrl,
        )
        assertFailsWith<InvalidConfigException> {
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_REPLICA_URL" to "10.0.0.7:8080")))
        }
    }

    @Test
    fun theServerSelectorDefaultsToTheChartLabelsAndIsChecked() {
        val default = RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_KUBERNETES_SERVER_SELECTOR" to " ")))
        assertEquals(KubernetesSettings.DEFAULT_SERVER_SELECTOR, default.kubernetes.serverSelector)
        assertFailsWith<InvalidConfigException> {
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_KUBERNETES_SERVER_SELECTOR" to "a=b,c")))
        }
    }

    @Test
    fun blankValuesReadAsUnset() {
        val config = RunnersConfig.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_RUNNERS_DRIVER" to "",
                    "OSTROM_RUNNERS_IMAGE" to " ",
                    "OSTROM_RUNNERS_KUBERNETES_PRIORITY_CLASS" to " ",
                    "OSTROM_RUNNERS_DOCKER_NETWORK" to "",
                ),
            ),
        )
        assertEquals(RunnersConfig.DriverKind.NONE, config.driver)
        assertEquals(RunnersConfig.DEFAULT_RUNNER_IMAGE, config.runnerImage)
        assertNull(config.kubernetes.priorityClass)
        assertNull(config.docker.network)
    }

    @Test
    fun theConfigurationRefusesAnUnknownDriverOrAMissingServerUrl() {
        assertFailsWith<InvalidConfigException> { RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "nomad")),
        ) }
        assertFailsWith<InvalidConfigException> { RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker")),
        ) }
        assertFailsWith<InvalidConfigException> {
            RunnersConfig.from(
                MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "ftp://x")),
            )
        }
    }

    @Test
    fun cloudSessionsAreOpenOnlyToTheAllowedOrganizations() = runTest {
        val settings = TestInstanceSettings()
        val source = SettingsAllowedOrgs(settings)
        assertEquals(AllowedOrgs.NONE, source.allowedOrgs())
        settings.save("cloud-access", mapOf("allowedOrgs" to " * "))
        assertTrue(ORG_B in source.allowedOrgs())
        settings.save("cloud-access", mapOf("allowedOrgs" to "$ORG_A, ,"))
        val listed = source.allowedOrgs()
        assertTrue(ORG_A in listed)
        assertFalse(ORG_B in listed)
        settings.save("cloud-access", mapOf("allowedOrgs" to "acme"))
        assertEquals(AllowedOrgs.NONE, source.allowedOrgs())
        val plans = AllowlistedCloudPlanProvider({ listed }, FreeCloudPlanProvider())
        assertTrue(plans.planFor(ORG_A).available)
        assertFalse(plans.planFor(ORG_B).available)
    }

    @Test
    fun theConsoleRefusesAnAllowedOrgsListItCannotRead() {
        val field = assertNotNull(CLOUD_ACCESS_SETTINGS.field("allowedOrgs"))
        assertEquals("OSTROM_RUNNERS_ALLOWED_ORGS", field.legacyVariable)
        assertFalse(field.secret)
        assertNull(field.check("*"))
        assertNull(field.check("$ORG_A,$ORG_B"))
        assertEquals("* or organization ids separated by commas.", field.check("*,$ORG_A"))
        assertEquals("* or organization ids separated by commas.", field.check("acme"))
    }
}
