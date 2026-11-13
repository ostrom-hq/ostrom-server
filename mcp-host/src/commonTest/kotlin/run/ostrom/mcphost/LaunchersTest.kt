// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchersTest {
    private val config = HostConfig(token = "t".repeat(32))
    private val dirs = ProcessDirs("/var/mcp/key")
    private val npm = ProcessSpec(
        "npm",
        "@scope/name@1.2.3-beta.1+build.5",
        listOf("--x", "\${API_KEY}"),
        mapOf("REGION" to "eu"),
        mapOf("API_KEY" to "12345678"),
        1_700_000_000_000,
    )
    private val pypi = ProcessSpec(
        "pypi",
        "mcp-server-fetch[extra]==2026.10.24",
        emptyList(),
        emptyMap(),
        emptyMap(),
        1_700_000_000_000,
    )

    @Test
    fun anNpmPackageRunsThroughNpxFrozenAtItsDeclarationDate() {
        val plan = Launchers(config.copy(npmRegistry = "https://mirror.example")).plan(npm, dirs)
        assertEquals(
            listOf("prlimit", "--nproc=1024", "--nofile=1024", "--", "npx", "--yes", "--", npm.pkg, "--x", "12345678"),
            plan.command,
        )
        assertEquals("2023-11-14T22:13:20Z", plan.environment["npm_config_before"])
        assertEquals("true", plan.environment["npm_config_ignore_scripts"])
        assertEquals("https://mirror.example", plan.environment["npm_config_registry"])
        assertEquals("/usr/local/bin:/usr/bin:/bin", plan.environment["PATH"])
        assertEquals("eu", plan.environment["REGION"])
        assertEquals("12345678", plan.environment["API_KEY"])
        assertEquals("/var/mcp/key/work", plan.workDir)
        assertEquals(listOf("12345678"), plan.secrets.toList())
        assertEquals(8, dirs.all.size)
    }

    @Test
    fun aPypiPackageRunsThroughUvxWithItsOwnPython() {
        val plan = Launchers(config.copy(prlimit = null, pypiIndex = "https://pypi.example/simple")).plan(pypi, dirs)
        assertEquals(
            listOf(
                "uvx",
                "--exclude-newer",
                "2023-11-14T22:13:20Z",
                "--default-index",
                "https://pypi.example/simple",
                pypi.pkg,
            ),
            plan.command,
        )
        assertEquals("/var/mcp/key/python", plan.environment["UV_PYTHON_INSTALL_DIR"])
        assertFalse("npm_config_before" in plan.environment)
        val plain = Launchers(config).plan(pypi, dirs)
        assertFalse("--default-index" in plain.command)
        assertFalse("npm_config_registry" in Launchers(config).plan(npm, dirs).environment)
    }

    @Test
    fun everyRuleIsCheckedAgain() {
        val refused = listOf(
            npm.copy(launcher = "cargo") to "runs only",
            npm.copy(pkg = "left-pad@^1.0.0") to "npm package",
            pypi.copy(pkg = "requests>=2") to "PyPI package",
            npm.copy(declaredAt = 0) to "declaration date",
            npm.copy(env = mapOf("lower" to "x")) to "uppercase",
            npm.copy(env = mapOf("PATH" to "/tmp")) to "reserved",
            npm.copy(env = mapOf("NPM_CONFIG_REGISTRY" to "x")) to "reserved",
            npm.copy(env = mapOf("OSTROM_TOKEN" to "x")) to "reserved",
            npm.copy(env = mapOf("A" to "x\u0000y")) to "NUL",
            npm.copy(env = mapOf("API_KEY" to "x")) to "both",
            npm.copy(secrets = (1..17).associate { "S$it" to "12345678" }, args = emptyList()) to "at most 16",
            npm.copy(secrets = mapOf("S" to "x".repeat(16_384)), args = emptyList()) to "exceed",
            npm.copy(secrets = mapOf("API_KEY" to "short")) to "at least 8",
            npm.copy(args = List(33) { "a" }) to "at most 32",
            npm.copy(args = listOf("x".repeat(513))) to "exceeds 512",
            npm.copy(args = listOf("a\nb")) to "control",
            npm.copy(args = listOf("\${OTHER}")) to "does not name",
        )
        for ((spec, message) in refused) {
            val failure = assertFailsWith<HostFailure>(spec.toString()) { Launchers(config).plan(spec, dirs) }
            assertEquals(HostErrors.INVALID_SPEC, failure.code)
            assertTrue(message in failure.message, "${failure.message} should say $message")
        }
        val onlyNpm = Launchers(config.copy(launchers = setOf("npm")))
        assertTrue("npm" in assertFailsWith<HostFailure> { onlyNpm.plan(pypi, dirs) }.message)
    }

    @Test
    fun noSecretReachesAToString() {
        assertFalse("12345678" in npm.toString())
        val request = OpsRequest("k", npm, "call", "t", kotlinx.serialization.json.JsonObject(emptyMap()), 1, 1)
        assertFalse("12345678" in request.toString())
    }
}
