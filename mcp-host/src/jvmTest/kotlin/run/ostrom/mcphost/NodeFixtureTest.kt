// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The real launch path: the host runs the Node.js fixture server (TeamCode's, `stdio-fixture-server.js`) installed
 * as `npx` and `uvx`, through `ProcessBuilder` and the stdio transport. Node.js
 * must be on `PATH` or in `OSTROM_MCP_HOST_TEST_NODE`: the test fails without it, it never passes by skipping.
 */
class NodeFixtureTest {
    private val node: String = System.getenv("OSTROM_MCP_HOST_TEST_NODE")?.takeIf { it.isNotBlank() }
        ?: System.getenv("PATH").split(':').map { Path.of(it, "node") }.first { Files.isExecutable(it) }.toString()
    private val root: Path = Files.createTempDirectory("ostrom-mcp-node")
    private val events = mutableListOf<JsonObject>()

    private fun host(): Supervisor {
        val bin = Files.createDirectories(root.resolve("bin"))
        val script = javaClass.getResource("/stdio-fixture-server.js")!!.readText()
        for (name in listOf("npx", "uvx")) {
            Files.writeString(bin.resolve(name), script)
            bin.resolve(name).toFile().setExecutable(true)
        }
        val config = HostConfig(
            token = "t".repeat(32),
            dataDir = root.resolve("data").toString(),
            npx = bin.resolve("npx").toString(),
            uvx = bin.resolve("uvx").toString(),
            // No prlimit: RLIMIT_NPROC counts every thread of the user, Gradle's included, and a CI user may not raise
            // its hard limit. The image's contract test (scripts/smoke-mcp-host-image.py) runs the prlimit path.
            prlimit = null,
            path = "${Path.of(node).parent}:/usr/bin:/bin",
            drainMinMillis = 0,
            tickMillis = 100,
            retainGraceMillis = 0,
            memoryCheckMillis = 200,
            closeStdinWaitMillis = 1_000,
            terminateWaitMillis = 1_500,
        )
        val table = JvmProcessTable()
        val platform = HostPlatform(
            JvmProcessSpawner(table),
            table,
            JvmHostFiles(),
            { synchronized(events) { events += it } },
            Dispatchers.IO.limitedParallelism(64),
            { Clock.System.now().toEpochMilliseconds() },
        )
        return Supervisor(config, platform, Dispatchers.Default)
    }

    private fun spec(mode: String = "", launcher: String = "npm") = ProcessSpec(
        launcher,
        if (launcher == "npm") "fixture@1.0.0" else "fixture==1.0.0",
        listOf("--key", "\${FIXTURE_SECRET}"),
        mapOf("FIXTURE_MODE" to mode),
        mapOf("FIXTURE_SECRET" to "fixture-secret-value"),
        Clock.System.now().toEpochMilliseconds(),
    )

    private fun call(key: String, tool: String, spec: ProcessSpec = spec(), arguments: JsonObject? = null) =
        OpsRequest(key, spec, "call", tool, arguments, 20_000, 4 shl 20)

    private fun OpsResponse.text() = result!!["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

    private val key = "c".repeat(64)

    @Test
    fun theFixtureIsListedCalledAndIsolated() = runBlocking {
        val host = host()
        try {
            withTimeout(60.seconds) {
                assertTrue(host.ready())
                val listed = host.op(OpsRequest(key, spec(), "list", null, null, 20_000, 4 shl 20))
                assertTrue(listed.cold, listed.toString())
                assertEquals(15, listed.result!!["tools"]!!.jsonArray.size)
                val echoed = host.op(call(key, "echo", arguments = buildJsonObject { put("text", "hello") }))
                assertEquals("hello", echoed.text())
                val env = Json.parseToJsonElement(host.op(call(key, "env")).text()).jsonObject
                assertEquals("$root/data/$key/home", env["HOME"]!!.jsonPrimitive.content)
                assertEquals("[REDACTED]", env["FIXTURE_SECRET"]!!.jsonPrimitive.content)
                val leaked = env.keys.filter { it.startsWith("JAVA") || it.startsWith("OSTROM") || it == "USER" }
                assertEquals(emptyList(), leaked)
                val stats = Json.parseToJsonElement(host.op(call(key, "stats")).text()).jsonObject
                val argv = stats["argv"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertEquals(listOf("--yes", "--", "fixture@1.0.0", "--key", "[REDACTED]"), argv)
                assertEquals(HostErrors.CRASHED, host.op(call(key, "crash")).error!!.code)
                val again = host.op(call(key, "echo", arguments = buildJsonObject { put("text", "hello") }))
                assertEquals("hello", again.text())
                val pyText = buildJsonObject { put("text", "py") }
                val pypi = host.op(call("d".repeat(64), "echo", spec("", "pypi"), pyText))
                assertEquals("py", pypi.text())
                delay(500)
                assertTrue(host.status().processes.all { (it.rssBytes ?: 0) > 0 || it.state != ProcessStates.READY })
            }
        } finally {
            host.shutdown()
        }
    }

    @Test
    fun aServerIgnoringSigtermAndItsGrandchildAreStopped() = runBlocking {
        val host = host()
        try {
            withTimeout(60.seconds) {
                val stubborn = spec("ignore-term")
                val spawned = Json.parseToJsonElement(host.op(call(key, "spawn", stubborn)).text()).jsonObject
                val grandchild = spawned["pid"]!!.jsonPrimitive.content.toLong()
                val server = host.status().processes.single().pid!!
                delay(300)
                assertEquals(1, host.retain(emptyList()))
                delay(5_000)
                assertFalse(ProcessHandle.of(server).map { it.isAlive }.orElse(false))
                assertFalse(ProcessHandle.of(grandchild).map { it.isAlive }.orElse(false))
                assertTrue(events.any { it["event"]?.jsonPrimitive?.content == "killed" })
                assertFalse(Files.exists(root.resolve("data/$key")))
            }
        } finally {
            host.shutdown()
        }
    }
}
