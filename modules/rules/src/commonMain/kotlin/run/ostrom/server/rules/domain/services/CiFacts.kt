// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.services

/** When a CI job runs, as a process reads it. */
enum class CiEvent {
    /** On each pull request (a GitHub `pull_request`, a GitLab merge request pipeline). */
    PULL_REQUEST,

    /** On a tag or a published release. */
    RELEASE,
}

/**
 * A CI job a process names.
 *
 * @property path The CI file.
 * @property line The line of the job.
 * @property name The job's name, as written (shown later).
 * @property event When it runs.
 * @property commands Its commands kept by [ProcessCommands.safe], with their lines; never an `env:` value, a `with:`
 *   input or a secret.
 */
data class CiJob(
    val path: String,
    val line: Int,
    val name: String,
    val event: CiEvent,
    val commands: List<Pair<Int, String>>,
)

/**
 * A CI step that sends a message to a chat service: the process ends by telling the team.
 *
 * @property path The CI file.
 * @property line The line of the step.
 * @property service The service, in the analysis's own words (`Slack`).
 */
data class CiNotice(val path: String, val line: Int, val service: String)

/**
 * What one CI file says of the team's processes.
 *
 * @property jobs Its jobs that run on pull requests or releases, at most [CiFacts.MAX_JOBS].
 * @property notices Its chat notifications.
 */
data class CiFile(val jobs: List<CiJob>, val notices: List<CiNotice>)

/**
 * Reads GitHub Actions workflows and `.gitlab-ci.yml` for the jobs a pull request or a release runs and their shell
 * commands. Only the trigger, the job names, `if`/`rules`/`only` conditions, `run:`/`script:` lines and `uses:` names
 * are read: an `env:` value, a `with:` input and `variables:` never are.
 */
object CiFacts {
    /** The jobs one CI file gives at most. */
    const val MAX_JOBS: Int = 20

    /** The commands one job gives at most. */
    const val MAX_JOB_COMMANDS: Int = 5

    /** The GitLab CI file. */
    const val GITLAB: String = ".gitlab-ci.yml"

    private val pullRequestEvents = setOf("pull_request", "pull_request_target", "merge_group")
    private val gitlabReserved = setOf(
        "default", "include", "stages", "variables", "workflow", "image", "services", "cache", "before_script",
        "after_script",
    )
    private val services = listOf(
        "slack" to "Slack",
        "discord" to "Discord",
        "teams" to "Microsoft Teams",
        "mattermost" to "Mattermost",
    )

    /**
     * Tells whether a path is a CI file this reader reads.
     *
     * @param path The path.
     * @return True for `.github/workflows/<name>.yml` (or `.yaml`) and `.gitlab-ci.yml`.
     */
    fun isCiFile(path: String): Boolean = path == GITLAB || path.startsWith(WORKFLOWS) &&
        '/' !in path.removePrefix(WORKFLOWS) && (path.endsWith(".yml") || path.endsWith(".yaml"))

    /**
     * Reads one CI file.
     *
     * @param path Its path ([isCiFile]).
     * @param text Its text.
     * @return Its jobs and notifications.
     */
    fun read(path: String, text: String): CiFile {
        val top = YamlOutline.parse(text)
        return if (path == GITLAB) gitlab(path, top) else github(path, top)
    }

    private fun github(path: String, top: List<YamlEntry>): CiFile {
        val on = top.firstOrNull { !it.item && it.key == "on" }
        val names = if (on == null) emptyList() else on.scalars().map { it.second } + on.children.mapNotNull { it.key }
        val pullRequest = names.any { it in pullRequestEvents }
        val release = "release" in names || on?.child("push")?.child("tags") != null
        val jobs = top.firstOrNull { !it.item && it.key == "jobs" }?.children.orEmpty()
        val notices = mutableListOf<CiNotice>()
        val found = jobs.mapNotNull { job ->
            val id = job.key?.takeUnless { job.item } ?: return@mapNotNull null
            val steps = job.child("steps")?.children.orEmpty().filter { it.item }
            steps.mapNotNullTo(notices) { notice(path, it) }
            val event = githubEvent(job.child("if")?.value.orEmpty(), pullRequest, release)
                ?: return@mapNotNull null
            val lines = steps.flatMap(::runLines)
            val name = job.child("name")?.value?.takeUnless { "\${{" in it } ?: id
            CiJob(path, job.line, name, event, ProcessCommands.commands(lines).take(MAX_JOB_COMMANDS))
        }
        return CiFile(found.take(MAX_JOBS), notices.distinctBy { it.service })
    }

    // The chat service a step's action sends to, if any.
    private fun notice(path: String, step: YamlEntry): CiNotice? {
        val uses = step.fields().firstOrNull { it.key == "uses" }?.value?.lowercase().orEmpty()
        return services.firstOrNull { it.first in uses }?.let { CiNotice(path, step.line, it.second) }
    }

    // A step's `run:` lines, on its own line or as a block.
    private fun runLines(step: YamlEntry): List<Pair<Int, String>> = step.fields().filter { it.key == "run" }
        .flatMap { run -> listOfNotNull(run.value?.let { run.line to it }) + run.block }

    // A job's own condition wins: on tags or releases only, it is a release job; on a branch or a push only, it is
    // neither a pull request's nor a release's.
    private fun githubEvent(condition: String, pullRequest: Boolean, release: Boolean): CiEvent? = when {
        "refs/tags" in condition || "'release'" in condition -> CiEvent.RELEASE
        "refs/heads" in condition || "'push'" in condition -> null
        pullRequest -> CiEvent.PULL_REQUEST
        release -> CiEvent.RELEASE
        else -> null
    }

    private fun gitlab(path: String, top: List<YamlEntry>): CiFile {
        val found = top.mapNotNull { job ->
            val id = job.key?.takeUnless { job.item || it in gitlabReserved || it.startsWith(".") }
                ?: return@mapNotNull null
            val script = job.child("script") ?: return@mapNotNull null
            val event = gitlabEvent(job) ?: return@mapNotNull null
            val lines = script.scalars()
            CiJob(path, job.line, id, event, ProcessCommands.commands(lines).take(MAX_JOB_COMMANDS))
        }
        return CiFile(found.take(MAX_JOBS), emptyList())
    }

    // A job without `rules` or `only` runs in every pipeline, a merge request's included.
    private fun gitlabEvent(job: YamlEntry): CiEvent? {
        val rules = job.child("rules")
        val only = job.child("only")
        if (rules == null && only == null) return CiEvent.PULL_REQUEST
        val conditions = rules?.children.orEmpty().filter { it.item }
            .flatMap { rule -> rule.fields().filter { it.key == "if" }.mapNotNull { it.value } }
        val refs = only?.let { it.scalars() + it.child("refs")?.scalars().orEmpty() }.orEmpty().map { it.second }
        val text = conditions.joinToString(" ")
        return when {
            "CI_COMMIT_TAG" in text || "tags" in refs -> CiEvent.RELEASE
            "merge_request" in text || "merge_requests" in refs -> CiEvent.PULL_REQUEST
            else -> null
        }
    }

    private const val WORKFLOWS = ".github/workflows/"
}
