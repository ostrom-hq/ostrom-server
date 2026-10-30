// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        assertTrue("git clone" in SessionScript.FIRST && "exec bash -l" in SessionScript.AGAIN)
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
    fun serverTextFramesAreResizesOrIgnored() {
        assertEquals(ServerFrame.Resize(120, 40), HttpServerApi.parse("""{"type":"resize","columns":120,"rows":40}"""))
        assertNull(HttpServerApi.parse("""{"type":"other"}"""))
        assertNull(HttpServerApi.parse("not json"))
        assertNull(HttpServerApi.parse("""{"type":"resize"}"""))
    }
}
