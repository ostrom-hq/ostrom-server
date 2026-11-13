// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The agent's `gh` configuration directory (`GH_CONFIG_DIR`, [RunnerConfig.gitHubConfigDirectory]), which only the
 * runner writes, in the socket directory only the runner's and the agent's containers mount. It holds `hosts.yml`
 * with the execution owner's token for `github.com`, or, without a usable link, a notice ([NOTICE_FILE]) that tells
 * the agent why `gh` is signed out, and never a token. Every `gh` the agent runs reads the current token itself, and
 * `gh auth git-credential` answers with the same token as the runner's git helper.
 *
 * @param directory The directory.
 * @param files The file system.
 */
class GitHubCliConfig(directory: String, private val files: FileSystem = SystemFileSystem) {
    private val directory = Path(directory)

    /** The file `gh` reads. */
    val hosts = Path(directory, HOSTS_FILE)

    /** The notice left without a link. */
    val notice = Path(directory, NOTICE_FILE)

    /**
     * Replaces `hosts.yml` atomically with [token], or removes it and leaves the notice when [token] is null.
     *
     * @param token The token, null without a usable link.
     *
     * @return False when the token was not safe to write: the file is then removed, so `gh` is signed out rather than
     *   stale.
     */
    fun write(token: GitHubToken?): Boolean {
        files.createDirectories(directory)
        val content = token?.let(::hostsYaml)
        if (content == null) {
            files.delete(hosts, mustExist = false)
            if (token == null) write(notice, NOTICE)
            return token == null
        }
        val temporary = Path(directory, "$HOSTS_FILE.tmp")
        write(temporary, content)
        files.atomicMove(temporary, hosts)
        files.delete(notice, mustExist = false)
        return true
    }

    /** Removes what the runner wrote; called on its way out. */
    fun clear() {
        files.delete(hosts, mustExist = false)
        files.delete(notice, mustExist = false)
    }

    private fun write(path: Path, content: String) = files.sink(path).buffered().use { it.writeString(content) }

    /** Constants and writers of the enclosing type. */
    companion object {
        /** The file `gh` reads its accounts from. */
        const val HOSTS_FILE = "hosts.yml"

        /** Read by no tool: `gh` only reads `hosts.yml` and `config.yml` there. */
        const val NOTICE_FILE = "OSTROM-GITHUB-NOT-LINKED.txt"

        /** The notice's text. */
        val NOTICE = """
            gh is not signed in in this Ostrom cloud session.
            The session owner's GitHub account is not linked, must be linked again, or could not be fetched right now.
            The owner links GitHub with `ostrom github link` or on the console's GitHub page; this session picks it up
            within 5 minutes, without a restart. Until then gh is signed out and git reaches public repositories only.

        """.trimIndent()

        private val TOKEN = Regex("[A-Za-z0-9_]{1,1024}")

        /** GitHub logins, Enterprise Managed Users' `_shortcode` suffix included; always written quoted. */
        private val LOGIN = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")

        /**
         * `gh`'s multi-account `hosts.yml` for github.com over HTTPS. A token or login with any other character is
         * refused rather than escaped: GitHub's never hold one. Without a login, the single-account form.
         *
         * @param token The token.
         *
         * @return The file, or null when the token is not GitHub's alphabet.
         */
        fun hostsYaml(token: GitHubToken): String? {
            if (!TOKEN.matches(token.token)) return null
            val login = token.login?.takeIf { LOGIN.matches(it) }?.let { "\"$it\"" }
            return buildString {
                appendLine("github.com:")
                if (login != null) {
                    appendLine("    users:")
                    appendLine("        $login:")
                    appendLine("            oauth_token: ${token.token}")
                }
                appendLine("    git_protocol: https")
                appendLine("    oauth_token: ${token.token}")
                if (login != null) appendLine("    user: $login")
            }
        }
    }
}

/**
 * Keeps [config] in step with [source] for the whole execution: written once before the agent starts ([refresh]),
 * rewritten when the source renews the token, removed without a usable link (the server is asked again every [idle],
 * so a link made during the session is picked up), and removed when the runner stops. A failed call or write is
 * retried after [retry], then every [idle]; the last token written stays until it expires, then the file is removed.
 *
 * @param source The token.
 * @param config The directory.
 * @param clock The time.
 * @param log Writes a line to the container's log (once per streak, never a token).
 * @param sleep Waits.
 * @param idle The wait without a link or after repeated failures.
 * @param retry The wait after a first failure, and the shortest one.
 */
@Suppress("LongParameterList") // The source, the file, the time and the waits, named at the call sites.
class GitHubCliRenewal(
    private val source: GitHubCredentialSource,
    private val config: GitHubCliConfig,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val idle: Duration = 5.minutes,
    private val retry: Duration = GitHubCredentialSource.RETRY,
) {
    private var failures = 0
    private var refused = false
    private var unlinked = false
    private var writtenExpiresAt: Instant? = null

    /** @return How long to wait before the next pass, after one: ask the source, write or remove the file. */
    suspend fun refresh(): Duration = try {
        val token = source.token()
        val accepted = write(token)
        if (!accepted && !refused) log("the GitHub token was not written to the gh configuration: unexpected format")
        refused = !accepted
        failures = 0
        if (token == null && !unlinked) log("the session owner has no usable GitHub link: gh and git are signed out")
        unlinked = token == null
        if (token == null || !accepted) idle else maxOf(source.renewsAt() - clock.now(), retry)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        if (failures++ == 0) log("the gh configuration could not be renewed")
        // An expired token is worse than none: gh would claim a login and fail every call.
        if (writtenExpiresAt.let { it != null && clock.now() >= it }) clearQuietly()
        if (failures == 1) retry else idle
    }

    /**
     * Renews until cancelled, then removes the file.
     *
     * @param first The wait before the first pass, after a [refresh] already done.
     */
    suspend fun run(first: Duration) {
        try {
            var wait = first
            while (true) {
                sleep(wait)
                wait = refresh()
            }
        } finally {
            withContext(NonCancellable) { clearQuietly() }
        }
    }

    private fun write(token: GitHubToken?): Boolean = config.write(token).also { accepted ->
        writtenExpiresAt = if (token != null && accepted) token.expiresAt else null
    }

    private fun clearQuietly() {
        try {
            config.clear()
            writtenExpiresAt = null
            // not coroutine-reachable: a file removal, which never suspends.
        } catch (_: Exception) {
            log("the gh configuration could not be removed")
        }
    }
}
