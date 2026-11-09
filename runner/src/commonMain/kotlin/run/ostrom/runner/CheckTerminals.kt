// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.runner

import kotlinx.coroutines.CancellationException

/** The homes of the checks, on the check host. */
interface CheckHomes {
    /**
     * Makes a new, empty home for a check.
     *
     * @return Its path.
     */
    fun create(): String

    /**
     * After a check: kills every process it left behind, then removes its home.
     *
     * @param home The check's home.
     */
    fun release(home: String)
}

/**
 * The check host's terminals (audit runner 2, R4): each check gets a home of its own (`HOME` and `TMPDIR`), made for
 * it, and once it ends every process it left behind is killed and its home removed, before the next check starts. A
 * check therefore neither inherits a file nor a process of the one before.
 *
 * The check's folder is resolved to its real path first (audit runner 2, R7): a link the agent left in the workspace
 * (`/workspace/x` to `/`) never takes a check outside it. Such a check exits 126 without running.
 *
 * @param terminals Starts the scripts.
 * @param homes Makes and releases the homes.
 * @param workspace The workspace: a check runs inside it only.
 * @param real The real path of a folder, without links, or null when it does not exist.
 */
class CheckTerminals(
    private val terminals: TerminalStarter,
    private val homes: CheckHomes,
    private val workspace: String,
    private val real: (String) -> String?,
) : TerminalStarter {
    override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
        val inside = real(directory)?.takeIf { it == workspace || it.startsWith("$workspace/") }
        val (run, folder) = if (inside == null) OUTSIDE to workspace else script to inside
        val home = homes.create()
        val terminal = try {
            terminals.start(run, environment + mapOf("HOME" to home, "TMPDIR" to home), folder)
        } catch (error: CancellationException) {
            homes.release(home)
            throw error
        } catch (error: Exception) {
            homes.release(home)
            throw error
        }
        return Released(terminal) { homes.release(home) }
    }

    private companion object {
        /** What runs instead of a check whose folder is outside the workspace. */
        const val OUTSIDE = "echo 'ostrom: the check runs inside the workspace only.' >&2; exit 126"
    }

    /** A check's terminal, whose close releases the check's home. */
    private class Released(private val terminal: Terminal, private val release: () -> Unit) : Terminal by terminal {
        override fun close() {
            try {
                terminal.close()
            } finally {
                release()
            }
        }
    }
}
