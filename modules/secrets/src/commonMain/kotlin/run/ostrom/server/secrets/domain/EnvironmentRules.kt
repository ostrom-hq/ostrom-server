// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The validation rules and limits of environment variants (TeamCode's `VariantService`, environment kind). */
object EnvironmentRules {
    /** Team variants per organization. */
    const val TEAM_LIMIT: Int = 500

    /** Personal variants per member. */
    const val PERSONAL_LIMIT: Int = 20

    /** The most members a bulk assignment reaches (one statement binds them all). */
    const val BULK_MEMBERS: Int = 10_000

    /** The most assignments, and the most exclusions, one page lists. */
    const val ASSIGNMENTS_PAGE: Int = 5_000

    /** The longest value, in characters. */
    const val MAX_VALUE_LENGTH: Int = 16_384

    /** The longest variant name. */
    const val MAX_NAME_LENGTH: Int = 120

    private val variablePattern = Regex("[A-Z_][A-Z0-9_]{0,127}")

    /**
     * Names that would let a value take over the process, its loader, its proxies or the agent's own connection:
     * TeamCode's list, then (Nathan, 14/11/2026, also proposed upstream) what else steers the loader or a runtime, the
     * shell, Git, SSH and `gh`, or an agent's tools. Most come from the CLI's `ProgramVariables.PINNED` and the MCP
     * host's `Launchers`; the runner withholds at least these.
     */
    val RESERVED_NAMES: Set<String> = setOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV",
        "PROMPT_COMMAND", "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
        "PYTHONPATH", "PYTHONSTARTUP", "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "GIT_SSH",
        "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
        "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
        // the loader and the runtimes: glibc's tunables, TLS checks and key logs, code a runtime loads
        "GLIBC_TUNABLES", "NODE_TLS_REJECT_UNAUTHORIZED", "NODE_ICU_DATA", "ELECTRON_RUN_AS_NODE", "PYTHONUSERBASE",
        "PYTHONEXECUTABLE", "PYTHONPLATLIBDIR", "PYTHONBREAKPOINT", "PERL5DB", "CLASSPATH", "DOTNET_STARTUP_HOOKS",
        "CORECLR_PROFILER", "CORECLR_PROFILER_PATH", "CORECLR_ENABLE_PROFILING", "CURL_CA_BUNDLE", "SSLKEYLOGFILE",
        "GCONV_PATH",
        // the shell
        "OLDPWD", "SHELLOPTS", "BASHOPTS", "CDPATH", "GLOBIGNORE", "PS4", "ZDOTDIR",
        // Git, SSH and the GitHub CLI: the programs they run and where they read a repository
        "GIT_TERMINAL_PROMPT", "GH_CONFIG_DIR", "GIT_ASKPASS", "SSH_ASKPASS", "GIT_PROXY_COMMAND", "GIT_EXTERNAL_DIFF",
        "GIT_PAGER", "GIT_EDITOR", "GIT_SEQUENCE_EDITOR", "GIT_TEMPLATE_DIR", "GIT_DIR", "GIT_WORK_TREE",
        "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_COMMON_DIR",
        // what weakens Git's TLS or protocols
        "GIT_SSL_NO_VERIFY", "GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "GIT_ALLOW_PROTOCOL",
        // an agent's tools: ripgrep's file can name a `--pre` program
        "RIPGREP_CONFIG_PATH",
    )

    /**
     * Prefixes reserved the same way; `OSTROM_` and `TEAMCODE_` are the agent's own connection to the server, and
     * `BASH_FUNC_` names an exported shell function.
     */
    val RESERVED_PREFIXES: List<String> = listOf(
        "LD_", "DYLD_", "OSTROM_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_",
        "BASH_FUNC_",
    )

    /**
     * Checks a variable name.
     *
     * @throws InvalidEnvironmentVariantException When it is malformed or reserved.
     */
    fun requireVariable(variable: String) {
        requireWellFormed(variable)
        if (isReserved(variable)) {
            throw InvalidEnvironmentVariantException(
                "$variable is reserved for the runtime, its loader or the agent's own connection.",
            )
        }
    }

    /**
     * Checks a variable name's form only: what removing an assignment or an exclusion needs, so a name reserved
     * after it was stored can still be cleaned up.
     *
     * @throws InvalidEnvironmentVariantException When it is malformed.
     */
    fun requireWellFormed(variable: String) {
        if (!variablePattern.matches(variable)) {
            throw InvalidEnvironmentVariantException(
                "Use an environment variable name of uppercase letters, digits and underscores (at most 128).",
            )
        }
    }

    /** Whether a name is reserved. */
    fun isReserved(variable: String): Boolean =
        variable in RESERVED_NAMES || RESERVED_PREFIXES.any { variable.startsWith(it) }

    /**
     * Checks a variant name.
     *
     * @throws InvalidEnvironmentVariantException When it is blank, too long or holds a control character.
     */
    fun requireName(name: String) {
        if (name.isBlank() || name.length > MAX_NAME_LENGTH || name.any(Char::isISOControl)) {
            throw InvalidEnvironmentVariantException(
                "Use a variant name of 1 to $MAX_NAME_LENGTH characters without control characters.",
            )
        }
    }

    /**
     * Checks a value.
     *
     * @throws InvalidEnvironmentVariantException When it is empty, too long or holds a NUL character.
     */
    fun requireValue(value: SecretValue) {
        val text = value.reveal()
        if (text.isEmpty() || text.length > MAX_VALUE_LENGTH || '\u0000' in text) {
            throw InvalidEnvironmentVariantException(
                "An environment value is 1 to $MAX_VALUE_LENGTH characters, without NUL.",
            )
        }
    }
}

/** A malformed variant, assignment or value (400 `invalid_environment_variant`). */
class InvalidEnvironmentVariantException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_environment_variant")

/** No such variant in the caller's reach (404). */
class EnvironmentVariantNotFoundException :
    OstromException(ErrorKind.NOT_FOUND, "Environment variant not found.")

/** The variant changed since it was read (409 `variant_version_conflict`). */
class VariantVersionConflictException : OstromException(
    ErrorKind.CONFLICT,
    "The variant changed since you read it: read it again, then send your change.",
    "variant_version_conflict",
)

/** Another variant of the same owner already has this name for the variable (409 `variant_exists`). */
class VariantExistsException : OstromException(
    ErrorKind.CONFLICT,
    "A variant with this name already exists for this variable.",
    "variant_exists",
)

/** Too many variants (409 `variant_limit`). */
class VariantLimitException(limit: Int) : OstromException(
    ErrorKind.CONFLICT,
    "At most $limit variants.",
    "variant_limit",
)

/** The member is excluded from the variable (409 `variant_excluded`). */
class VariantExcludedException : OstromException(
    ErrorKind.CONFLICT,
    "This member is excluded from this variable: remove the exclusion first.",
    "variant_excluded",
)

/** A personal variant, which only its owner edits or assigns (409 `variant_personal`). */
class VariantPersonalException : OstromException(
    ErrorKind.CONFLICT,
    "This is a member's personal variant: only its owner changes it; an administrator may promote or delete it.",
    "variant_personal",
)

/** A per-member variant cannot be assigned in bulk (409 `variant_not_shared`). */
class VariantNotSharedException : OstromException(
    ErrorKind.CONFLICT,
    "Only a shared variant is assigned in bulk: a per-member one needs each member's value.",
    "variant_not_shared",
)

/** The member already has a selection for the variable (409 `slot_taken`). */
class SlotTakenException : OstromException(
    ErrorKind.CONFLICT,
    "You already have a value for this variable: an administrator assigned it, or you declared one.",
    "slot_taken",
)

/** No such member in the organization (404). */
class MemberNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Member not found.")
