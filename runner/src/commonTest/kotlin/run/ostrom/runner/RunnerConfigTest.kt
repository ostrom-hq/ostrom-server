// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerConfigTest {
    private val id = Uuid.random()
    private val base = mapOf(
        "OSTROM_SERVER_URL" to "http://ostrom-server.ostrom.svc:8080/",
        "OSTROM_SESSION_ID" to id.toString(),
        "OSTROM_EPOCH" to "3",
        "OSTROM_AGENT_COMMAND" to "claude",
        "OSTROM_RUNNER_TOKEN_FILE" to "/run/ostrom/token",
        "PATH" to "/usr/bin",
    )

    @Test
    fun theConfigurationComesFromThePodsEnvironment() {
        val config = RunnerConfig.from(
            base + ("OSTROM_REPOSITORY_URL" to "https://github.com/a/b") + ("OSTROM_REPOSITORY_REF" to "main"),
        ) { path ->
            assertEquals("/run/ostrom/token", path)
            "ostrom_runner_secret\n"
        }
        assertEquals("http://ostrom-server.ostrom.svc:8080", config.serverUrl)
        assertEquals(id, config.sessionId)
        assertEquals(3, config.epoch)
        assertEquals("claude", config.agentCommand)
        assertEquals("https://github.com/a/b", config.repositoryUrl)
        assertEquals("main", config.repositoryRef)
        assertEquals("ostrom_runner_secret", config.token)
        assertEquals("/workspace", config.workspace)
        assertFalse("secret" in config.toString())
        // No agent kind said: no launch is opened. The sockets have their defaults.
        assertNull(config.agent)
        assertFalse(config.governed)
        assertEquals("/run/ostrom/checks.sock", config.checksSocket)
        assertEquals("/run/ostrom-check/check.sock", config.checkHostSocket)
        val sockets = RunnerConfig.from(
            base + ("OSTROM_AGENT" to "claude-code") + ("OSTROM_RUNNER_CHECKS" to "/s/c.sock") +
                ("OSTROM_CHECK_HOST_SOCKET" to "/s/h.sock"),
        ) { "t" }
        assertEquals("claude-code", sockets.agent)
        assertTrue(sockets.governed)
        assertEquals("/s/c.sock", sockets.checksSocket)
        assertEquals("/s/h.sock", sockets.checkHostSocket)
        val defaults = config.credentialSocket to config.gitHubConfigDirectory
        assertEquals("/run/ostrom/credential.sock" to "/run/ostrom/gh", defaults)
        val github = RunnerConfig.from(
            base + ("OSTROM_CREDENTIAL_SOCKET" to "/s/k.sock") + ("OSTROM_GH_CONFIG_DIR" to "/s/gh"),
        ) { "t" }
        assertEquals("/s/k.sock" to "/s/gh", github.credentialSocket to github.gitHubConfigDirectory)
        assertFalse(RunnerConfig.from(base + ("OSTROM_AGENT" to "shell")) { "t" }.governed)

        val inline = RunnerConfig.from(
            base - "OSTROM_RUNNER_TOKEN_FILE" + ("OSTROM_RUNNER_TOKEN" to "t") + ("OSTROM_WORKSPACE" to "/w"),
        ) { error("no file") }
        assertEquals("t", inline.token)
        assertEquals("/w", inline.workspace)
        assertNull(inline.repositoryUrl)
        assertNull(inline.model)
        assertNull(inline.prompt)

        val files = mapOf("/run/ostrom/token" to "t", "/run/ostrom/prompt" to "Fix the tests")
        val prompted = RunnerConfig.from(
            base + ("OSTROM_AGENT_PROMPT_FILE" to "/run/ostrom/prompt") + ("OSTROM_AGENT_MODEL" to "m"),
        ) { files.getValue(it) }
        assertEquals("Fix the tests", prompted.prompt)
        assertEquals("m", prompted.model)
        assertEquals("hi", RunnerConfig.from(base + ("OSTROM_AGENT_PROMPT" to "hi")) { "t" }.prompt)
        // Blank values and an empty prompt file read as unset.
        val blank = RunnerConfig.from(
            base + ("OSTROM_AGENT_PROMPT_FILE" to "/run/ostrom/prompt") + ("OSTROM_WORKSPACE" to " ") +
                ("OSTROM_REPOSITORY_URL" to " "),
        ) { if (it.endsWith("prompt")) " \n" else "t" }
        assertNull(blank.prompt)
        assertNull(blank.repositoryUrl)
        assertEquals("/workspace", blank.workspace)
        assertFalse("Fix" in prompted.toString())
        val environment = SessionScript.environment(
            base + ("OSTROM_AGENT_PROMPT_FILE" to "/run/ostrom/prompt"),
            prompted,
        )
        assertEquals("Fix the tests", environment["OSTROM_AGENT_PROMPT"])
        assertNull(environment["OSTROM_AGENT_PROMPT_FILE"])
    }

    @Test
    fun aMissingOrMalformedVariableIsRefused() {
        val read = { _: String -> "t" }
        listOf(
            "OSTROM_SERVER_URL",
            "OSTROM_SESSION_ID",
            "OSTROM_EPOCH",
            "OSTROM_AGENT_COMMAND",
            "OSTROM_RUNNER_TOKEN_FILE",
        ).forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { RunnerConfig.from(base - name, read) }
        }
        assertFailsWith<IllegalArgumentException> { RunnerConfig.from(base + ("OSTROM_SERVER_URL" to "ftp://x"), read) }
        assertFailsWith<IllegalArgumentException> { RunnerConfig.from(base + ("OSTROM_SESSION_ID" to "x"), read) }
        assertFailsWith<IllegalArgumentException> { RunnerConfig.from(base + ("OSTROM_EPOCH" to "x"), read) }
        assertFailsWith<IllegalArgumentException> { RunnerConfig.from(base) { "  \n" } }
    }

    @Test
    fun theAgentNeverSeesTheRunnersToken() {
        val config = RunnerConfig.from(base) { "t" }
        val environment = SessionScript.environment(base + ("OSTROM_RUNNER_TOKEN" to "t"), config)
        assertFalse(environment.keys.any { it in RunnerConfig.PRIVATE_VARIABLES })
        assertEquals("/workspace/.home", environment["HOME"])
        assertEquals("/workspace", environment["OSTROM_WORKSPACE"])
        assertEquals("xterm-256color", environment["TERM"])
        assertEquals("C.UTF-8", environment["LANG"])
        assertEquals("/usr/bin", environment["PATH"])
        assertEquals(
            "/h",
            SessionScript.environment(base + ("HOME" to "/h") + ("LANG" to "fr_FR.UTF-8"), config)["HOME"],
        )
        assertEquals("/workspace/.home", SessionScript.environment(base + ("HOME" to " "), config)["HOME"])
        assertTrue("git clone" in SessionScript.FIRST && "exec bash -l" in SessionScript.AGAIN)
        assertTrue("clone --no-recurse-submodules --" in SessionScript.FIRST)
        // git and gh reach GitHub through the runner, with no token in the agent's environment.
        assertEquals("2", environment["GIT_CONFIG_COUNT"])
        assertEquals("credential.helper" to "", environment["GIT_CONFIG_KEY_0"] to environment["GIT_CONFIG_VALUE_0"])
        assertEquals(
            "credential.https://github.com.helper" to "/usr/local/bin/ostrom-runner credential",
            environment["GIT_CONFIG_KEY_1"] to environment["GIT_CONFIG_VALUE_1"],
        )
        assertEquals("0", environment["GIT_TERMINAL_PROMPT"])
        assertEquals("/run/ostrom/credential.sock", environment["OSTROM_CREDENTIAL_SOCKET"])
        assertEquals("/run/ostrom/gh", environment["GH_CONFIG_DIR"])
        assertTrue("protocol.allow=never" in environment.getValue("OSTROM_GIT_HARDENING"))
        assertTrue("OSTROM_LAUNCH_REFUSED" in SessionScript.FIRST)
    }

    @Test
    fun anEnvironmentBlockIsParsed() {
        assertEquals(
            mapOf("A" to "1", "B" to "x=y", "C" to ""),
            parseEnvironment("A=1\u0000B=x=y\u0000=bad\u0000C=\u0000"),
        )
    }

    @Test
    fun heartbeatsCarryTheExecutionAndReadTheInstruction() = runTest {
        val bodies = mutableListOf<String>()
        var status = HttpStatusCode.OK
        var answer = """{"instruction":"run","state":"running"}"""
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/cloud/runner/heartbeat", request.url.encodedPath)
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            bodies += request.body.toByteArray().decodeToString()
            respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val api = HttpServerApi(client, RunnerConfig.from(base) { "t" }, this)

        assertEquals(
            Instruction.RUN,
            api.heartbeat(ready = true, stopped = false, lastModelRequestAt = "2026-10-30T10:00:00Z"),
        )
        val heartbeat = """{"sessionId":"$id","epoch":3,"ready":true,"stopped":false,""" +
            """"lastModelRequestAt":"2026-10-30T10:00:00Z"}"""
        assertEquals(heartbeat, bodies.single())
        answer = """{"instruction":"stop","state":"pausing","extra":1}"""
        assertEquals(Instruction.STOP, api.heartbeat(ready = true, stopped = false, lastModelRequestAt = null))
        status = HttpStatusCode.Conflict
        assertFailsWith<ExecutionEndedException> { api.heartbeat(true, false, null) }
        status = HttpStatusCode.Unauthorized
        assertFailsWith<ExecutionEndedException> { api.heartbeat(true, false, null) }
        status = HttpStatusCode.BadGateway
        assertFailsWith<IllegalStateException> { api.heartbeat(true, false, null) }
    }

    @Test
    fun theLaunchIsOpenedWithTheRunnerTokenOrRefused() = runTest {
        val bodies = mutableListOf<String>()
        var status = HttpStatusCode.Created
        var answer = """{"launchId":"L-1","agentToken":"ostrom_agent_x","expiresAt":"2026-11-02T20:00:00Z"}"""
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/cloud/runner/launch", request.url.encodedPath)
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            bodies += request.body.toByteArray().decodeToString()
            respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val api = HttpServerApi(client, RunnerConfig.from(base) { "t" }, this)

        val opened = assertIs<RunnerLaunch.Opened>(api.openLaunch())
        assertEquals("L-1", opened.launchId)
        assertEquals("ostrom_agent_x", opened.agentToken)
        assertFalse("ostrom_agent_x" in opened.toString())
        assertEquals("""{"sessionId":"$id","epoch":3}""", bodies.single())
        status = HttpStatusCode.Forbidden
        answer = """{"error":{"type":"agent_not_allowed","message":"Claude Code is not allowed here."}}"""
        assertEquals(RunnerLaunch.Refused("Claude Code is not allowed here."), api.openLaunch())
        status = HttpStatusCode.TooManyRequests
        answer = "slow down"
        assertEquals(RunnerLaunch.Refused("The server answered 429."), api.openLaunch())
        status = HttpStatusCode.Conflict
        assertFailsWith<ExecutionEndedException> { api.openLaunch() }
        status = HttpStatusCode.Unauthorized
        assertFailsWith<ExecutionEndedException> { api.openLaunch() }
        status = HttpStatusCode.ServiceUnavailable
        assertFailsWith<IllegalStateException> { api.openLaunch() }
    }

    @Test
    fun aCheckIsReportedWithTheRunnerToken() = runTest {
        val bodies = mutableListOf<String>()
        var status = HttpStatusCode.Created
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/cloud/runner/checks", request.url.encodedPath)
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            bodies += request.body.toByteArray().decodeToString()
            respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val api = HttpServerApi(client, RunnerConfig.from(base) { "t" }, this)
        val check = RunnerCheck("s-1", "sha256:pkg", "n-1", "lint", "abc", 0, "passed", 12, "h1", "box", "ok")

        assertTrue(api.reportCheck(check))
        val report = """{"sessionId":"$id","epoch":3,"check":{"sessionId":"s-1","digest":"sha256:pkg",""" +
            """"nodeId":"n-1","scriptId":"lint","scriptSha":"abc","exit":0,"outcome":"passed","durationMs":12,""" +
            """"head":"h1","sandbox":"box","outputTail":"ok"}}"""
        assertEquals(report, bodies.single())
        status = HttpStatusCode.NotFound
        assertFalse(api.reportCheck(check))
    }

    @Test
    fun theOwnersGitHubTokenIsAskedWithTheRunnerToken() = runTest {
        val bodies = mutableListOf<String>()
        var status = HttpStatusCode.OK
        var answer = """{"status":"linked","token":"ghu_one","expiresAt":"2026-11-13T18:00:00Z","login":"octocat"}"""
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v1/cloud/runner/github", request.url.encodedPath)
            assertEquals("Bearer t", request.headers[HttpHeaders.Authorization])
            bodies += request.body.toByteArray().decodeToString()
            respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val api = HttpServerApi(client, RunnerConfig.from(base) { "t" }, this)

        val linked = api.gitHubToken()
        assertEquals("ghu_one", linked?.token)
        assertEquals("octocat" to Instant.parse("2026-11-13T18:00:00Z"), linked?.login to linked?.expiresAt)
        assertEquals("""{"sessionId":"$id","epoch":3}""", bodies.single())
        answer = """{"status":"linked","token":"ghu_two","login":" "}"""
        assertEquals(null to null, api.gitHubToken().let { it?.login to it?.expiresAt })
        answer = """{"status":"linked","token":" "}"""
        assertNull(api.gitHubToken())
        answer = """{"status":"not_linked"}"""
        assertNull(api.gitHubToken())
        answer = """{"status":"unavailable","token":"ghu_x"}"""
        assertNull(api.gitHubToken())
        status = HttpStatusCode.NotFound
        assertNull(api.gitHubToken(), "A server without the route")
        status = HttpStatusCode.Conflict
        assertFailsWith<ExecutionEndedException> { api.gitHubToken() }
        status = HttpStatusCode.Unauthorized
        assertFailsWith<ExecutionEndedException> { api.gitHubToken() }
        status = HttpStatusCode.BadGateway
        assertFailsWith<IllegalStateException> { api.gitHubToken() }
    }

    @Test
    fun serverTextFramesAreResizesOrIgnored() {
        assertEquals(ServerFrame.Resize(120, 40), HttpServerApi.parse("""{"type":"resize","columns":120,"rows":40}"""))
        assertNull(HttpServerApi.parse("""{"type":"other"}"""))
        assertNull(HttpServerApi.parse("not json"))
        assertNull(HttpServerApi.parse("""{"type":"resize"}"""))
    }

    @Test
    fun binaryFramesAreKeystrokesAndOtherFramesNothing() {
        val input = HttpServerApi.read(Frame.Binary(true, "ls".encodeToByteArray()))
        assertEquals("ls", (input as ServerFrame.Input).bytes.decodeToString())
        val resize = HttpServerApi.read(Frame.Text("""{"type":"resize","columns":80,"rows":24}"""))
        assertEquals(ServerFrame.Resize(80, 24), resize)
        assertNull(HttpServerApi.read(Frame.Ping(ByteArray(0))))
    }

    @Test
    fun onlyARefusedTokenOrAnotherExecutionEndsTheRunner() {
        assertNull(HttpServerApi.ended(null))
        assertNull(HttpServerApi.ended(CloseReason(CloseReason.Codes.NORMAL, "bye")))
        assertNull(HttpServerApi.ended(CloseReason(4408.toShort(), "too slow")))
        val newer = HttpServerApi.ended(CloseReason(4409.toShort(), "newer"))
        assertEquals("The server closed the terminal (4409).", newer?.message)
        assertTrue(HttpServerApi.ended(CloseReason(4401.toShort(), "token")) is ExecutionEndedException)
    }
}
