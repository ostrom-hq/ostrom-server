// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubContentsApi
import run.ostrom.server.integrations.github.infrastructure.GitHubContentsJson.Branch
import run.ostrom.server.integrations.github.infrastructure.GitHubContentsJson.BranchRule
import run.ostrom.server.integrations.github.infrastructure.GitHubContentsJson.Content
import run.ostrom.server.integrations.github.infrastructure.GitHubContentsJson.Tree
import run.ostrom.server.integrations.github.infrastructure.GitHubContentsJson.protectionOf
import run.ostrom.server.integrations.github.infrastructure.GitHubContentsJson.text
import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryTree
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [GitHubContentsApi] over HTTPS, with installation tokens restricted to `contents: read` and `metadata: read`: only
 * `GET` requests, never a write. GitHub's rate limits are kept: a limited answer (`403` or `429` with
 * `Retry-After`, or `X-RateLimit-Remaining: 0`) waits for the time GitHub gives, up to [maxWait] and [RETRIES] times;
 * beyond, the read fails with a sentence saying when to start again.
 *
 * Classic branch protection is read from `GET /repos/{repo}/branches/{branch}`, which says whether the branch is
 * protected and its required checks: its review settings need `administration: read`, which the App does not ask
 * for, so the approvals and the code owners' review come from the rulesets that apply to the branch.
 *
 * @param http The client (`expectSuccess = false`).
 * @param apiUrl GitHub's API.
 * @param clock The clock, against `X-RateLimit-Reset`.
 * @param maxWait The longest wait for a rate limit before the read fails.
 * @param pause How a wait is spent; a test passes its own.
 * @param tokens An installation token restricted to the permissions given, or null when GitHub refuses them.
 */
class HttpGitHubContentsApi(
    private val http: HttpClient,
    private val apiUrl: String,
    private val clock: Clock,
    private val maxWait: Duration = 60.seconds,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
    private val tokens: suspend (installationId: Long, permissions: Map<String, String>) -> String?,
) : GitHubContentsApi {
    override suspend fun tree(installationId: Long, repo: String, branch: String): RepositoryTree {
        val token = token(installationId)
        val head = get(token, branchPath(repo, branch))
        // An empty repository has no branch yet: nothing to read, not a failure.
        if (head.status == HttpStatusCode.NotFound) return EMPTY
        val sha = read<Branch>(head, "branch").commit.commit.tree.sha
        if (!SHA.matches(sha)) throw GitHubCallException("GitHub answered an unexpected branch.")
        return listing(get(token, "${repoPath(repo)}/git/trees/$sha?recursive=1"))
    }

    override suspend fun file(installationId: Long, repo: String, branch: String, path: String): String? {
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val encoded = segments.joinToString("/") { it.encodeURLParameter() }
        val query = "ref=${branch.encodeURLParameter()}"
        val response = get(token(installationId), "${repoPath(repo)}/contents/$encoded?$query")
        return if (response.status == HttpStatusCode.NotFound) null else text(read<Content>(response, "file"))
    }

    override suspend fun protection(installationId: Long, repo: String, branch: String): BranchProtection? {
        val token = token(installationId)
        val response = get(token, branchPath(repo, branch))
        // A branch GitHub does not show to this installation: its protection is unknown, not absent.
        if (!response.status.isSuccess()) return null
        val classic = read<Branch>(response, "branch")
        val rulesets = get(token, "${repoPath(repo)}/rules/branches/${branch.encodeURLParameter()}?per_page=100")
        val rules = if (rulesets.status.isSuccess()) read<List<BranchRule>>(rulesets, "rules") else emptyList()
        return protectionOf(classic, rules)
    }

    private suspend fun token(installationId: Long): String = tokens(installationId, CONTENTS)
        ?: throw GitHubCallException(
            "The GitHub App may not read the repositories' contents: accept its permissions on GitHub, then start " +
                "the analysis again.",
        )

    // One GET, waiting out GitHub's rate limit when it says how long.
    private suspend fun get(token: String, path: String): HttpResponse {
        var attempts = 0
        while (true) {
            val response = http.exchange(HttpMethod.Get, "$apiUrl$path", path) { headers(token) }
            val wait = limited(response) ?: return response
            attempts++
            if (attempts > RETRIES || wait > maxWait) {
                throw GitHubCallException(
                    "GitHub's rate limit is reached for this installation: start the analysis again in " +
                        "${wait.inWholeMinutes + 1} minutes.",
                )
            }
            pause(wait)
        }
    }

    // How long GitHub asks to wait, or null when the answer is not a rate limit (a 403 can be a permission refusal).
    private fun limited(response: HttpResponse): Duration? {
        val status = response.status
        if (status != HttpStatusCode.Forbidden && status != HttpStatusCode.TooManyRequests) return null
        val retryAfter = response.headers["retry-after"]?.toLongOrNull()?.seconds
        val reset = response.headers["x-ratelimit-reset"]?.toLongOrNull()
            ?.takeIf { response.headers["x-ratelimit-remaining"] == "0" }
            ?.let { (Instant.fromEpochSeconds(it) - clock.now()).coerceAtLeast(Duration.ZERO) + 1.seconds }
        val unsaid = DEFAULT_WAIT.takeIf { status == HttpStatusCode.TooManyRequests }
        return retryAfter ?: reset ?: unsaid
    }

    private suspend fun listing(response: HttpResponse): RepositoryTree =
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Conflict) {
            // 409: GitHub's answer for an empty repository.
            EMPTY
        } else {
            val tree = read<Tree>(response, "tree")
            RepositoryTree(tree.tree.filter { it.type == "blob" }.map { it.path }, tree.truncated)
        }

    private fun branchPath(repo: String, branch: String) = "${repoPath(repo)}/branches/${branch.encodeURLParameter()}"

    private suspend inline fun <reified T> read(response: HttpResponse, what: String): T {
        if (!response.status.isSuccess()) {
            throw GitHubCallException("GitHub refused the $what (${response.status.value}).")
        }
        return try {
            contentsJson.decodeFromString<T>(response.bodyAsText())
        } catch (error: SerializationException) {
            throw GitHubCallException("GitHub answered an unexpected $what.", error)
        }
    }

    private companion object {
        const val RETRIES = 3
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val CONTENTS = mapOf("contents" to "read", "metadata" to "read")
private val EMPTY = RepositoryTree(emptyList(), truncated = false)
private val SHA = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
private val DEFAULT_WAIT = 60.seconds
// An explicit null (a branch without protection details) reads as the shape's default.
private val contentsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
