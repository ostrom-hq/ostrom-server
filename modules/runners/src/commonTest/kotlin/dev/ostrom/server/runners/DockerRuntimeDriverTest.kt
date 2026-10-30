// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.CloudPlan
import dev.ostrom.server.runners.domain.ExecutionSpec
import dev.ostrom.server.runners.domain.ExecutionStatus
import dev.ostrom.server.runners.fakes.FakeApi
import dev.ostrom.server.runners.fakes.ORG_A
import dev.ostrom.server.runners.fakes.session
import dev.ostrom.server.runners.infrastructure.DisabledRuntimeDriver
import dev.ostrom.server.runners.infrastructure.DockerApiException
import dev.ostrom.server.runners.infrastructure.DockerRuntimeDriver
import dev.ostrom.server.runners.infrastructure.DockerSettings
import dev.ostrom.server.runners.infrastructure.KubernetesRuntimeDriver
import dev.ostrom.server.runners.infrastructure.RunnersConfig
import dev.ostrom.server.runners.infrastructure.RuntimeDrivers
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
    fun aWorkspaceIsANamedVolume() = runTest {
        driver.ensureWorkspace(session)
        assertEquals(listOf("POST /volumes/create"), api.lines())
        assertEquals("ostrom-ws-$hex", body(0)["Name"]!!.jsonPrimitive.content)
        api.on("POST", "/volumes/create", HttpStatusCode.InternalServerError to "{}")
        assertFailsWith<DockerApiException> { driver.ensureWorkspace(session) }
    }

    @Test
    fun anExecutionIsACreatedThenStartedContainer() = runTest {
        driver.startExecution(spec)

        assertEquals(listOf("POST /containers/create", "POST /containers/$name/start"), api.lines())
        assertEquals(name, api.calls[0].query["name"])
        val container = body(0)
        assertEquals("ghcr.io/ostrom-hq/ostrom-runner:1.2", container["Image"]!!.jsonPrimitive.content)
        assertEquals("1000:1000", container["User"]!!.jsonPrimitive.content)
        val env = container["Env"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("OSTROM_RUNNER_TOKEN=ostrom_runner_xyz" in env)
        assertTrue("OSTROM_AGENT_COMMAND=bash" in env)
        assertTrue("OSTROM_AGENT_PROMPT=hi" in env)
        assertFalse(env.any { it.startsWith("OSTROM_AGENT_MODEL") })
        assertTrue("OSTROM_SERVER_URL=http://host.docker.internal:8080" in env)
        assertFalse(env.any { it.startsWith("OSTROM_REPOSITORY_URL") })
        val host = container["HostConfig"]!!.jsonObject
        assertEquals("true", host["ReadonlyRootfs"]!!.jsonPrimitive.content)
        assertEquals("rw,nosuid,size=1g", host["Tmpfs"]!!.jsonObject["/tmp"]!!.jsonPrimitive.content)
        assertEquals("ostrom-ws-$hex:/workspace", host["Binds"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("ostrom", host["NetworkMode"]!!.jsonPrimitive.content)
        assertEquals("no", host["RestartPolicy"]!!.jsonObject["Name"]!!.jsonPrimitive.content)
        assertEquals(hex, container["Labels"]!!.jsonObject["ostrom.run/session"]!!.jsonPrimitive.content)
        assertEquals("restricted", container["Labels"]!!.jsonObject["ostrom.run/egress"]!!.jsonPrimitive.content)
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
        val none = RunnersConfig.from(MapConfigSource(emptyMap()))
        assertIs<DisabledRuntimeDriver>(RuntimeDrivers.create(none))
        val kubernetes = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "kubernetes", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertIs<KubernetesRuntimeDriver>(RuntimeDrivers.create(kubernetes) { "token" })
        val docker = RunnersConfig.from(
            MapConfigSource(mapOf("OSTROM_RUNNERS_DRIVER" to "docker", "OSTROM_RUNNERS_SERVER_URL" to "http://s")),
        )
        assertIs<DockerRuntimeDriver>(RuntimeDrivers.create(docker))
    }

    @Test
    fun theTokenFileIsReadFromDisk() {
        val path = Path(SystemTemporaryDirectory, "ostrom-runners-token-test")
        SystemFileSystem.sink(path).buffered().use { it.writeString("sa-token\n") }
        assertEquals("sa-token\n", RuntimeDrivers.readTextFile(path.toString()))
        SystemFileSystem.delete(path)
    }
}
