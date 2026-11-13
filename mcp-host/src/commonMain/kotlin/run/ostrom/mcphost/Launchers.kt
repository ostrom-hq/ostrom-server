// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlin.time.Instant

/**
 * The directories of one key, below [HostConfig.dataDir]: each process has its own home and caches, so a package
 * cannot poison another key's cache.
 *
 * @property root The key's directory.
 */
class ProcessDirs(val root: String) {
    /** `HOME`. */
    val home: String = "$root/home"

    /** The working directory. */
    val work: String = "$root/work"

    /** `TMPDIR`. */
    val tmp: String = "$root/tmp"

    /** npm's cache. */
    val npmCache: String = "$root/npm-cache"

    /** uv's cache. */
    val uvCache: String = "$root/uv-cache"

    /** uv's tools. */
    val uvTools: String = "$root/uv-tools"

    /** uv's tool binaries. */
    val uvBin: String = "$root/uv-bin"

    /** The Python uv installs. */
    val python: String = "$root/python"

    /** Every directory, made before a start. */
    val all: List<String> get() = listOf(home, work, tmp, npmCache, uvCache, uvTools, uvBin, python)
}

/**
 * The exact argv, environment and working directory of a process. No shell is involved anywhere.
 *
 * @property command The program and its arguments.
 * @property environment The whole environment.
 * @property workDir The working directory.
 * @property secrets The secret values, scrubbed from every output.
 */
class LaunchPlan(
    val command: List<String>,
    val environment: Map<String, String>,
    val workDir: String,
    val secrets: Collection<String>,
)

/**
 * Turns a [ProcessSpec] into a [LaunchPlan] after checking again every rule the relay checks when an administrator
 * declares the server: a configured launcher, an exactly pinned package, bounded arguments without control characters,
 * variable names that cannot take over the runtime, the registry or the dependency cut-off, and secrets long enough to
 * be scrubbed.
 *
 * @param config The host's settings.
 */
class Launchers(private val config: HostConfig) {
    /**
     * Plans one start.
     *
     * @param spec The definition.
     * @param dirs The key's directories.
     * @throws HostFailure [HostErrors.INVALID_SPEC] on the first broken rule.
     */
    fun plan(spec: ProcessSpec, dirs: ProcessDirs): LaunchPlan {
        ensure(spec.launcher in config.launchers) {
            "This host runs only ${config.launchers.sorted().joinToString(" and ")} servers."
        }
        val npm = spec.launcher == NPM
        ensure((if (npm) npmPackage else pypiPackage).matches(spec.pkg)) {
            if (npm) {
                "Name an npm package with its exact version, such as @scope/name@1.2.3 (no range, tag or URL)."
            } else {
                "Name a PyPI package with its exact version, such as name==1.2.3 (no range, wildcard or URL)."
            }
        }
        ensure(spec.declaredAt > 0) { "The declaration date is missing." }
        validateVariables(spec)
        val args = arguments(spec)
        val before = Instant.fromEpochMilliseconds(spec.declaredAt).toString()
        val environment = linkedMapOf(
            "PATH" to config.path,
            "HOME" to dirs.home,
            "TMPDIR" to dirs.tmp,
            "LANG" to "C.UTF-8",
        )
        val command = mutableListOf<String>()
        config.prlimit?.let {
            command += listOf(it, "--nproc=${config.maxPids}", "--nofile=${config.maxOpenFiles}", "--")
        }
        if (npm) {
            command += listOf(config.npx, "--yes", "--", spec.pkg)
            environment += mapOf(
                "npm_config_cache" to dirs.npmCache,
                // Transitive dependencies resolve as on the declaration day: a version published later is never taken.
                "npm_config_before" to before,
                "npm_config_ignore_scripts" to "true",
                "npm_config_yes" to "true",
                "npm_config_update_notifier" to "false",
                "npm_config_fund" to "false",
                "npm_config_audit" to "false",
            )
            config.npmRegistry?.let { environment["npm_config_registry"] = it }
        } else {
            command += listOf(config.uvx, "--exclude-newer", before)
            config.pypiIndex?.let { command += listOf("--default-index", it) }
            command += spec.pkg
            environment += mapOf(
                "UV_CACHE_DIR" to dirs.uvCache,
                "UV_TOOL_DIR" to dirs.uvTools,
                "UV_TOOL_BIN_DIR" to dirs.uvBin,
                "UV_PYTHON_INSTALL_DIR" to dirs.python,
                "UV_NO_CONFIG" to "1",
                "UV_NO_PROGRESS" to "1",
            )
        }
        command += args
        environment += spec.env
        environment += spec.secrets
        return LaunchPlan(command, environment, dirs.work, spec.secrets.values)
    }

    private fun validateVariables(spec: ProcessSpec) {
        for ((name, value) in spec.env + spec.secrets) {
            ensure(envName.matches(name)) {
                "Use environment variable names of uppercase letters, digits and underscores."
            }
            ensure(name !in reservedNames && reservedPrefixes.none { name.startsWith(it) }) {
                "$name is reserved for the runtime, the package manager or Ostrom."
            }
            ensure('\u0000' !in value) { "The value of $name contains a NUL character." }
        }
        val both = spec.env.keys.firstOrNull { it in spec.secrets }
        ensure(both == null) { "$both is both a plain and a secret variable." }
        ensure(spec.secrets.size <= MAX_SECRETS) { "Use at most $MAX_SECRETS secret variables." }
        ensure(spec.secrets.entries.sumOf { it.key.length + it.value.encodeToByteArray().size } <= SECRET_BYTES) {
            "The secret variables exceed $SECRET_BYTES bytes."
        }
        val short = spec.secrets.keys.firstOrNull { spec.secrets.getValue(it).length < MIN_SECRET_LENGTH }
        ensure(short == null) {
            "The secret $short must hold at least $MIN_SECRET_LENGTH characters, so it can be scrubbed from outputs."
        }
    }

    private fun arguments(spec: ProcessSpec): List<String> {
        ensure(spec.args.size <= MAX_ARGS) { "Use at most $MAX_ARGS arguments." }
        return spec.args.map { arg ->
            ensure(arg.length <= MAX_ARG_LENGTH) { "An argument exceeds $MAX_ARG_LENGTH characters." }
            ensure(arg.none { it.isISOControl() }) { "An argument contains a control character." }
            reference.replace(arg) { match ->
                spec.secrets[match.groupValues[1]]
                    ?: throw invalid("\${${match.groupValues[1]}} does not name a secret variable of this server.")
            }
        }
    }

    private fun invalid(message: String) = HostFailure(HostErrors.INVALID_SPEC, message)

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) throw invalid(message())
    }

    /** The launchers and the rules. */
    companion object {
        /** npm packages, through `npx`. */
        const val NPM: String = "npm"

        /** PyPI packages, through `uvx`. */
        const val PYPI: String = "pypi"

        /** Every launcher. */
        val ALL: Set<String> = setOf(NPM, PYPI)

        /** The most arguments. */
        const val MAX_ARGS: Int = 32

        /** The longest argument. */
        const val MAX_ARG_LENGTH: Int = 512

        /** The most secret variables. */
        const val MAX_SECRETS: Int = 16

        /** The secret variables' total size, names included. */
        const val SECRET_BYTES: Int = 16_384

        /** The shortest secret value: a shorter one would mangle ordinary output once scrubbed. */
        const val MIN_SECRET_LENGTH: Int = 8

        private const val VERSION = """(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""" +
            """(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?"""

        /** `name@1.2.3` or `@scope/name@1.2.3`: an exact semantic version, never a range, a tag, a path or a URL. */
        private val npmPackage = Regex("""(@[a-z0-9][a-z0-9._~-]*/)?[A-Za-z0-9][A-Za-z0-9._~-]*@$VERSION""")

        /** `name==1.2.3`, extras allowed: an exact PEP 440 version, never a range, a wildcard or a URL. */
        private val pypiPackage = Regex(
            """[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?(\[[A-Za-z0-9._-]+(,[A-Za-z0-9._-]+)*])?""" +
                """==[0-9][0-9A-Za-z.!+-]*""",
        )
        private val reference = Regex("""\$\{([A-Z_][A-Z0-9_]{0,127})}""")
        private val envName = Regex("[A-Z_][A-Z0-9_]{0,127}")

        /** The relay's reserved names, plus what would redirect npm, uv or pip, the loader or TLS checks. */
        private val reservedNames = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV",
            "PROMPT_COMMAND", "LANG", "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP", "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB",
            "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL", "HTTP_PROXY", "HTTPS_PROXY",
            "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
            "NODE_TLS_REJECT_UNAUTHORIZED", "NODE_ICU_DATA", "NODE_V8_COVERAGE", "GLIBC_TUNABLES", "CURL_CA_BUNDLE",
            "SSLKEYLOGFILE", "PYTHONUSERBASE", "PYTHONEXECUTABLE", "PYTHONPLATLIBDIR",
        )
        private val reservedPrefixes = listOf(
            "LD_", "DYLD_", "OSTROM_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_",
            "NPM_CONFIG_", "UV_", "PIP_", "NODE_", "PYTHON", "COREPACK_", "SSL", "CURL_",
        )
    }
}
