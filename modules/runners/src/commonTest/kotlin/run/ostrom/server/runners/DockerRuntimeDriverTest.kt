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
import run.ostrom.server.runners.domain.RuntimeUnreachableException
import run.ostrom.server.runners.infrastructure.KubernetesClusters
import run.ostrom.server.runners.infrastructure.KubernetesTarget
import run.ostrom.server.runners.infrastructure.RunnersConfig
import run.ostrom.server.runners.infrastructure.RuntimeDrivers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
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
        RELEASE,
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
    fun anExecutionIsItsBinaryThenTheAgentHostTheCheckHostAndTheRunnerEachInItsContainer() = runTest {
        driver.startExecution(spec)

        assertEquals(
            listOf(
                "POST /volumes/create",
                "POST /containers/create",
                "POST /containers/$name-bin/start",
                "POST /containers/create",
                "POST /containers/$name-agent/start",
                "POST /containers/create",
                "POST /containers/$name-checks/start",
                "POST /containers/create",
                "POST /containers/$name/start",
            ),
            api.lines(),
        )
        // The execution's binary volume, found by its session and epoch.
        val volume = body(0)
        assertEquals("ostrom-ws-$hex-e2-bin", volume["Name"]!!.jsonPrimitive.content)
        val volumeLabels = volume["Labels"]!!.jsonObject
        assertEquals(hex, volumeLabels["ostrom.run/session"]!!.jsonPrimitive.content)
        assertEquals("2", volumeLabels["ostrom.run/epoch"]!!.jsonPrimitive.content)
        assertEquals("$name-bin", api.calls[1].query["name"])
        assertEquals("$name-agent", api.calls[3].query["name"])
        assertEquals("$name-checks", api.calls[5].query["name"])
        assertEquals(name, api.calls[7].query["name"])
        // The agent: its own uid, the workspace, the socket and the binary, and no secret (S2-49).
        val agent = body(3)
        assertEquals(SESSION_IMAGE, agent["Image"]!!.jsonPrimitive.content)
        assertEquals("1001:1000", agent["User"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/opt/ostrom/ostrom", "cloud", "agent-supervisor"),
            agent["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val agentEnv = agent["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("OSTROM_AGENT_SOCKET=/run/ostrom/agent.sock", "HOME=/workspace/.home"), agentEnv)
        val agentHost = agent["HostConfig"]!!.jsonObject
        assertEquals(
            listOf(
                "ostrom-ws-$hex:/workspace",
                "ostrom-ws-$hex-socket:/run/ostrom",
                "ostrom-ws-$hex-e2-bin:/opt/ostrom:ro",
            ),
            agentHost["Binds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(agentHost["GroupAdd"])
        assertFalse("ostrom_runner_xyz" in agent.toString())
    }

    @Test
    fun theCheckHostHasTheAgentsUidTheWorkspaceItsSocketAndTheBinaryOnly() = runTest {
        driver.startExecution(spec)
        // The check host: the agent's uid, the workspace, the check socket and the binary only, no secret.
        val checks = body(5)
        assertEquals(SESSION_IMAGE, checks["Image"]!!.jsonPrimitive.content)
        assertEquals("1001:1000", checks["User"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/opt/ostrom/ostrom", "cloud", "check-host"),
            checks["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("OSTROM_CHECK_HOST_SOCKET=/run/ostrom-check/check.sock", "HOME=/tmp"),
            checks["Env"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val checkHost = checks["HostConfig"]!!.jsonObject
        assertEquals(
            listOf(
                "ostrom-ws-$hex:/workspace",
                "ostrom-ws-$hex-checks:/run/ostrom-check",
                "ostrom-ws-$hex-e2-bin:/opt/ostrom:ro",
            ),
            checkHost["Binds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(checkHost["GroupAdd"])
        assertFalse("ostrom_runner_xyz" in checks.toString())
    }

    @Test
    fun theDeliveryFillsTheBinaryVolumeFromTheRunnerImageThenEnds() = runTest {
        driver.startExecution(spec)
        // The runner image, the runner's uid, the binary volume alone, read-write, over the binary's directory.
        val delivery = body(1)
        assertEquals(RUNNER_IMAGE, delivery["Image"]!!.jsonPrimitive.content)
        assertEquals("1000:1000", delivery["User"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/usr/local/bin/ostrom", "--version"),
            delivery["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(listOf("HOME=/tmp"), delivery["Env"]!!.jsonArray.map { it.jsonPrimitive.content })
        val host = delivery["HostConfig"]!!.jsonObject
        assertEquals(
            listOf("ostrom-ws-$hex-e2-bin:/usr/local/bin"),
            host["Binds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // The same hardening as the session's containers.
        assertEquals(listOf("ALL"), host["CapDrop"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("no-new-privileges"), host["SecurityOpt"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", host["ReadonlyRootfs"]!!.jsonPrimitive.content)
        assertEquals("ostrom", host["NetworkMode"]!!.jsonPrimitive.content)
        assertEquals(hex, delivery["Labels"]!!.jsonObject["ostrom.run/session"]!!.jsonPrimitive.content)
        assertEquals("2", delivery["Labels"]!!.jsonObject["ostrom.run/epoch"]!!.jsonPrimitive.content)
        assertFalse("ostrom_runner_xyz" in delivery.toString())

        // A binary elsewhere in its image: its own directory.
        val elsewhere = RunnerDeliverySource { RunnerDelivery(RUNNER_IMAGE, "/opt/bin/ostrom", SESSION_IMAGE) }
        api.calls.clear()
        DockerRuntimeDriver(api.client, DockerSettings("http://d", null), "http://s", elsewhere).startExecution(spec)
        val binds = body(1)["HostConfig"]!!.jsonObject["Binds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ostrom-ws-$hex-e2-bin:/opt/bin"), binds)
        assertEquals(
            listOf("/opt/bin/ostrom", "--version"),
            body(1)["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun eachExecutionReadsTheDeliveryCurrentAtItsStart() = runTest {
        var image = "runner:1"
        val changing = RunnerDeliverySource { RunnerDelivery(image, RunnerDelivery.DEFAULT_RUNNER_BINARY, "s:1") }
        val docker = DockerRuntimeDriver(api.client, DockerSettings("http://d", null), "http://s", changing)
        docker.startExecution(spec)
        image = "runner:2"
        docker.startExecution(spec.copy(epoch = 3))
        assertEquals("runner:1", body(1)["Image"]!!.jsonPrimitive.content)
        assertEquals("runner:2", body(10)["Image"]!!.jsonPrimitive.content)
        api.on("POST", "/volumes/create", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { docker.startExecution(spec) }
    }

    @Test
    fun theRunnerHasItsOwnUidTheTokenAndBothSocketsButNoWorkspace() = runTest {
        driver.startExecution(spec)
        // The runner: another uid in the session's group (no extra group), the token, and no workspace.
        val container = body(7)
        assertEquals(SESSION_IMAGE, container["Image"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("/opt/ostrom/ostrom", "cloud", "runner"),
            container["Entrypoint"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("1000:1000", container["User"]!!.jsonPrimitive.content)
        assertNull(container["HostConfig"]!!.jsonObject["GroupAdd"])
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
        assertEquals(
            listOf(
                "ostrom-ws-$hex-socket:/run/ostrom",
                "ostrom-ws-$hex-checks:/run/ostrom-check",
                "ostrom-ws-$hex-e2-bin:/opt/ostrom:ro",
            ),
            binds,
        )
        assertEquals("ostrom", host["NetworkMode"]!!.jsonPrimitive.content)
        assertEquals("no", host["RestartPolicy"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
        assertEquals(hex, container["Labels"]!!.jsonObject["ostrom.run/session"]!!.jsonPrimitive.content)
        assertEquals("restricted", container["Labels"]!!.jsonObject["ostrom.run/egress"]!!.jsonPrimitive.content)

        driver.startExecution(spec.copy(prompt = null))
        val silent = body(16)["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(silent.any { it.startsWith("OSTROM_AGENT_PROMPT") })
    }

    @Test
    fun aPlanWithoutSecretsIsTheSameExecutionWithoutTheTokenNorTheFirstMessage() = runTest {
        val plan = DockerExecutionPlan("ostrom", "http://host.docker.internal:8080", RELEASE)
        val delivery = RELEASE.current()
        driver.ensureWorkspace(session)
        driver.startExecution(spec)
        // The same volumes and containers the driver creates, in the same order.
        assertEquals(plan.volumes(session.id).map { it.body }, (0..2).map(::body))
        assertEquals(plan.binaryVolume(spec).body, body(3))
        assertEquals(plan.containers(spec, delivery).map { it.body }, listOf(4, 6, 8, 10).map(::body))
        val kept = plan.containers(spec, delivery, secrets = false)
        assertEquals(listOf("$name-bin", "$name-agent", "$name-checks", name), kept.map { it.name })
        assertEquals(plan.containers(spec, delivery).take(3), kept.take(3))
        val env = kept.last().body["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(env.any { it.startsWith("OSTROM_RUNNER_TOKEN") || it.startsWith("OSTROM_AGENT_PROMPT") })
        assertTrue("OSTROM_SESSION_ID=${session.id}" in env)
        assertEquals("PlannedObject(name=$name)", plan.containers(spec, delivery).last().toString())
        // A registered runner's plan: the session's volumes, the execution's binary, its containers.
        val planned = ExecutionPlan(
            plan.volumes(session.id) + plan.binaryVolume(spec),
            plan.containers(spec, delivery),
        )
        assertEquals(planned, plan.plan(spec))
        assertEquals(planned, plan.plan(spec, delivery))
    }

    @Test
    fun aMissingImageIsPulledThenTheContainerCreatedAgainTheRunnerImageAsTheSessionImage() = runTest {
        // The runner image is missing for the delivery, then the session image for the agent host.
        api.on(
            "POST",
            "/containers/create",
            HttpStatusCode.NotFound to "{}",
            HttpStatusCode.Created to "{}",
            HttpStatusCode.NotFound to "{}",
            HttpStatusCode.Created to "{}",
        )
        driver.startExecution(spec)

        assertEquals(
            listOf(
                "POST /volumes/create",
                "POST /containers/create",
                "POST /images/create",
                "POST /containers/create",
                "POST /containers/$name-bin/start",
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
        assertEquals("ghcr.io/ostrom-hq/ostrom-runner", api.calls[2].query["fromImage"])
        assertEquals("1.2", api.calls[2].query["tag"])
        assertEquals("ghcr.io/acme/session", api.calls[6].query["fromImage"])
        assertEquals("3", api.calls[6].query["tag"])
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
                "GET /volumes",
            ),
            api.lines(),
        )
        assertEquals("""{"label":["ostrom.run/session=$hex"]}""", api.calls[0].query["filters"])
        assertEquals("true", api.calls[2].query["force"])
        // Only the execution's binary volumes: the session's own have no epoch.
        assertEquals("""{"label":["ostrom.run/session=$hex","ostrom.run/epoch"]}""", api.calls[5].query["filters"])

        api.on("DELETE", "/containers/a", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
        api.on("POST", "/containers/a/stop", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
        api.on("GET", "/containers/json", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
    }

    @Test
    fun stoppingRemovesTheExecutionsBinariesAndOneStillHeldIsLeftForLater() = runTest {
        api.on("GET", "/containers/json", HttpStatusCode.OK to "[]")
        val e1 = "ostrom-ws-$hex-e1-bin"
        val e2 = "ostrom-ws-$hex-e2-bin"
        api.on("GET", "/volumes", HttpStatusCode.OK to """{"Volumes":[{"Name":"$e1"},{"Name":"$e2"},{}]}""")
        api.on("DELETE", "/volumes/$e1", HttpStatusCode.Conflict to "{}")
        api.on("GET", "/volumes/$e2", HttpStatusCode.NotFound to "{}")
        driver.stopExecutions(session)
        assertEquals(
            listOf(
                "GET /containers/json",
                "GET /volumes",
                "DELETE /volumes/$e1",
                "DELETE /volumes/$e2",
                "GET /volumes/$e2",
            ),
            api.lines(),
        )
        api.on("DELETE", "/volumes/$e2", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
        api.on("GET", "/volumes", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.stopExecutions(session) }
        // No volume matches: the engine answers null.
        api.on("GET", "/volumes", HttpStatusCode.OK to """{"Volumes":null}""")
        api.calls.clear()
        driver.stopExecutions(session)
        assertEquals(listOf("GET /containers/json", "GET /volumes"), api.lines())
    }

    @Test
    fun aWorkspaceIsGoneOnceItsBinariesAreToo() = runTest {
        val path = "/volumes/ostrom-ws-$hex"
        val binary = "$path-e2-bin"
        for (volume in listOf(path, "$path-socket", "$path-checks", binary)) {
            api.on("GET", volume, HttpStatusCode.NotFound to "{}")
        }
        api.on("GET", "/volumes", HttpStatusCode.OK to """{"Volumes":[{"Name":"ostrom-ws-$hex-e2-bin"}]}""")
        api.on("DELETE", binary, HttpStatusCode.Conflict to "{}")
        assertFalse(driver.deleteWorkspace(session), "The binary is still held")
        api.on("DELETE", binary, HttpStatusCode.NoContent to "")
        assertTrue(driver.deleteWorkspace(session))
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
        DockerRuntimeDriver(api.client, DockerSettings("http://d", null), "http://s", RELEASE).startExecution(spec)
        assertNull(body(1)["HostConfig"]!!.jsonObject["NetworkMode"])
        assertNull(body(3)["HostConfig"]!!.jsonObject["NetworkMode"])
    }

    @Test
    fun aRuntimeWithoutClustersAlwaysOpens() = runTest {
        assertTrue(DisabledRuntimeDriver().reachable(session()), "Only a cluster can stop opening")
    }

    @Test
    fun theConfigurationChoosesTheDriver() {
        val registered = RegisteredRuntimeDriver(InMemoryRunnerAssignments(), SettableClock(), FakeRunnerWorkSignals())
        val none = RunnersConfig.from(MapConfigSource(emptyMap()))
        assertIs<DisabledRuntimeDriver>(RuntimeDrivers.create(none, { registered }, DELIVERY, NO_CLUSTERS))
        val kubernetes = RunnersConfig.from(MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes")), true)
        assertIs<KubernetesRuntimeDriver>(RuntimeDrivers.create(kubernetes, { registered }, DELIVERY, NO_CLUSTERS))
        val docker = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertIs<DockerRuntimeDriver>(RuntimeDrivers.create(docker, { registered }, DELIVERY, NO_CLUSTERS))
        val machines = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "Registered", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertEquals(registered, RuntimeDrivers.create(machines, { registered }, DELIVERY, NO_CLUSTERS))
        assertEquals("registered", machines.runtimeLabel)
        val onDocker = RuntimeDrivers.create(docker, { registered }, DELIVERY, NO_CLUSTERS)
        assertFalse(onDocker.relocates, "Only registered runners move")
    }
}

private val NO_CLUSTERS = {
    object : KubernetesClusters {
        override suspend fun <T> use(clusterId: String, block: suspend (KubernetesTarget) -> T): T =
            throw RuntimeUnreachableException("none")
    }
}

private val DELIVERY = RunnerDeliverySource { RunnerDelivery("runner:1", RunnerDelivery.DEFAULT_RUNNER_BINARY, "img") }

private const val RUNNER_IMAGE = "ghcr.io/ostrom-hq/ostrom-runner:1.2"
private const val SESSION_IMAGE = "ghcr.io/acme/session:3"

/** The CLI's runner image, and a team's session image. */
private val RELEASE = RunnerDeliverySource {
    RunnerDelivery(RUNNER_IMAGE, RunnerDelivery.DEFAULT_RUNNER_BINARY, SESSION_IMAGE)
}
