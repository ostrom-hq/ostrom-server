// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * A check the agent's hook asks the runner for: one JSON line on [RunnerConfig.checksSocket].
 *
 * @property sessionId The agent's session the check guards.
 * @property digest The package's digest.
 * @property nodeId The node of the check.
 * @property scriptId The script.
 * @property script The script's text, as the hook read it from the package: the runner hashes what it runs.
 * @property directory Where it runs: the repository, or a checkout of a commit, inside the workspace.
 * @property timeoutMs Past this it is stopped.
 * @property head The commit it tests.
 */
@Serializable
data class CheckRequest(
    val sessionId: String,
    val digest: String,
    val nodeId: String,
    val scriptId: String,
    val script: String,
    val directory: String,
    val timeoutMs: Long = RunnerChecks.DEFAULT_TIMEOUT_MS,
    val head: String? = null,
)

/**
 * The runner's answer to the hook: one JSON line.
 *
 * @property started False when the check was not run ([error] says why): the hook refuses the action.
 * @property exit The script's exit code, null when it was stopped at its time limit or not run.
 * @property outcome `passed`, `failed` or `timeout`; `failed` when it was not run.
 * @property scriptSha The SHA-256 of the script the runner ran.
 * @property outputTail The end of its output.
 * @property reported Whether the server recorded the run, as attested by the isolated runner.
 * @property error Why it was not run.
 */
@Serializable
data class CheckReply(
    val started: Boolean,
    val exit: Int? = null,
    val outcome: String = RunnerChecks.FAILED,
    val scriptSha: String = "",
    val outputTail: String = "",
    val reported: Boolean = false,
    val error: String? = null,
)

/**
 * The checks of the agent's hooks, run by the runner on the check host (`ostrom-runner check-host`): a container of
 * its own, without the runner token, that mounts the workspace and whose socket only the runner reaches. The runner
 * hashes the script it sends, reads the exit code the check host reports, and tells the server, which records the run
 * as attested by the isolated runner.
 *
 * What this proves, and what it does not: Ostrom launched the script and read its exit code, not that the agent left
 * the workspace untouched. The check runs on the agent's workspace.
 *
 * @param host Starts the script on the check host.
 * @param server The server the run is reported to.
 * @param workspace The workspace: a check runs inside it only.
 * @param environment The runner's environment, of which the check keeps `PATH` and `LANG`.
 * @param clock Times the run.
 */
class RunnerChecks(
    private val host: TerminalStarter,
    private val server: ServerApi,
    private val workspace: String,
    private val environment: Map<String, String>,
    private val clock: Clock,
) {
    private val running = Semaphore(PARALLEL)

    /**
     * Runs a check to its end or its time limit, and reports it.
     *
     * @param request The hook's request.
     *
     * @return The answer for the hook.
     */
    suspend fun run(request: CheckRequest): CheckReply {
        val refusal = refusal(request)
        return if (refusal == null) runAccepted(request) else CheckReply(started = false, error = refusal)
    }

    private suspend fun runAccepted(request: CheckRequest): CheckReply {
        val sha = request.script.encodeUtf8().sha256().hex()
        val started = clock.now()
        val run = running.withPermit { execute(request) }
            ?: return CheckReply(started = false, scriptSha = sha, error = "the check host could not be reached.")
        val outcome = when {
            run.timedOut -> TIMEOUT
            run.exit == 0 -> PASSED
            else -> FAILED
        }
        val check = RunnerCheck(
            sessionId = request.sessionId,
            digest = request.digest,
            nodeId = request.nodeId,
            scriptId = request.scriptId,
            scriptSha = sha,
            exit = run.exit,
            outcome = outcome,
            durationMs = (clock.now() - started).inWholeMilliseconds,
            head = request.head,
            sandbox = SANDBOX,
            outputTail = run.tail,
        )
        return CheckReply(true, run.exit, outcome, sha, run.tail, report(check))
    }

    /** Why a request is not run: a script too large, a folder outside the workspace, a time limit out of range. */
    private fun refusal(request: CheckRequest): String? {
        val segments = request.directory.split('/')
        return when {
            request.script.length > MAX_SCRIPT -> "the script is over $MAX_SCRIPT characters."
            !inside(request.directory) || segments.any { it == ".." || it == "." } ->
                "the check runs inside $workspace only."
            request.timeoutMs !in 1..MAX_TIMEOUT_MS -> "the time limit must be 1 to $MAX_TIMEOUT_MS ms."
            else -> null
        }
    }

    private fun inside(directory: String): Boolean = directory == workspace || directory.startsWith("$workspace/")

    /** What became of the script on the check host. */
    private class Run(val exit: Int?, val timedOut: Boolean, val tail: String)

    /** Runs the script on the check host; null when the host cannot be reached. */
    private suspend fun execute(request: CheckRequest): Run? {
        val terminal = try {
            host.start(request.script, variables(request), request.directory)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        val output = Tail()
        return try {
            val ended = withTimeoutOrNull(request.timeoutMs.milliseconds) {
                while (true) output += terminal.read() ?: break
            }
            if (ended == null) withContext(NonCancellable) { terminal.terminate() }
            Run(if (ended == null) null else terminal.exitCode, ended == null, output.text())
        } finally {
            terminal.close()
        }
    }

    /** The check's environment: no token, no variable of the agent, a home of its own outside the workspace. */
    private fun variables(request: CheckRequest): Map<String, String> = mapOf(
        "PATH" to (environment["PATH"] ?: DEFAULT_PATH),
        "LANG" to (environment["LANG"] ?: "C.UTF-8"),
        "HOME" to CHECK_HOME,
        "TMPDIR" to CHECK_HOME,
        "CI" to "1",
        "PYTHONDONTWRITEBYTECODE" to "1",
        "OSTROM_CHECK" to request.scriptId,
    )

    /** Reports the run; a server that cannot be reached leaves it unrecorded, never the hook waiting. */
    private suspend fun report(check: RunnerCheck): Boolean = try {
        server.reportCheck(check)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /** The end of an output, kept as bytes while it is read, decoded once. */
    private class Tail {
        private var bytes = ByteArray(0)

        /** Adds a chunk, keeping the last [KEPT_BYTES]. */
        operator fun plusAssign(chunk: ByteArray) {
            val joined = bytes + chunk
            bytes = if (joined.size > KEPT_BYTES) joined.copyOfRange(joined.size - KEPT_BYTES, joined.size) else joined
        }

        /** The last [TAIL_CHARACTERS] characters. */
        fun text(): String = bytes.decodeToString().takeLast(TAIL_CHARACTERS)
    }

    /** Constants of the checks. */
    companion object {
        /** A time limit the hook did not give. */
        const val DEFAULT_TIMEOUT_MS = 600_000L

        /** The longest a check may run. */
        const val MAX_TIMEOUT_MS = 3_600_000L

        /** The largest script, in characters. */
        const val MAX_SCRIPT = 65_536

        /** How the run was confined, as the server records it. */
        const val SANDBOX = "isolated runner: check host container, no runner token"

        /** The script exited 0. */
        const val PASSED = "passed"

        /** The script exited with another code, or was not run. */
        const val FAILED = "failed"

        /** The script was stopped at its time limit. */
        const val TIMEOUT = "timeout"

        private const val PARALLEL = 2
        private const val TAIL_CHARACTERS = 4_000
        private const val KEPT_BYTES = 64 * 1024
        private const val CHECK_HOME = "/tmp"
        private const val DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin"
    }
}
