// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

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
 * @param terminals Starts the scripts.
 * @param homes Makes and releases the homes.
 */
class CheckTerminals(private val terminals: TerminalStarter, private val homes: CheckHomes) : TerminalStarter {
    override suspend fun start(script: String, environment: Map<String, String>, directory: String): Terminal {
        val home = homes.create()
        val terminal = try {
            terminals.start(script, environment + mapOf("HOME" to home, "TMPDIR" to home), directory)
        } catch (error: CancellationException) {
            homes.release(home)
            throw error
        } catch (error: Exception) {
            homes.release(home)
            throw error
        }
        return Released(terminal) { homes.release(home) }
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
