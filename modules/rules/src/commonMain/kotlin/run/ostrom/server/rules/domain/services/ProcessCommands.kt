// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.server.kernel.text.SecretMasks

/**
 * The commands a process may suggest. A repository is untrusted and its CI holds secrets: a command line is kept only
 * when nothing in it can carry one. A line with `=` (an assignment, a `--token=` flag: what follows is never read),
 * a `${{ }}` expression or a `secrets.` name is left out whole, and so is one the server's [SecretMasks] would
 * change, a `-u user:password` credential, one that is not a single clean line, and one longer than
 * [MAX_COMMAND].
 */
object ProcessCommands {
    /** The longest command a node suggests; a longer one is left out, never cut into another command. */
    const val MAX_COMMAND: Int = 300

    private val unsafe = listOf("=", "\${{", "secrets.", "$(")
    private val credential = Regex("(^|\\s)(-u|--user)\\s+\\S+:\\S+")

    /**
     * A command line as a process may suggest it.
     *
     * @param text The line, as read.
     * @return The command, trimmed, or null when it is left out.
     */
    fun safe(text: String): String? {
        val command = text.trim()
        val kept = command.isNotEmpty() && !command.startsWith("#") && command.length <= MAX_COMMAND &&
            unsafe.none { it in command } && !credential.containsMatchIn(command) &&
            RepositoryContext.clean(command) == command &&
            SecretMasks.masked(command) == command
        return command.takeIf { kept }
    }

    /**
     * The commands of a shell block, a line continued by `\` joined to the next, each kept only when [safe].
     *
     * @param lines The block's lines, with their numbers.
     * @return The commands kept, each with the number of its first line.
     */
    fun commands(lines: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val joined = mutableListOf<Pair<Int, String>>()
        var start = 0
        val buffer = StringBuilder()
        for ((number, line) in lines) {
            if (buffer.isEmpty()) start = number
            val text = line.trim()
            if (text.endsWith("\\")) {
                buffer.append(text.dropLast(1).trim()).append(' ')
            } else {
                joined += start to buffer.append(text).toString()
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) joined += start to buffer.toString()
        return joined.mapNotNull { (number, text) -> safe(text)?.let { number to it } }
    }
}
