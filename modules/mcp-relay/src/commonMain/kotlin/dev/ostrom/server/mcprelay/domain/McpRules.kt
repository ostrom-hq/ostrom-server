// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** Validation of what an admin declares; the MCP host checks the stdio rules again. */
object McpRules {
    /** The most servers per org, and per member listing. */
    const val MAX_SERVERS: Int = 64

    /** The most tools listed per server and allowlisted per assignment. */
    const val MAX_TOOLS: Int = 256

    /** Names kept for the routes next to `POST /api/v1/mcp/{server}`. */
    val RESERVED_NAMES: Set<String> = setOf("servers", "settings", "calls", "usage")

    /** The most argument policies per server. */
    const val MAX_POLICIES: Int = 64

    private const val MAX_URL = 2048
    private const val MAX_ARGS = 32
    private const val MAX_ARG = 512
    private const val MAX_VARIABLES = 48
    private const val MAX_SECRET_VARIABLES = 16
    private const val MAX_VALUE = 1024
    private const val MAX_CONDITIONS = 16
    private const val MAX_MESSAGE = 500
    private const val MAX_PATH = 256

    private val serverName = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val toolName = Regex("[A-Za-z0-9_.-]{1,128}")
    private val prefix = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private val variable = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val npmPackage =
        Regex("(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*@\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.+-]+)?")
    private val pypiPackage = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(\\[[A-Za-z0-9,._-]+])?==\\d+(\\.\\d+)*([a-z0-9.+-]*)")
    private val secretReference = Regex("\\$\\{([A-Z0-9_]+)}")
    private val headerName = Regex("[A-Za-z0-9-]{1,64}")
    private val forbiddenHeaders = setOf(
        "host",
        "cookie",
        "content-length",
        "content-type",
        "mcp-session-id",
        "mcp-protocol-version",
        "transfer-encoding",
        "connection",
    )
    private val reservedPrefixes = listOf(
        "LD_", "OSTROM_", "TEAMCODE_", "NPM_CONFIG_", "UV_", "PIP_", "NODE_", "PYTHON", "COREPACK_", "SSL", "CURL_",
        "DYLD_",
    )
    private val reservedNames = setOf(
        "PATH", "HOME", "TMPDIR", "LANG", "SHELL", "USER", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
        "GLIBC_TUNABLES", "REQUESTS_CA_BUNDLE",
    )
    private val valueless = setOf(ConditionOperator.EXISTS, ConditionOperator.ABSENT)

    /** Whether a tool name is one the relay serves (upstream tools with other names are skipped). */
    fun isToolName(name: String): Boolean = toolName.matches(name)

    /**
     * Checks a server definition.
     *
     * @throws InvalidMcpServerException On the first broken rule.
     */
    fun requireServer(server: McpServer) {
        rule(
            !OstromMcpServers.isOwn(server.name),
            "`${server.name.trim().lowercase()}` is Ostrom's own MCP server, which every agent already has: choose " +
                "another name.",
        )
        rule(
            serverName.matches(server.name) && server.name !in RESERVED_NAMES,
            "A server name is 1 to 64 characters of a-z 0-9 . _ - and starts with a letter or digit (not `servers`).",
        )
        server.credentialHeader?.let {
            rule(headerName.matches(it) && it.lowercase() !in forbiddenHeaders, "The credential header is not allowed.")
        }
        when (server.transport) {
            McpTransport.HTTP -> requireHttp(server)
            McpTransport.STDIO -> requireStdioServer(server)
        }
    }

    private fun requireHttp(server: McpServer) {
        val url = server.url ?: invalid("An HTTP server needs its URL.")
        val wellFormed = url.startsWith("https://") && url.length <= MAX_URL
        rule(
            wellFormed &&
                url.none {
                    it.isWhitespace() || it.isISOControl()
                },
            "An HTTP server URL starts with https:// and has at most $MAX_URL characters.",
        )
        rule(server.stdio == null, "An HTTP server has no command.")
    }

    private fun requireStdioServer(server: McpServer) {
        val spec = server.stdio ?: invalid("A stdio server needs its command.")
        rule(
            server.url == null && server.credentialSecretId == null && server.credentialHeader == null,
            "A stdio server takes its credentials as secret variables, not a URL or header.",
        )
        requireStdio(spec)
    }

    private fun requireStdio(spec: StdioSpec) {
        val pinned = when (spec.launcher) {
            StdioLauncher.NPM -> npmPackage.matches(spec.packageName)
            StdioLauncher.PYPI -> pypiPackage.matches(spec.packageName)
        }
        rule(pinned, "The package is pinned to one exact version (name@1.2.3 for npm, name==1.2.3 for PyPI).")
        rule(
            spec.args.size <= MAX_ARGS &&
                spec.args.none {
                    it.length > MAX_ARG || it.any(Char::isISOControl)
                },
            "At most $MAX_ARGS arguments of at most $MAX_ARG characters, without control characters.",
        )
        requireVariables(spec)
        for (reference in secretReference.findAll(spec.args.joinToString(" "))) {
            val name = reference.groupValues[1]
            rule(name in spec.secretEnv, "\${$name} must name a secret variable.")
        }
    }

    private fun requireVariables(spec: StdioSpec) {
        rule(spec.env.size + spec.secretEnv.size <= MAX_VARIABLES, "At most $MAX_VARIABLES variables.")
        rule(spec.env.keys.none { it in spec.secretEnv.keys }, "A variable is either visible or secret.")
        for (name in spec.env.keys + spec.secretEnv.keys) {
            rule(
                variable.matches(name) &&
                    name !in reservedNames &&
                    reservedPrefixes.none {
                        name.startsWith(it)
                    },
                "The variable $name is not allowed.",
            )
        }
        rule(
            spec.env.values.none {
                it.length > MAX_VALUE || '\u0000' in it
            },
            "A variable value has at most $MAX_VALUE characters, without NUL.",
        )
        rule(spec.secretEnv.size <= MAX_SECRET_VARIABLES, "At most $MAX_SECRET_VARIABLES secret variables.")
    }

    /**
     * Checks an allowlist.
     *
     * @throws InvalidMcpServerException When it is too long or names an invalid tool.
     */
    fun requirePolicy(policy: ToolPolicy) {
        val tools = policy.tools.orEmpty()
        rule(
            tools.size <= MAX_TOOLS &&
                tools.all {
                    isToolName(it)
                },
            "An allowlist has at most $MAX_TOOLS valid tool names.",
        )
    }

    /**
     * Checks an argument policy.
     *
     * @throws InvalidMcpServerException On the first broken rule.
     */
    fun requireArgumentPolicy(policy: ArgumentPolicy) {
        rule(policy.tool == ArgumentPolicy.ANY_TOOL || isToolName(policy.tool), "The policy names an invalid tool.")
        rule(policy.conditions.size <= MAX_CONDITIONS, "At most $MAX_CONDITIONS conditions per policy.")
        rule(
            policy.message.isNotBlank() && policy.message.length <= MAX_MESSAGE,
            "A policy explains itself in 1 to $MAX_MESSAGE characters.",
        )
        for (condition in policy.conditions) {
            rule(
                condition.path.isNotBlank() && condition.path.length <= MAX_PATH,
                "A condition path has 1 to $MAX_PATH characters.",
            )
            if (condition.operator in valueless) continue
            val value = condition.value
            if (value == null || value.length > MAX_VALUE) {
                invalid("This condition needs a value of at most $MAX_VALUE characters.")
            }
            rule(
                condition.operator != ConditionOperator.MATCHES || SafePattern.isSafe(value),
                "A pattern may not repeat a group that repeats or alternates, refer back to a group, nor repeat " +
                    "without bound more than twice (it could run for minutes).",
            )
        }
    }

    /**
     * Checks the agent prefix.
     *
     * @throws InvalidMcpServerException When it is not 1 to 32 characters of a-z 0-9 _ -.
     */
    fun requirePrefix(value: String) {
        rule(
            prefix.matches(value),
            "The prefix is 1 to 32 characters of a-z 0-9 _ - and starts with a letter or digit.",
        )
    }

    /**
     * The name the agent gives a server: `<prefix>-<server>`, with `.` replaced since agents refuse it.
     *
     * @param prefix The org's prefix.
     * @param server The server name.
     */
    fun agentName(prefix: String, server: String): String = "$prefix-${server.replace('.', '_')}"
}

/** Refuses with [message] unless [holds]. */
private fun rule(holds: Boolean, message: String) {
    if (!holds) invalid(message)
}

private fun invalid(message: String): Nothing = throw InvalidMcpServerException(message)

/** A malformed server, assignment, policy or setting. */
class InvalidMcpServerException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** No server with this id in the org. */
class McpServerNotFoundException : OstromException(ErrorKind.NOT_FOUND, "MCP server not found.")

/** The org already has a server with this name, or one the agent would see under the same name. */
class McpServerNameTakenException(message: String = "An MCP server with this name already exists.") :
    OstromException(ErrorKind.CONFLICT, message)

/** The secret named by a server or assignment is not an org secret. */
class McpSecretNotFoundException : OstromException(ErrorKind.INVALID_REQUEST, "The credential names an unknown secret.")

/** A stdio server is declared but no MCP host is configured. */
class McpHostDisabledException :
    OstromException(
        ErrorKind.CONFLICT,
        "No MCP host is configured on this server (OSTROM_MCP_HOST_URL), so it cannot run a stdio server.",
    )
