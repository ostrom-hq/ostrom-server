// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostConfigTest {
    private val token = "t".repeat(32)

    @Test
    fun theDefaultsFollowTheDocumentation() {
        val config = HostConfig.fromEnvironment(mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PORT" to "  "))
        assertEquals(8090, config.port)
        assertEquals("/var/mcp", config.dataDir)
        assertEquals(setOf("npm", "pypi"), config.launchers)
        assertEquals(16, config.maxProcesses)
        assertEquals(512L * 1024 * 1024, config.processMemoryBytes)
        assertEquals("prlimit", config.prlimit)
        assertFalse(token in config.toString())
    }

    @Test
    fun everyVariableIsRead() {
        val config = HostConfig.fromEnvironment(
            mapOf(
                "OSTROM_MCP_HOST_TOKEN" to token,
                "OSTROM_MCP_HOST_ADDRESS" to "127.0.0.1",
                "OSTROM_MCP_HOST_PORT" to "9000",
                "OSTROM_MCP_HOST_DATA_DIR" to "/data",
                "OSTROM_MCP_HOST_LAUNCHERS" to "npm, ,",
                "OSTROM_MCP_HOST_NPM_REGISTRY" to "https://npm.example",
                "OSTROM_MCP_HOST_PYPI_INDEX" to "http://pypi.example",
                "OSTROM_MCP_HOST_MAX_PROCESSES" to "4",
                "OSTROM_MCP_HOST_MAX_IN_FLIGHT_PER_PROCESS" to "2",
                "OSTROM_MCP_HOST_PROCESS_MEMORY_MIB" to "128",
                "OSTROM_MCP_HOST_IDLE_MINUTES" to "1",
                "OSTROM_MCP_HOST_START_TIMEOUT_MS" to "1000",
                "OSTROM_MCP_HOST_PATH" to "/bin",
                "OSTROM_MCP_HOST_PRLIMIT" to "none",
                "OSTROM_MCP_HOST_DRAIN_SECONDS" to "3",
                "OSTROM_MCP_HOST_RETAIN_GRACE_SECONDS" to "0",
            ),
        )
        assertEquals("127.0.0.1", config.address)
        assertEquals(9000, config.port)
        assertEquals("/data", config.dataDir)
        assertEquals(setOf("npm"), config.launchers)
        assertEquals("https://npm.example", config.npmRegistry)
        assertEquals("http://pypi.example", config.pypiIndex)
        assertEquals(4, config.maxProcesses)
        assertEquals(2, config.maxInFlightPerProcess)
        assertEquals(128L * 1024 * 1024, config.processMemoryBytes)
        assertEquals(60_000L, config.idleMillis)
        assertEquals(1_000L, config.startTimeoutMillis)
        assertEquals("/bin", config.path)
        assertNull(config.prlimit)
        assertEquals(3_000L, config.drainMillis)
        assertEquals(0L, config.retainGraceMillis)
        val custom = HostConfig.fromEnvironment(
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PRLIMIT" to "/x/prlimit"),
        )
        assertEquals("/x/prlimit", custom.prlimit)
    }

    @Test
    fun malformedValuesAreRefused() {
        val refused = listOf(
            emptyMap(),
            mapOf("OSTROM_MCP_HOST_TOKEN" to "short"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PORT" to "eighty"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PORT" to "70000"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PORT" to "0"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_MAX_PROCESSES" to "300"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_MAX_IN_FLIGHT_PER_PROCESS" to "0"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_RETAIN_GRACE_SECONDS" to "4000"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_RETAIN_GRACE_SECONDS" to "-1"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_LAUNCHERS" to "cargo"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_LAUNCHERS" to ","),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_MAX_PROCESSES" to "0"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_MAX_IN_FLIGHT_PER_PROCESS" to "500"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PROCESS_MEMORY_MIB" to "1"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_IDLE_MINUTES" to "0"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_START_TIMEOUT_MS" to "0"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_DRAIN_SECONDS" to "-1"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_NPM_REGISTRY" to "ftp://x"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to token, "OSTROM_MCP_HOST_PYPI_INDEX" to "file:///x"),
        )
        for (env in refused) {
            val refused = assertFailsWith<IllegalArgumentException>(env.toString()) { HostConfig.fromEnvironment(env) }
            assertTrue(refused.message!!.isNotEmpty())
        }
    }
}
