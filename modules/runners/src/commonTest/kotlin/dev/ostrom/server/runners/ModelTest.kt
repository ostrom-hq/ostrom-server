package dev.ostrom.server.runners

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.CloudPlan
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.ExecutionSpec
import dev.ostrom.server.runners.domain.FreeCloudPlanProvider
import dev.ostrom.server.runners.domain.MissingScopeException
import dev.ostrom.server.runners.domain.NoOrganizationException
import dev.ostrom.server.runners.domain.RuntimeNames
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.runners.domain.SessionStateMachine
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.infrastructure.DisabledRuntimeDriver
import dev.ostrom.server.runners.infrastructure.RunnerTokensImpl
import dev.ostrom.server.runners.infrastructure.RunnersConfig
import dev.ostrom.server.runners.presentation.CLOUD_READ
import dev.ostrom.server.runners.presentation.CLOUD_WRITE
import dev.ostrom.server.runners.presentation.toCaller
import kotlinx.coroutines.test.runTest
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
        assertFailsWith<IllegalStateException> { SessionStateMachine.requireTransition(SessionState.RUNNING, SessionState.CREATING) }
    }

    @Test
    fun statesAndAgentsReadTheirWireNames() {
        SessionState.entries.forEach { assertEquals(it, SessionState.fromWire(it.wire)) }
        DesiredState.entries.forEach { assertEquals(it, DesiredState.fromWire(it.wire)) }
        AgentKind.entries.forEach { assertEquals(it, AgentKind.fromWire(it.wire)) }
        assertFailsWith<IllegalArgumentException> { SessionState.fromWire("sleeping") }
        assertFailsWith<IllegalArgumentException> { DesiredState.fromWire("sleeping") }
        assertNull(AgentKind.fromWire("cursor"))
        assertEquals(listOf(SessionState.CREATING, SessionState.RUNNING, SessionState.PAUSING, SessionState.RESUMING), SessionState.entries.filter { it.holdsExecution })
        assertEquals(listOf(SessionState.TERMINATED, SessionState.FAILED), SessionState.entries.filter { it.isFinal })
        assertEquals("bash", AgentKind.SHELL.command)
    }

    @Test
    fun theFreePlanIsTheDecidedOne() = runTest {
        assertEquals(CloudPlan(5, 300, 30, 240, true), FreeCloudPlanProvider().planFor(ORG_A))
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
        val spec = ExecutionSpec(Uuid.random(), ORG_A, 1, AgentKind.CLAUDE, null, "ostrom_runner_secret", CloudPlan.FREE.sessionLimits())
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
        assertEquals(dev.ostrom.server.runners.domain.ExecutionStatus.FAILED, driver.executionStatus(session))
        assertTrue(driver.deleteWorkspace(session))
    }

    @Test
    fun theCallerComesFromTheOrgScope() {
        val org = "org:$ORG_A"
        assertEquals(Caller(ORG_A, "alice"), principal(org).toCaller(CLOUD_WRITE))
        assertEquals(Caller(ORG_A, "alice"), principal(org, CLOUD_READ).toCaller(CLOUD_READ))
        assertEquals(Caller(ORG_A, "alice"), principal(org, CLOUD_WRITE).toCaller(CLOUD_READ))
        assertFailsWith<MissingScopeException> { principal(org, CLOUD_READ).toCaller(CLOUD_WRITE) }
        assertFailsWith<MissingScopeException> { principal(org, "artifacts:write").toCaller(CLOUD_READ) }
        assertFailsWith<NoOrganizationException> { principal().toCaller(CLOUD_READ) }
        assertFailsWith<NoOrganizationException> { principal("org:not-a-uuid").toCaller(CLOUD_READ) }
    }

    private fun principal(vararg scopes: String) = AuthenticatedPrincipal("alice", PrincipalKind.USER, scopes.toSet())

    @Test
    fun theConfigurationDefaultsToNoRuntime() {
        val config = RunnersConfig.from(MapConfigSource(emptyMap()))
        assertEquals(RunnersConfig.DriverKind.NONE, config.driver)
        assertEquals(RunnersConfig.DEFAULT_RUNNER_IMAGE, config.runnerImage)
        assertEquals("ostrom", config.kubernetes.namespace)
        assertEquals("https://kubernetes.default.svc", config.kubernetes.apiUrl)
        assertEquals("10Gi", config.kubernetes.workspaceSize)
        assertNull(config.kubernetes.storageClass)
        assertNull(config.kubernetes.imagePullSecret)
        assertEquals("http://127.0.0.1:2375", config.docker.apiUrl)
        assertNull(config.docker.network)
        assertEquals(RunnersConfig.DriverKind.NONE, RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "none"))).driver)
    }

    @Test
    fun theConfigurationReadsEveryKey() {
        val config = RunnersConfig.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_RUNNERS_DRIVER" to "Kubernetes",
                    "OSTROM_RUNNERS_SERVER_URL" to "http://ostrom-server.ostrom.svc:8080/",
                    "OSTROM_RUNNERS_IMAGE" to "ghcr.io/x/runner:1",
                    "OSTROM_RUNNERS_KUBERNETES_API_URL" to "https://k8s:6443",
                    "OSTROM_RUNNERS_KUBERNETES_NAMESPACE" to "other",
                    "OSTROM_RUNNERS_KUBERNETES_TOKEN_FILE" to "/t",
                    "OSTROM_RUNNERS_KUBERNETES_CA_FILE" to "/c",
                    "OSTROM_RUNNERS_KUBERNETES_STORAGE_CLASS" to "fast",
                    "OSTROM_RUNNERS_WORKSPACE_SIZE" to "5Gi",
                    "OSTROM_RUNNERS_KUBERNETES_IMAGE_PULL_SECRET" to "ghcr",
                    "OSTROM_RUNNERS_DOCKER_API_URL" to "http://docker:2375",
                    "OSTROM_RUNNERS_DOCKER_NETWORK" to "ostrom",
                ),
            ),
        )
        assertEquals(RunnersConfig.DriverKind.KUBERNETES, config.driver)
        assertEquals("http://ostrom-server.ostrom.svc:8080", config.serverUrl)
        assertEquals("ghcr.io/x/runner:1", config.runnerImage)
        assertEquals("https://k8s:6443", config.kubernetes.apiUrl)
        assertEquals("other", config.kubernetes.namespace)
        assertEquals("/t", config.kubernetes.tokenFile)
        assertEquals("/c", config.kubernetes.caFile)
        assertEquals("fast", config.kubernetes.storageClass)
        assertEquals("5Gi", config.kubernetes.workspaceSize)
        assertEquals("ghcr", config.kubernetes.imagePullSecret)
        assertEquals("http://docker:2375", config.docker.apiUrl)
        assertEquals("ostrom", config.docker.network)
        assertEquals(
            RunnersConfig.DriverKind.DOCKER,
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "https://x"))).driver,
        )
    }

    @Test
    fun theConfigurationRefusesAnUnknownDriverOrAMissingServerUrl() {
        assertFailsWith<InvalidConfigException> { RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "nomad"))) }
        assertFailsWith<InvalidConfigException> { RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker"))) }
        assertFailsWith<InvalidConfigException> {
            RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "ftp://x")))
        }
    }
}
