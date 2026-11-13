// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

/**
 * The host's settings, read once from `OSTROM_MCP_HOST_*` variables (the chart sets them). Defaults follow
 * docs/mcp-host.md and TeamCode's host (docs/mcp.md, Configuration); the sub-second durations exist for tests.
 *
 * @property token Shared with the server; at least [MIN_TOKEN_LENGTH] characters.
 * @property address The listening address.
 * @property port The listening port.
 * @property dataDir One directory per key below it: home, working directory, temporary files and caches.
 * @property launchers The launchers allowed (`npm`, `pypi`).
 * @property npmRegistry An npm mirror, or null for npm's.
 * @property pypiIndex A PyPI mirror, or null for PyPI's.
 * @property maxProcesses Processes at once.
 * @property maxInFlightPerProcess Concurrent requests per process.
 * @property processMemoryBytes Resident memory of one process tree, over which it is stopped.
 * @property idleMillis A process unused this long is stopped.
 * @property maxLifetimeMillis A process this old is stopped once idle.
 * @property startTimeoutMillis A start (downloads included) and its `initialize`.
 * @property maxLineBytes A stdout line longer than this is dropped unparsed.
 * @property maxRequestBytes The largest request body.
 * @property stderrBytes The stderr kept per process.
 * @property writeStallMillis A write to stdin blocked this long means the process stopped reading: it is restarted.
 * @property maxPids `prlimit --nproc`: counts every thread of the pod's user, so it bounds a fork bomb.
 * @property maxOpenFiles `prlimit --nofile`.
 * @property path The only `PATH` a process sees.
 * @property npx The npm launcher.
 * @property uvx The PyPI launcher.
 * @property prlimit `prlimit`, or null to run without it (development machines).
 * @property drainMillis On SIGTERM, how long running operations may still take.
 * @property drainMinMillis On SIGTERM, how long new requests are still answered.
 * @property tickMillis The supervision period.
 * @property memoryCheckMillis The memory reading period.
 * @property crashLimit More failures than this in [crashWindowMillis] put a key in backoff.
 * @property crashWindowMillis The failure window.
 * @property backoffMillis How long a backoff lasts.
 * @property nonJsonLimitPerMinute More non-JSON stdout lines than this in a minute restart the process.
 * @property oversizeLimit This many oversized lines in [oversizeWindowMillis] restart the process.
 * @property oversizeWindowMillis The oversized lines window.
 * @property closeStdinWaitMillis A graceful stop waits this long after closing stdin.
 * @property retainGraceMillis A retain sweep spares keys first seen this recently.
 * @property terminateWaitMillis How long SIGTERM gets before SIGKILL.
 */
data class HostConfig(
    val token: String,
    val address: String = "0.0.0.0",
    val port: Int = 8090,
    val dataDir: String = "/var/mcp",
    val launchers: Set<String> = Launchers.ALL,
    val npmRegistry: String? = null,
    val pypiIndex: String? = null,
    val maxProcesses: Int = 16,
    val maxInFlightPerProcess: Int = 16,
    val processMemoryBytes: Long = DEFAULT_MEMORY_MIB * MIB,
    val idleMillis: Long = DEFAULT_IDLE_MINUTES * MINUTE,
    val maxLifetimeMillis: Long = DEFAULT_LIFETIME_MINUTES * MINUTE,
    val startTimeoutMillis: Long = 45_000,
    val maxLineBytes: Int = MAX_LINE_MIB * MIB.toInt(),
    val maxRequestBytes: Int = MAX_REQUEST_MIB * MIB.toInt(),
    val stderrBytes: Int = STDERR_BYTES,
    val writeStallMillis: Long = 5_000,
    val maxPids: Int = 1024,
    val maxOpenFiles: Int = 1024,
    val path: String = "/usr/local/bin:/usr/bin:/bin",
    val npx: String = "npx",
    val uvx: String = "uvx",
    val prlimit: String? = "prlimit",
    val drainMillis: Long = 75_000,
    val drainMinMillis: Long = 5_000,
    val tickMillis: Long = 1_000,
    val memoryCheckMillis: Long = 5_000,
    val crashLimit: Int = 3,
    val crashWindowMillis: Long = WINDOW_MINUTES * MINUTE,
    val backoffMillis: Long = WINDOW_MINUTES * MINUTE,
    val nonJsonLimitPerMinute: Int = 100,
    val oversizeLimit: Int = 3,
    val oversizeWindowMillis: Long = WINDOW_MINUTES * MINUTE,
    val closeStdinWaitMillis: Long = 2_000,
    val retainGraceMillis: Long = 60_000,
    val terminateWaitMillis: Long = 5_000,
) {
    init {
        require(token.length >= MIN_TOKEN_LENGTH) {
            "OSTROM_MCP_HOST_TOKEN must hold at least $MIN_TOKEN_LENGTH characters."
        }
        require(port in 1..MAX_PORT) { "OSTROM_MCP_HOST_PORT must be a TCP port." }
        require(retainGraceMillis in 0..MAX_RETAIN_GRACE) { "OSTROM_MCP_HOST_RETAIN_GRACE_SECONDS must be 0 to 3600." }
        require(launchers.isNotEmpty() && Launchers.ALL.containsAll(launchers)) {
            "OSTROM_MCP_HOST_LAUNCHERS takes npm and/or pypi."
        }
        require(maxProcesses in 1..MAX_COUNT && maxInFlightPerProcess in 1..MAX_COUNT) {
            "Use 1 to $MAX_COUNT processes and in-flight requests per process."
        }
        require(processMemoryBytes >= MIN_MEMORY_MIB * MIB) {
            "OSTROM_MCP_HOST_PROCESS_MEMORY_MIB must be at least $MIN_MEMORY_MIB."
        }
        require(idleMillis > 0 && startTimeoutMillis > 0 && drainMillis >= 0) {
            "The idle, start and drain durations must be positive."
        }
        require(npmRegistry == null || isHttpUrl(npmRegistry)) {
            "OSTROM_MCP_HOST_NPM_REGISTRY must be an HTTP(S) URL."
        }
        require(pypiIndex == null || isHttpUrl(pypiIndex)) { "OSTROM_MCP_HOST_PYPI_INDEX must be an HTTP(S) URL." }
    }

    /** Never the token. */
    override fun toString(): String =
        "HostConfig(port=$port, dataDir=$dataDir, launchers=${launchers.sorted()}, maxProcesses=$maxProcesses)"

    /** Reading the settings. */
    companion object {
        /** The shortest token accepted. */
        const val MIN_TOKEN_LENGTH: Int = 32

        private const val MIB = 1024L * 1024
        private const val MINUTE = 60_000L
        private const val SECOND = 1_000L
        private const val MAX_PORT = 65_535
        private const val MAX_COUNT = 256
        private const val MIN_MEMORY_MIB = 64L
        private const val MAX_RETAIN_GRACE = 3_600_000L
        private const val DEFAULT_MEMORY_MIB = 512L
        private const val DEFAULT_IDLE_MINUTES = 15L
        private const val DEFAULT_LIFETIME_MINUTES = 24 * 60L
        private const val WINDOW_MINUTES = 10L
        private const val MAX_LINE_MIB = 4
        private const val MAX_REQUEST_MIB = 2
        private const val STDERR_BYTES = 64 * 1024

        private fun launchers(list: String?, defaults: Set<String>): Set<String> =
            if (list == null) defaults else list.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        private fun prlimit(value: String?, default: String?): String? = when (value) {
            null -> default
            "none" -> null
            else -> value
        }

        private fun isHttpUrl(value: String) = value.startsWith("https://") || value.startsWith("http://")

        /**
         * Reads the settings from the environment.
         *
         * @param env The process environment.
         * @throws IllegalArgumentException When a value is missing or malformed.
         */
        fun fromEnvironment(env: Map<String, String>): HostConfig {
            fun value(name: String) =
                env["OSTROM_MCP_HOST_$name"]?.let { raw -> raw.trim().takeIf { it.isNotEmpty() } }
            fun long(name: String, default: Long, unit: Long = 1): Long = value(name)?.let {
                requireNotNull(it.toLongOrNull()) { "OSTROM_MCP_HOST_$name must be a number." } * unit
            } ?: default
            fun int(name: String, default: Int) = long(name, default.toLong()).toInt()
            val defaults = HostConfig(token = "x".repeat(MIN_TOKEN_LENGTH))
            return HostConfig(
                token = requireNotNull(value("TOKEN")) { "OSTROM_MCP_HOST_TOKEN is required." },
                address = value("ADDRESS") ?: defaults.address,
                port = int("PORT", defaults.port),
                dataDir = value("DATA_DIR") ?: defaults.dataDir,
                launchers = launchers(value("LAUNCHERS"), defaults.launchers),
                npmRegistry = value("NPM_REGISTRY"),
                pypiIndex = value("PYPI_INDEX"),
                maxProcesses = int("MAX_PROCESSES", defaults.maxProcesses),
                maxInFlightPerProcess = int("MAX_IN_FLIGHT_PER_PROCESS", defaults.maxInFlightPerProcess),
                processMemoryBytes = long("PROCESS_MEMORY_MIB", defaults.processMemoryBytes, MIB),
                idleMillis = long("IDLE_MINUTES", defaults.idleMillis, MINUTE),
                startTimeoutMillis = long("START_TIMEOUT_MS", defaults.startTimeoutMillis),
                path = value("PATH") ?: defaults.path,
                prlimit = prlimit(value("PRLIMIT"), defaults.prlimit),
                drainMillis = long("DRAIN_SECONDS", defaults.drainMillis, SECOND),
                retainGraceMillis = long("RETAIN_GRACE_SECONDS", defaults.retainGraceMillis, SECOND),
            )
        }
    }
}
