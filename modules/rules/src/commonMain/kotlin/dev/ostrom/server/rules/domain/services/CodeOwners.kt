// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

/**
 * A CODEOWNERS file as GitHub reads it: entries in file order, the last one that matches a path wins, patterns follow
 * the gitignore rules. Also writes the path patterns a gate matches, in a syntax every engine reads alike.
 */
object CodeOwners {
    private const val MAX_ENTRIES = 500
    private val spaces = Regex("\\s+")

    /**
     * Reads a CODEOWNERS file: comments and blank lines are ignored.
     *
     * @param text The file.
     * @return Its entries in file order, at most 50.
     */
    fun parse(text: String): List<OwnerEntry> = text.lineSequence()
        .map { it.substringBefore(" #").substringBefore("\t#").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> line.split(spaces).let { OwnerEntry(it.first(), it.drop(1)) } }
        .take(MAX_ENTRIES)
        .toList()

    /**
     * The owners GitHub requests for a path: those of the last entry that matches it.
     *
     * @param entries The entries.
     * @param path The path, from the root.
     * @return The owners, or none.
     */
    fun ownersOf(entries: List<OwnerEntry>, path: String): List<String> =
        entries.lastOrNull { Regex(glob(it.pattern)).matches(path) }?.owners.orEmpty()

    /**
     * The GitHub logins among owners: `@alice` gives `alice`; a team (`@acme/platform`) or an e-mail gives nothing.
     *
     * @param owners The owners, as written.
     * @return The logins, each once.
     */
    fun logins(owners: List<String>): List<String> =
        owners.filter { it.startsWith("@") && '/' !in it }.map { it.drop(1) }.distinct()

    /**
     * A pattern as a regular expression on a path from the repository's root.
     *
     * @param pattern The pattern (`/docs/`, `*.sql`, a double star for any depth).
     * @return The expression, anchored at both ends.
     */
    fun glob(pattern: String): String {
        val anchored = pattern.startsWith("/") || pattern.trimEnd('/').contains('/')
        val head = if (anchored) "^" else "^(?:.*/)?"
        return head + translate(pattern.removePrefix("/").trimEnd('/')) + tail(pattern)
    }

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
