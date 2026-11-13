// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.services

/**
 * A CODEOWNERS file as GitHub reads it: entries in file order, the last one that matches a path wins, patterns follow
 * the gitignore rules. Also writes the path patterns a gate matches, in a syntax every engine reads alike.
 */
object CodeOwners {
    private const val MAX_ENTRIES = 500

    /** The longest pattern read; a longer entry is left out. */
    const val MAX_PATTERN: Int = 256

    /** The longest path matched; a longer one has no owner (nobody's yes, the team picks). */
    const val MAX_PATH: Int = 1_024

    private val spaces = Regex("\\s+")
    private val stars = Regex("\\*+")

    /**
     * Reads a CODEOWNERS file: comments and blank lines are ignored.
     *
     * @param text The file.
     * @return Its entries in file order, at most 500, each pattern at most [MAX_PATTERN] characters.
     */
    fun parse(text: String): List<OwnerEntry> = text.lineSequence()
        .map { it.substringBefore(" #").substringBefore("\t#").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> line.split(spaces).let { OwnerEntry(it.first(), it.drop(1)) } }
        .filter { it.pattern.length <= MAX_PATTERN }
        .take(MAX_ENTRIES)
        .toList()

    /**
     * The owners GitHub requests for a path: those of the last entry that matches it. Matched without a regular
     * expression ([GlobMatcher]): a pattern and a path both come from the repository, and `*a*a*a…` against a long
     * name would keep a backtracking engine busy for hours, out of reach of any cancellation.
     *
     * @param entries The entries.
     * @param path The path, from the root; past [MAX_PATH] characters it has no owner.
     * @return The owners, or none.
     */
    fun ownersOf(entries: List<OwnerEntry>, path: String): List<String> =
        if (path.length > MAX_PATH) {
            emptyList()
        } else {
            entries.lastOrNull { GlobMatcher.of(it.pattern).matches(path) }?.owners.orEmpty()
        }

    /**
     * The GitHub logins among owners: `@alice` gives `alice`; a team (`@acme/platform`) or an e-mail gives nothing.
     *
     * @param owners The owners, as written.
     * @return The logins, each once.
     */
    fun logins(owners: List<String>): List<String> =
        owners.filter { it.startsWith("@") && '/' !in it }.map { it.drop(1) }.distinct()

    /**
     * Whether a pattern may become a gate's expression: one run of stars at most. Each hook matches the expression
     * with a backtracking engine, as the replay does on the server; two runs (`*a*`, `**` then `*`) already cost the
     * square of the path's length per position, more runs a power of it.
     *
     * @param pattern The pattern.
     * @return True when it has at most one run of stars.
     */
    fun gateable(pattern: String): Boolean = stars.findAll(pattern).count() <= 1

    /**
     * The pattern a gate matches for files under a CODEOWNERS pattern, on any path that ends with them: an agent's
     * tool names the file by its absolute path.
     *
     * @param pattern The pattern.
     * @return The expression.
     */
    fun gatePattern(pattern: String): String =
        "(^|/)" + translate(pattern.removePrefix("/").trimEnd('/')) + tail(pattern)

    /**
     * The pattern a gate matches for a directory (ending with `/`) or a file found by the analysis.
     *
     * @param prefix The directory or file, from the root.
     * @return The expression.
     */
    fun prefixPattern(prefix: String): String =
        "(^|/)" + literal(prefix) + if (prefix.endsWith('/')) "" else "$"

    private fun tail(pattern: String): String = if (pattern.endsWith("/")) "/.*$" else "(?:/.*)?$"

    private fun translate(glob: String): String = buildString {
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> append("(?:.*/)?").also { i += 2 }
                glob.startsWith("**", i) -> append(".*").also { i++ }
                glob[i] == '*' -> append("[^/]*")
                glob[i] == '?' -> append("[^/]")
                else -> append(literal(glob[i].toString()))
            }
            i++
        }
    }

    /**
     * A text as a regular expression that matches it literally. Each special character is escaped by hand: `\Q…\E`
     * is not read by JavaScript, where the hooks match.
     *
     * @param text The text.
     * @return The expression.
     */
    fun literal(text: String): String = text.map { if (it in "\\^$.|?*+()[]{}") "\\$it" else it.toString() }
        .joinToString("")
}
