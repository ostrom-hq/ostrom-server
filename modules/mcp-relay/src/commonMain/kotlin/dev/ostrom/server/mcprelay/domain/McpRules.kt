package dev.ostrom.server.mcprelay.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** Validation of what an admin declares; the MCP host checks the stdio rules again. */
object McpRules {
    /** The most servers per org, and per member listing. */
    const val MAX_SERVERS: Int = 64

    /** The most tools listed per server and allowlisted per assignment. */
    const val MAX_TOOLS: Int = 256

    /** The name kept for the list route `/v1/mcp/servers`. */
    const val RESERVED_NAME: String = "servers"

    /** The most argument policies per server. */
    const val MAX_POLICIES: Int = 64

    private val serverName = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val toolName = Regex("[A-Za-z0-9_.-]{1,128}")
    private val prefix = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private val variable = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val npmPackage = Regex("(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*@\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.+-]+)?")
    private val pypiPackage = Regex("[A-Za-z0-9][A-Za-z0-9._-]*(\\[[A-Za-z0-9,._-]+])?==\\d+(\\.\\d+)*([a-z0-9.+-]*)")
    private val headerName = Regex("[A-Za-z0-9-]{1,64}")
    private val forbiddenHeaders = setOf("host", "cookie", "content-length", "content-type", "mcp-session-id", "mcp-protocol-version", "transfer-encoding", "connection")
    private val reservedPrefixes = listOf("LD_", "OSTROM_", "TEAMCODE_", "NPM_CONFIG_", "UV_", "PIP_", "NODE_", "PYTHON", "COREPACK_", "SSL", "CURL_", "DYLD_")
    private val reservedNames = setOf("PATH", "HOME", "TMPDIR", "LANG", "SHELL", "USER", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "GLIBC_TUNABLES", "REQUESTS_CA_BUNDLE")

    /** Whether a tool name is one the relay serves (upstream tools with other names are skipped). */
    fun isToolName(name: String): Boolean = toolName.matches(name)

    /**
     * Checks a server definition.
     *
     * @throws InvalidMcpServerException On the first broken rule.
     */
    fun requireServer(server: McpServer) {
        if (!serverName.matches(server.name) || server.name == RESERVED_NAME) {
            invalid("A server name is 1 to 64 characters of a-z 0-9 . _ - and starts with a letter or digit (not `servers`).")
        }
        server.credentialHeader?.let {
            if (!headerName.matches(it) || it.lowercase() in forbiddenHeaders) invalid("The credential header is not allowed.")
        }
        when (server.transport) {
            McpTransport.HTTP -> {
                val url = server.url ?: invalid("An HTTP server needs its URL.")
                if (!url.startsWith("https://") || url.length > 2048 || url.any { it.isWhitespace() || it.isISOControl() }) {
                    invalid("An HTTP server URL starts with https:// and has at most 2048 characters.")
                }
                if (server.stdio != null) invalid("An HTTP server has no command.")
            }
            McpTransport.STDIO -> {
                val spec = server.stdio ?: invalid("A stdio server needs its command.")
                if (server.url != null || server.credentialSecretId != null || server.credentialHeader != null) {
                    invalid("A stdio server takes its credentials as secret variables, not a URL or header.")
                }
                requireStdio(spec)
            }
        }
    }

    private fun requireStdio(spec: StdioSpec) {
        val pinned = when (spec.launcher) {
            StdioLauncher.NPM -> npmPackage.matches(spec.packageName)
            StdioLauncher.PYPI -> pypiPackage.matches(spec.packageName)
        }
        if (!pinned) invalid("The package is pinned to one exact version (name@1.2.3 for npm, name==1.2.3 for PyPI).")
        if (spec.args.size > 32 || spec.args.any { it.length > 512 || it.any(Char::isISOControl) }) {
            invalid("At most 32 arguments of at most 512 characters, without control characters.")
        }
        val names = spec.env.keys + spec.secretEnv.keys
        if (spec.env.size + spec.secretEnv.size > 48) invalid("At most 48 variables.")
        if (spec.env.keys.any { it in spec.secretEnv.keys }) invalid("A variable is either visible or secret.")
        for (name in names) {
            if (!variable.matches(name) || name in reservedNames || reservedPrefixes.any { name.startsWith(it) }) {
                invalid("The variable $name is not allowed.")
            }
        }
        if (spec.env.values.any { it.length > 1024 || '\u0000' in it }) invalid("A variable value has at most 1024 characters, without NUL.")
        if (spec.secretEnv.size > 16) invalid("At most 16 secret variables.")
        for (reference in Regex("\\$\\{([A-Z0-9_]+)}").findAll(spec.args.joinToString(" "))) {
            if (reference.groupValues[1] !in spec.secretEnv) invalid("\${${reference.groupValues[1]}} must name a secret variable.")
        }
    }

    /**
     * Checks an allowlist.
     *
     * @throws InvalidMcpServerException When it is too long or names an invalid tool.
     */
    fun requirePolicy(policy: ToolPolicy) {
        val tools = policy.tools ?: return
        if (tools.size > MAX_TOOLS || tools.any { !isToolName(it) }) invalid("An allowlist has at most 256 valid tool names.")
    }

    /**
     * Checks an argument policy.
     *
     * @throws InvalidMcpServerException On the first broken rule.
     */
    fun requireArgumentPolicy(policy: ArgumentPolicy) {
        if (policy.tool != ArgumentPolicy.ANY_TOOL && !isToolName(policy.tool)) invalid("The policy names an invalid tool.")
        if (policy.conditions.size > 16) invalid("At most 16 conditions per policy.")
        if (policy.message.isBlank() || policy.message.length > 500) invalid("A policy explains itself in 1 to 500 characters.")
        for (condition in policy.conditions) {
            if (condition.path.isBlank() || condition.path.length > 256) invalid("A condition path has 1 to 256 characters.")
            val needsValue = condition.operator !in setOf(ConditionOperator.EXISTS, ConditionOperator.ABSENT)
            if (needsValue && (condition.value == null || condition.value.length > 1024)) invalid("This condition needs a value of at most 1024 characters.")
        }
    }

    /**
     * Checks the agent prefix.
     *
     * @throws InvalidMcpServerException When it is not 1 to 32 characters of a-z 0-9 _ -.
     */
    fun requirePrefix(value: String) {
        if (!prefix.matches(value)) invalid("The prefix is 1 to 32 characters of a-z 0-9 _ - and starts with a letter or digit.")
    }

    /**
     * The name the agent gives a server: `<prefix>-<server>`, with `.` replaced since agents refuse it.
     *
     * @param prefix The org's prefix.
     * @param server The server name.
     */
    fun agentName(prefix: String, server: String): String = "$prefix-${server.replace('.', '_')}"

    private fun invalid(message: String): Nothing = throw InvalidMcpServerException(message)
}

/** A malformed server, assignment, policy or setting. */
class InvalidMcpServerException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** No server with this id in the org. */
class McpServerNotFoundException : OstromException(ErrorKind.NOT_FOUND, "MCP server not found.")

/** The org already has a server with this name. */
class McpServerNameTakenException : OstromException(ErrorKind.CONFLICT, "An MCP server with this name already exists.")

/** The secret named by a server or assignment is not an org secret. */
class McpSecretNotFoundException : OstromException(ErrorKind.INVALID_REQUEST, "The credential names an unknown secret.")

/** A stdio server is declared but no MCP host is configured. */
class McpHostDisabledException : OstromException(ErrorKind.CONFLICT, "No MCP host is configured on this server (OSTROM_MCP_HOST_URL), so it cannot run a stdio server.")
