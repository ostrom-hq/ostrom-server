// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlinx.serialization.Serializable

/**
 * A repository the runner clones into `/workspace/<path>` on the first execution and only fetches afterwards.
 *
 * @property url `https://github.com/<owner>/<name>[.git]`, without credentials, query or fragment.
 * @property path Relative to `/workspace`.
 * @property ref The branch, tag or commit checked out after cloning; the default branch when null.
 * @property branch A local branch created at [ref] after cloning, for example one branch per task.
 */
@Serializable
data class CloudRepository(
    val url: String,
    val path: String,
    val ref: String? = null,
    val branch: String? = null,
)

/**
 * The commands run by `/bin/sh -c` in the working directory.
 *
 * @property create Run once per workspace, in order; each is marked done on the workspace when it succeeds.
 * @property start Run on every execution (first start and every resume), in order, after [create].
 */
@Serializable
data class CloudSetup(
    val create: List<String> = emptyList(),
    val start: List<String> = emptyList(),
)

/**
 * The preparation recipe of a session (`runners.cloud.environment.v1`), as TeamCode's `CloudEnvironmentSpec`: stored
 * on the session at its creation and never edited, so a resume prepares exactly what the first execution did. It
 * holds no secret: the owner's variable values and GitHub token are resolved at every start.
 *
 * @property repositories Cloned on the first execution, fetched afterwards.
 * @property workingDirectory Relative to `/workspace`, where the setup and the agent start.
 * @property setup The setup commands.
 * @property requiredVariables The environment variables the owner must be assigned.
 * @property requireGitHub Whether the preparation fails without a usable GitHub link.
 * @property gitEmailDomain The domain of the Git e-mail address of an owner without a GitHub link.
 *
 * Serialized as is on the session's row (`runners_sessions.environment`): a field added later has a default.
 */
@Serializable
data class CloudEnvironmentSpec(
    val repositories: List<CloudRepository> = emptyList(),
    val workingDirectory: String? = null,
    val setup: CloudSetup = CloudSetup(),
    val requiredVariables: List<String> = emptyList(),
    val requireGitHub: Boolean = false,
    val gitEmailDomain: String? = null,
)

/**
 * The Git identity the agent commits with.
 *
 * @property name The `user.name`.
 * @property email The `user.email`.
 */
data class GitIdentity(val name: String, val email: String)

/**
 * What the runner receives to prepare one execution. Holds secrets: never logged nor stored.
 *
 * @property spec The recipe.
 * @property git The owner's Git identity.
 * @property variables The owner's environment variables.
 */
class PreparedEnvironment(
    val spec: CloudEnvironmentSpec,
    val git: GitIdentity,
    val variables: RunnerVariableValues,
) {
    override fun toString(): String =
        "PreparedEnvironment(repositories=${spec.repositories.size}, git=${git.name}, variables=$variables)"
}

/**
 * A preparation phase the runner reports, shown on the session.
 *
 * @property wire The stable name in the API.
 */
enum class PreparationPhase(val wire: String) {
    CONFIGURING_GIT("configuring_git"),
    CLONING_REPOSITORIES("cloning_repositories"),
    FETCHING_REPOSITORIES("fetching_repositories"),
    RUNNING_SETUP("running_setup"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param wire A reported name.
         * @return The phase, or null when the name is unknown.
         */
        fun fromWire(wire: String): PreparationPhase? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A preparation step that can fail.
 *
 * @property wire The stable name in the API.
 */
enum class PreparationStep(val wire: String) {
    GIT_IDENTITY("git_identity"),
    CLONE("clone"),
    CHECKOUT("checkout"),
    FETCH("fetch"),
    WORKING_DIRECTORY("working_directory"),
    SETUP_CREATE("setup_create"),
    SETUP_START("setup_start"),
    RUNNER("runner"),
    ;

    /** Constants and readers of the enclosing type. */
    companion object {
        /**
         * @param wire A reported name.
         * @return The step, or null when the name is unknown.
         */
        fun fromWire(wire: String): PreparationStep? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A failed step, as the runner reports it: structured fields only, no command output ever reaches the server.
 *
 * @property step The step.
 * @property index The repository or the setup command, from 0.
 * @property exitCode The command's exit code.
 */
data class PreparationFailure(val step: PreparationStep, val index: Int? = null, val exitCode: Int? = null)

/** The rules of a preparation recipe, as TeamCode's `CloudEnvironmentRules`. */
object CloudEnvironmentRules {
    /** The failure code, and the phase, of a failed preparation. */
    const val PREPARATION_FAILED = "preparation_failed"

    /** The most repositories a recipe clones. */
    const val MAX_REPOSITORIES = 16

    /** The most commands per setup list. */
    const val MAX_COMMANDS = 16

    /** The longest setup command. */
    const val MAX_COMMAND_LENGTH = 4096

    /** The most required variables. */
    const val MAX_REQUIRED_VARIABLES = 64

    /** The Git e-mail domain of an owner without a GitHub link, when the recipe names none. */
    const val DEFAULT_EMAIL_DOMAIN = "users.noreply.ostrom.invalid"

    private const val MAX_PATH_LENGTH = 255
    private const val MAX_SEGMENTS = 8

    /** A path segment: never `.`, `..` nor a hidden directory such as the runner's `.ostrom` and `.home`. */
    private val segment = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}")
    private val githubUrl = Regex("https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}")
    private val refPattern = Regex("[A-Za-z0-9._/-]{1,200}")
    private val variable = Regex("[A-Z_][A-Z0-9_]{0,127}")
    private val domain = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")

    /**
     * Checks a recipe a member asks for.
     *
     * @param spec The recipe.
     * @throws InvalidCloudSessionRequestException When a field breaks a rule.
     */
    fun validate(spec: CloudEnvironmentSpec) {
        validateRepositories(spec.repositories)
        spec.workingDirectory?.let(::validatePath)
        for (commands in listOf(spec.setup.create, spec.setup.start)) {
            ensureValid(commands.size <= MAX_COMMANDS && commands.none(::badCommand)) {
                "Use at most $MAX_COMMANDS setup commands per list, each 1 to $MAX_COMMAND_LENGTH characters."
            }
        }
        val required = spec.requiredVariables
        ensureValid(
            required.size <= MAX_REQUIRED_VARIABLES && required.all(variable::matches) &&
                required.toSet().size == required.size,
        ) { "Name at most $MAX_REQUIRED_VARIABLES distinct required variables in UPPER_SNAKE_CASE." }
        ensureValid(spec.gitEmailDomain?.let(domain::matches) != false) { "Use a DNS name for the Git email domain." }
    }

    private fun badCommand(command: String): Boolean =
        command.isBlank() || command.length > MAX_COMMAND_LENGTH || '\u0000' in command

    private fun validateRepositories(repositories: List<CloudRepository>) {
        ensureValid(repositories.size <= MAX_REPOSITORIES) { "Use at most $MAX_REPOSITORIES repositories." }
        for (repository in repositories) {
            ensureValid(githubUrl.matches(repository.url.removeSuffix(".git"))) {
                "Use an https://github.com/<owner>/<name> clone URL, without credentials, query or fragment."
            }
            validatePath(repository.path)
            repository.ref?.let(::validateRef)
            repository.branch?.let(::validateRef)
        }
        val paths = repositories.map { it.path.trimEnd('/') }
        ensureValid(paths.none { path -> paths.count { it == path || it.startsWith("$path/") } > 1 }) {
            "Clone each repository into its own directory, never inside another one."
        }
    }

    /**
     * Checks a path below `/workspace`, outside the runner's own directories: no segment starts with a dot, so
     * neither `.`, `..`, `.ostrom` nor `.home` is one.
     *
     * @param path The path.
     * @throws InvalidCloudSessionRequestException When it is not one.
     */
    fun validatePath(path: String) {
        val parts = path.trimEnd('/').split('/')
        val valid = path.length <= MAX_PATH_LENGTH && !path.startsWith("/") && parts.size <= MAX_SEGMENTS &&
            parts.all(segment::matches)
        ensureValid(valid) {
            "Use a relative path below the workspace (letters, digits, '.', '_', '-'), outside .ostrom and .home."
        }
    }

    /** Refuses option-like and malformed refs before they reach `git`. */
    private fun validateRef(ref: String) {
        val valid = refPattern.matches(ref) && !ref.startsWith("-") && !ref.startsWith("/") && !ref.endsWith("/") &&
            !ref.endsWith(".lock") && ".." !in ref && "//" !in ref
        ensureValid(valid) { "Use a valid branch, tag or commit name." }
    }

    /**
     * The directory a repository is cloned into when the member names none: the repository's name.
     *
     * @param url The clone URL.
     * @return The name, without `.git`.
     */
    fun defaultPath(url: String): String = url.substringAfterLast('/').removeSuffix(".git")

    /**
     * The recipe the runner prepares for a session: its stored recipe, the repository of `repo` and `ref` as its
     * only repository when it names none, and the first repository's directory as the working directory when none
     * is named.
     *
     * @param session The session.
     * @return The recipe.
     */
    fun effective(session: CloudSession): CloudEnvironmentSpec {
        val stored = session.environment ?: CloudEnvironmentSpec()
        val legacy = session.repository
        val repositories = stored.repositories.ifEmpty {
            listOfNotNull(legacy?.let { CloudRepository(it.url, defaultPath(it.url), it.ref) })
        }
        return stored.copy(
            repositories = repositories,
            workingDirectory = stored.workingDirectory ?: repositories.firstOrNull()?.path,
        )
    }

    /**
     * The owner's Git identity, written again on every execution: a linked GitHub account's login and no-reply
     * address, otherwise the member's login at the recipe's e-mail domain.
     *
     * @param login The owner's login.
     * @param github The owner's GitHub access.
     * @param spec The recipe.
     * @return The identity.
     */
    fun gitIdentity(login: String, github: RunnerGitHubToken, spec: CloudEnvironmentSpec): GitIdentity {
        if (github is RunnerGitHubToken.Linked) {
            val id = github.userId
            val local = if (id == null) github.login else "$id+${github.login}"
            return GitIdentity(github.login, "$local@users.noreply.github.com")
        }
        val local = login.lowercase()
            .map { if ((it.isLetterOrDigit() && it.code < ASCII) || it in "._-") it else '-' }
            .joinToString("").trim('.', '-').ifEmpty { "member" }
        return GitIdentity(login, "$local@${spec.gitEmailDomain ?: DEFAULT_EMAIL_DOMAIN}")
    }

    private const val ASCII = 128
}

/** A reported preparation failure: its checks, whether it may be retried, and the session's message. */
object PreparationFailures {
    private const val MAX_EXIT_CODE = 255

    /** The steps that may succeed on another attempt (network, GitHub); setup commands and paths fail again. */
    private val retryableSteps = setOf(PreparationStep.CLONE, PreparationStep.FETCH, PreparationStep.RUNNER)

    /**
     * Checks a reported failure's index and exit code against the recipe.
     *
     * @param failure The failure.
     * @param spec The recipe.
     * @throws InvalidCloudSessionRequestException When the index names nothing or the exit code is out of range.
     */
    fun validate(failure: PreparationFailure, spec: CloudEnvironmentSpec) {
        val count = when (failure.step) {
            PreparationStep.CLONE, PreparationStep.CHECKOUT, PreparationStep.FETCH -> spec.repositories.size
            PreparationStep.SETUP_CREATE -> spec.setup.create.size
            PreparationStep.SETUP_START -> spec.setup.start.size
            else -> 0
        }
        val validIndex = failure.index?.let { it in 0 until count } != false
        val validExit = failure.exitCode?.let { it in 0..MAX_EXIT_CODE } != false
        ensureValid(validIndex && validExit) { "Unknown preparation step index or exit code." }
    }

    /**
     * @param step A failed step.
     * @return Whether trying again may succeed.
     */
    fun retryable(step: PreparationStep): Boolean = step in retryableSteps

    /**
     * The session's message for a reported failure, written by the server: no runner text reaches the session.
     *
     * @param failure The failure.
     * @param spec The recipe.
     * @param epoch The execution, for its log's name.
     * @return The message.
     */
    fun message(failure: PreparationFailure, spec: CloudEnvironmentSpec, epoch: Long): String {
        val exit = failure.exitCode?.let { " (exit code $it)" }.orEmpty()
        val log = " Details are in /workspace/.ostrom/logs/prepare-e$epoch.log."
        val repository = failure.index?.let { spec.repositories.getOrNull(it) }
        return when (failure.step) {
            PreparationStep.GIT_IDENTITY -> "Configuring the Git identity failed$exit."
            PreparationStep.CLONE, PreparationStep.CHECKOUT, PreparationStep.FETCH ->
                repositoryMessage(failure.step, repository, exit) + log
            PreparationStep.WORKING_DIRECTORY ->
                "The working directory ${spec.workingDirectory ?: "."} does not exist after cloning."
            PreparationStep.SETUP_CREATE -> "Setup command ${(failure.index ?: 0) + 1} (create) failed$exit.$log"
            PreparationStep.SETUP_START -> "Setup command ${(failure.index ?: 0) + 1} (start) failed$exit.$log"
            PreparationStep.RUNNER -> "The runner could not prepare the environment."
        }
    }

    /** The message of a failed clone, checkout or fetch, naming the repository when the runner gave its index. */
    private fun repositoryMessage(step: PreparationStep, repository: CloudRepository?, exit: String): String {
        val github = " Private repositories need the owner's GitHub access."
        if (repository == null) {
            return when (step) {
                PreparationStep.CLONE -> "Cloning a repository into the workspace failed$exit.$github"
                PreparationStep.CHECKOUT -> "Checking out the requested ref in a repository failed$exit."
                else -> "Fetching a repository failed$exit.$github"
            }
        }
        val ref = repository.ref ?: repository.branch ?: "the requested ref"
        return when (step) {
            PreparationStep.CLONE -> "Cloning ${repository.url} into ${repository.path} failed$exit.$github"
            PreparationStep.CHECKOUT -> "Checking out $ref in ${repository.path} failed$exit."
            else -> "Fetching ${repository.path} failed$exit.$github"
        }
    }
}
