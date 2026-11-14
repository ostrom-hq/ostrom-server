// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.RunnerDelivery
import run.ostrom.server.runners.domain.RunnerDeliverySource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.ExecutionPlan
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.fakes.FakeApi
import run.ostrom.server.runners.fakes.FakeRunnerWorkSignals
import run.ostrom.server.runners.fakes.InMemoryRunnerAssignments
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.DisabledRuntimeDriver
import run.ostrom.server.runners.infrastructure.DockerApiException
import run.ostrom.server.runners.infrastructure.DockerExecutionPlan
import run.ostrom.server.runners.infrastructure.DockerRuntimeDriver
import run.ostrom.server.runners.infrastructure.DockerSettings
import run.ostrom.server.runners.infrastructure.KubernetesRuntimeDriver
import run.ostrom.server.runners.infrastructure.RegisteredRuntimeDriver
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.infrastructure.RuntimeDrivers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerRuntimeDriverTest {
    private val api = FakeApi()
    private val driver = DockerRuntimeDriver(
        api.client,
        DockerSettings("http://docker:2375/", "ostrom"),
        "http://host.docker.internal:8080",
        "ghcr.io/ostrom-hq/ostrom-runner:1.2",
    )
    private val session = session().copy(epoch = 2)
    private val hex = session.id.toHexString()
    private val name = "ostrom-s-$hex-e2"
    private val spec = ExecutionSpec(
        session.id,
        ORG_A,
        2,
        AgentKind.SHELL,
        null,
        "ostrom_runner_xyz",
        CloudPlan.FREE.sessionLimits(),
        prompt = "hi",
    )

    private fun body(index: Int): JsonObject = Json.parseToJsonElement(api.calls[index].body!!).jsonObject

    @Test
    fun aSessionOnThisDockerNamesNoRegisteredRunner() = runTest {
        assertNull(driver.holder(session))
        assertTrue(api.lines().isEmpty(), "Nothing asked of the engine")
    }

    @Test
    fun aWorkspaceIsANamedVolumeWithItsSocketVolumes() = runTest {
        driver.ensureWorkspace(session)
        assertEquals(List(3) { "POST /volumes/create" }, api.lines())
        assertEquals("ostrom-ws-$hex", body(0)["Name"]!!.jsonPrimitive.content)
        assertEquals("ostrom-ws-$hex-socket", body(1)["Name"]!!.jsonPrimitive.content)
        assertEquals("ostrom-ws-$hex-checks", body(2)["Name"]!!.jsonPrimitive.content)
        api.on("POST", "/volumes/create", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.ensureWorkspace(session) }
    }

    @Test
    fun anExecutionIsTheAgentHostTheCheckHostThenTheRunnerEachInItsContainer() = runTest {
        driver.startExecution(spec)

        assertEquals(
            listOf(
                "POST /containers/create",
                "POST /containers/$name-agent/start",
                "POST /containers/create",
                "POST /containers/$name-checks/start",
                "POST /containers/create",
                "POST /containers/$name/start",
            ),
            api.lines(),
        )
        assertEquals("$name-agent", api.calls[0].query["name"])
        assertEquals("$name-checks", api.calls[2].query["name"])
        assertEquals(name, api.calls[4].query["name"])
        // The agent: its own uid, the workspace and the socket, and no secret (S2-49).
        val agent = body(0)
        assertEquals("1000:1000", agent["User"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/usr/local/bin/ostrom-runner", "agent-host"),
            agent["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val agentEnv = agent["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("OSTROM_AGENT_SOCKET=/run/ostrom/agent.sock", "HOME=/workspace/.home"), agentEnv)
        val agentHost = agent["HostConfig"]!!.jsonObject
        assertEquals(
            listOf("ostrom-ws-$hex:/workspace", "ostrom-ws-$hex-socket:/run/ostrom"),
            agentHost["Binds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(agentHost["GroupAdd"])
        assertFalse("ostrom_runner_xyz" in agent.toString())
        // The check host: the agent's uid, the workspace and the check socket only, no secret.
        val checks = body(2)
        assertEquals("1000:1000", checks["User"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/usr/local/bin/ostrom-runner", "check-host"),
            checks["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("OSTROM_CHECK_HOST_SOCKET=/run/ostrom-check/check.sock", "HOME=/tmp"),
            checks["Env"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val checkHost = checks["HostConfig"]!!.jsonObject
        assertEquals(
            listOf("ostrom-ws-$hex:/workspace", "ostrom-ws-$hex-checks:/run/ostrom-check"),
            checkHost["Binds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(checkHost["GroupAdd"])
        assertFalse("ostrom_runner_xyz" in checks.toString())
    }

    @Test
    fun theRunnerHasItsOwnUidTheTokenAndBothSocketsButNoWorkspace() = runTest {
        driver.startExecution(spec)
        // The runner: another uid, the shared group for the sockets, the token, and no workspace.
        val container = body(4)
        assertEquals("ghcr.io/ostrom-hq/ostrom-runner:1.2", container["Image"]!!.jsonPrimitive.content)
        assertEquals("1001:1001", container["User"]!!.jsonPrimitive.content)
        val groups = container["HostConfig"]!!.jsonObject["GroupAdd"]!!.jsonArray
        assertEquals("2000", groups.single().jsonPrimitive.content)
        val env = container["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("OSTROM_RUNNER_TOKEN=ostrom_runner_xyz" in env)
        assertTrue("OSTROM_AGENT_COMMAND=bash" in env)
        assertTrue("OSTROM_AGENT_PROMPT=hi" in env)
        assertFalse(env.any { it.startsWith("OSTROM_AGENT_MODEL") })
        assertTrue("OSTROM_SERVER_URL=http://host.docker.internal:8080" in env)
        assertFalse(env.any { it.startsWith("OSTROM_REPOSITORY_URL") })
        assertTrue("OSTROM_RUNNER_CHECKS=/run/ostrom/checks.sock" in env)
        assertTrue("OSTROM_CHECK_HOST_SOCKET=/run/ostrom-check/check.sock" in env)
        val host = container["HostConfig"]!!.jsonObject
        assertEquals("true", host["ReadonlyRootfs"]!!.jsonPrimitive.content)
        assertEquals("rw,nosuid,size=1g", host["Tmpfs"]!!.jsonObject["/tmp"]!!.jsonPrimitive.content)
        val binds = host["Binds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ostrom-ws-$hex-socket:/run/ostrom", "ostrom-ws-$hex-checks:/run/ostrom-check"), binds)
        assertEquals("ostrom", host["NetworkMode"]!!.jsonPrimitive.content)
        assertEquals("no", host["RestartPolicy"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
        assertEquals(hex, container["Labels"]!!.jsonObject["ostrom.run/session"]!!.jsonPrimitive.content)
        assertEquals("restricted", container["Labels"]!!.jsonObject["ostrom.run/egress"]!!.jsonPrimitive.content)

        driver.startExecution(spec.copy(prompt = null))
        val silent = body(10)["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(silent.any { it.startsWith("OSTROM_AGENT_PROMPT") })
    }

    @Test
    fun aPlanWithoutSecretsIsTheSameExecutionWithoutTheTokenNorTheFirstMessage() = runTest {
        val plan = DockerExecutionPlan(
            "ostrom",
            "http://host.docker.internal:8080",
            "ghcr.io/ostrom-hq/ostrom-runner:1.2",
        )
        driver.ensureWorkspace(session)
        driver.startExecution(spec)
        // The same volumes and containers the driver creates, in the same order.
        assertEquals(plan.volumes(session.id).map { it.body }, (0..2).map(::body))
        assertEquals(plan.containers(spec).map { it.body }, listOf(3, 5, 7).map(::body))
        val kept = plan.containers(spec, secrets = false)
        assertEquals(listOf("$name-agent", "$name-checks", name), kept.map { it.name })
        assertEquals(plan.containers(spec).take(2), kept.take(2))
        val env = kept.last().body["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(env.any { it.startsWith("OSTROM_RUNNER_TOKEN") || it.startsWith("OSTROM_AGENT_PROMPT") })
        assertTrue("OSTROM_SESSION_ID=${session.id}" in env)
        assertEquals("PlannedObject(name=$name)", plan.containers(spec).last().toString())
        assertEquals(ExecutionPlan(plan.volumes(session.id), plan.containers(spec)), plan.plan(spec))
    }

    @Test
    fun aMissingImageIsPulledThenTheContainerCreatedAgain() = runTest {
        api.on("POST", "/containers/create", HttpStatusCode.NotFound to "{}", HttpStatusCode.Created to "{}")
        driver.startExecution(spec)

        assertEquals(
            listOf(
                "POST /containers/create",
                "POST /images/create",
                "POST /containers/create",
                "POST /containers/$name-agent/start",
                "POST /containers/create",
                "POST /containers/$name-checks/start",
                "POST /containers/create",
                "POST /containers/$name/start",
            ),
            api.lines(),
        )
        assertEquals("ghcr.io/ostrom-hq/ostrom-runner", api.calls[1].query["fromImage"])
        assertEquals("1.2", api.calls[1].query["tag"])
    }

    @Test
    fun aRepeatedStartIsFineAndARefusedOneFails() = runTest {
        api.on("POST", "/containers/create", HttpStatusCode.Conflict to "{}")
        api.on("POST", "/containers/$name/start", HttpStatusCode.NotModified to "")
        driver.startExecution(spec)

        api.on("POST", "/containers/$name/start", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.startExecution(spec) }
        api.on("POST", "/containers/create", HttpStatusCode.BadRequest to "{}")
        assertFailsWith<DockerApiException> { driver.startExecution(spec) }
        api.on("POST", "/containers/create", HttpStatusCode.NotFound to "{}")
        api.on("POST", "/images/create", HttpStatusCode.NotFound to "{}")
        assertFailsWith<DockerApiException> { driver.startExecution(spec) }
    }

    @Test
    fun containerStatesMapToExecutionStates() = runTest {
        val path = "/containers/$name/json"
        val cases = mapOf(
            "running" to ExecutionStatus.RUNNING,
            "paused" to ExecutionStatus.RUNNING,
            "restarting" to ExecutionStatus.RUNNING,
            "created" to ExecutionStatus.PENDING,
            "exited" to ExecutionStatus.ENDED,
            "dead" to ExecutionStatus.ENDED,
        )
        for ((state, expected) in cases) {
            api.on("GET", path, HttpStatusCode.OK to """{"State":{"Status":"$state"}}""")
            assertEquals(expected, driver.executionStatus(session))
        }
        api.on("GET", path, HttpStatusCode.OK to "{}")
        assertEquals(ExecutionStatus.PENDING, driver.executionStatus(session))
        api.on("GET", path, HttpStatusCode.NotFound to "{}")
        assertEquals(ExecutionStatus.MISSING, driver.executionStatus(session))
        api.on("GET", path, HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.executionStatus(session) }
    }

    @Test
    fun stoppingStopsThenRemovesEveryContainerOfTheSession() = runTest {
        api.on("GET", "/containers/json", HttpStatusCode.OK to """[{"Id":"a"},{"Id":"b"},{}]""")
        api.on("POST", "/containers/b/stop", HttpStatusCode.NotModified to "")
        api.on("DELETE", "/containers/b", HttpStatusCode.NotFound to "{}")
        driver.stopExecutions(session)

        assertEquals(
            listOf(
                "GET /containers/json",
                "POST /containers/a/stop",
                "DELETE /containers/a",
                "POST /containers/b/stop",
                "DELETE /containers/b",
            ),
            api.lines(),
        )
        assertEquals("""{"label":["ostrom.run/session=$hex"]}""", api.calls[0].query["filters"])
        assertEquals("true", api.calls[2].query["force"])

        api.on("DELETE", "/containers/a", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
        api.on("POST", "/containers/a/stop", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
        api.on("GET", "/containers/json", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
    }

    @Test
    fun aVolumeInUseIsNotGoneYet() = runTest {
        val path = "/volumes/ostrom-ws-$hex"
        val socket = "$path-socket"
        val checks = "$path-checks"
        api.on("GET", socket, HttpStatusCode.NotFound to "{}")
        api.on("GET", checks, HttpStatusCode.NotFound to "{}")
        api.on("DELETE", path, HttpStatusCode.Conflict to "{}")
        assertFalse(driver.deleteWorkspace(session))
        api.on("DELETE", path, HttpStatusCode.NoContent to "")
        api.on("GET", path, HttpStatusCode.NotFound to "{}")
        assertTrue(driver.deleteWorkspace(session))
        api.on("DELETE", path, HttpStatusCode.NotFound to "{}")
        assertTrue(driver.deleteWorkspace(session))
        api.on("GET", path, HttpStatusCode.OK to "{}")
        assertFalse(driver.deleteWorkspace(session))
        api.on("DELETE", path, HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.deleteWorkspace(session) }
        // The socket's volume still used keeps the answer at "not yet".
        api.on("DELETE", path, HttpStatusCode.NotFound to "{}")
        api.on("DELETE", socket, HttpStatusCode.Conflict to "{}")
        assertFalse(driver.deleteWorkspace(session))
        // So does the check socket's.
        api.on("DELETE", socket, HttpStatusCode.NotFound to "{}")
        api.on("DELETE", checks, HttpStatusCode.Conflict to "{}")
        assertFalse(driver.deleteWorkspace(session))
    }

    @Test
    fun imageReferencesSplitIntoNameAndTag() {
        assertEquals("ghcr.io/a/b" to "1", DockerRuntimeDriver.splitImage("ghcr.io/a/b:1"))
        assertEquals("localhost:5000/b" to "latest", DockerRuntimeDriver.splitImage("localhost:5000/b"))
        assertEquals("b" to "sha256:abc", DockerRuntimeDriver.splitImage("b@sha256:abc"))
        assertEquals("b" to "latest", DockerRuntimeDriver.splitImage("b"))
    }

    @Test
    fun aDefaultNetworkLeavesTheModeToTheEngine() = runTest {
        DockerRuntimeDriver(api.client, DockerSettings("http://d", null), "http://s", "img").startExecution(spec)
        assertNull(body(0)["HostConfig"]!!.jsonObject["NetworkMode"])
    }

    @Test
    fun theConfigurationChoosesTheDriver() {
        val registered = RegisteredRuntimeDriver(InMemoryRunnerAssignments(), SettableClock(), FakeRunnerWorkSignals())
        val none = RunnersConfig.from(MapConfigSource(emptyMap()))
        assertIs<DisabledRuntimeDriver>(RuntimeDrivers.create(none, { registered }, DELIVERY))
        val kubernetes = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertIs<KubernetesRuntimeDriver>(RuntimeDrivers.create(kubernetes, { registered }, DELIVERY) { "token" })
        val docker = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertIs<DockerRuntimeDriver>(RuntimeDrivers.create(docker, { registered }, DELIVERY))
        val machines = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "Registered", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertEquals(registered, RuntimeDrivers.create(machines, { registered }, DELIVERY))
        assertEquals("registered", machines.runtimeLabel)
        assertFalse(RuntimeDrivers.create(docker, { registered }, DELIVERY).relocates, "Only registered runners move")
    }

    @Test
    fun theTokenFileIsReadFromDisk() {
        val path = Path(SystemTemporaryDirectory, "ostrom-runners-token-test")
        SystemFileSystem.sink(path).buffered().use { it.writeString("sa-token\n") }
        assertEquals("sa-token\n", RuntimeDrivers.readTextFile(path.toString()))
        SystemFileSystem.delete(path)
    }
}

private val DELIVERY = RunnerDeliverySource { RunnerDelivery("runner:1", RunnerDelivery.DEFAULT_RUNNER_BINARY, "img") }
