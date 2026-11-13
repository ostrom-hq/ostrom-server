// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.models

/**
 * What a yes covers. Exactly one of [push] and [pullRequest] is set for a push or a merge; neither for any other
 * action (a deploy, a migration), which is then bound to its normalized [command] within its [scope].
 *
 * @property repo The repository the session declared (`owner/name`), or null outside a repository.
 * @property realRepo The repository the command really runs in, when the hook read it and it differs.
 * @property actionClass The boundary action class (`push-main`, `merge`, `deploy`, `custom-<slug>`…).
 * @property command The command, whitespace-normalized; part of the key only for actions without push or merge.
 * @property scope `session:<id>` (the yes serves that session) or `sha:<sha>` (a GitHub check, shared).
 * @property push The refs a push sends, read by the hook before it runs.
 * @property pullRequest The pull request a merge targets, its head and base read from GitHub by the server.
 * @property commits The commits shown to the approvers, oldest first.
 * @property diffDigest The digest of the diff shown with a push: another diff asks another yes.
 * @property tool The agent tool that runs the action (`Bash`, `mcp__github__merge_pull_request`…); shown, not keyed.
 * @property harnessVersion The launcher or plugin version that asked; shown, not keyed.
 * @property runId The workflow run that asked, when a run did; shown, not keyed.
 */
data class GateSubject(
    val repo: String?,
    val realRepo: String? = null,
    val actionClass: String,
    val command: String,
    val scope: String,
    val push: GatePush? = null,
    val pullRequest: GatePullRequest? = null,
    val commits: List<String> = emptyList(),
    val diffDigest: String? = null,
    val tool: String = "",
    val harnessVersion: String? = null,
    val runId: String? = null,
)

/**
 * A push, as the hook reads it before it runs.
 *
 * @property remote The URL git pushes to (`git remote get-url --push`).
 * @property refs Each ref and the commit it receives.
 * @property force True for a force-push (`--force`, `-f`, `+ref`, `--force-with-lease`): a yes on a plain push never
 * covers it.
 */
data class GatePush(
    val remote: String,
    val refs: List<PushRef>,
    val force: Boolean,
)

/**
 * A ref a push updates.
 *
 * @property ref The destination ref (`refs/heads/main`).
 * @property sha The commit it receives (40 or 64 hex characters).
 */
data class PushRef(val ref: String, val sha: String)

/**
 * The pull request a merge targets, read from GitHub by the server (never from a local ref the agent can move).
 *
 * @property number The pull request number.
 * @property headSha Its head commit: a new commit is a new gate.
 * @property base The branch it merges into: a yes for `develop` never covers `main`.
 */
data class GatePullRequest(
    val number: Int,
    val headSha: String,
    val base: String,
)
