// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.rules.domain.models.RepositorySnapshot
import dev.ostrom.server.rules.domain.models.RuleSource

/**
 * What every rule found in one repository shares: its names, its links, its code owners.
 *
 * @property snapshot What the analysis read of it.
 */
class RepositoryContext(val snapshot: RepositorySnapshot) {
    /** Its short name, as rules scope it. */
    val name: String = snapshot.repo.name

    /** Its default branch. */
    val branch: String = snapshot.repo.defaultBranch

    /** Its default branch as a rule's text names it ([shown]). */
    val shownBranch: String = shown(branch)

    /** The CODEOWNERS file GitHub applies, if any. */
    val codeownersPath: String? = RepositoryFacts.codeownersPaths.firstOrNull { it in snapshot.files }

    /** Its CODEOWNERS entries, in file order. */
    val owners: List<OwnerEntry> = codeownersPath?.let { CodeOwners.parse(snapshot.files.getValue(it)) }.orEmpty()

    /** The owners of every file, from the last catch-all entry (`*`), as written. */
    val defaultOwners: List<String> = owners.lastOrNull { it.pattern in catchAll }?.owners.orEmpty()

    /**
     * Whether GitHub enforces CODEOWNERS: the default branch requires the code owners' review. Otherwise the file is
     * anyone's who can push to that branch, and it names nobody's yes.
     */
    val ownersEnforced: Boolean = snapshot.protection?.codeOwnerReviews == true

    /**
     * The logins a gate may take from CODEOWNERS: none unless GitHub enforces it ([ownersEnforced]). A contributor who
     * names himself the only owner of an unprotected file would otherwise approve his own agent's actions.
     *
     * @param owners The owners, as written.
     * @return The logins, or none.
     */
    fun approvers(owners: List<String>): List<String> = if (ownersEnforced) CodeOwners.logins(owners) else emptyList()

    /**
     * Where a rule read a file.
     *
     * @param path The file.
     * @param kind `file`, `ci`…
     * @return The source, linked to the file on its branch.
     */
    fun file(path: String, kind: String = FILE): RuleSource =
        RuleSource(kind, path, "${snapshot.repo.webUrl}/blob/$branch/$path")

    /**
     * Where a rule read the branch protection.
     *
     * @return The source, linked to the branch settings.
     */
    fun settings(): RuleSource = RuleSource(SETTINGS, "branches/$branch", "${snapshot.repo.webUrl}/settings/branches")

    /** The words and patterns the rules share. */
    companion object {
        /** A source that is a file of the repository. */
        const val FILE: String = "file"

        /** A source that is a GitHub setting. */
        const val SETTINGS: String = "settings"

        /** The patterns that cover every file. */
        val catchAll: Set<String> = setOf("*", "/*", "**", "/**")

        /** The agent tools that change a file. */
        val editTools: List<String> = listOf("Write", "Edit", "MultiEdit")

        /** The longest name a rule's text shows; a longer one is cut. */
        const val MAX_SHOWN: Int = 120

        // What a name read from a repository may not bring into a rule's text: a line break, a control or format
        // character (a bidirectional override), a separator.
        private val hidden = setOf(
            CharCategory.CONTROL,
            CharCategory.FORMAT,
            CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR,
        )

        /**
         * A name read from a repository (a path, a CODEOWNERS pattern or owner, a branch, a check), cleaned for a
         * rule's text or scope. Everything a repository holds is untrusted, and a proposed rule reaches the agents'
         * harness before anyone decides it: the name stays on one line (each hidden character becomes U+FFFD) and
         * never closes the backticks around it (a backtick becomes a quote). Not cut: a scope too long is refused.
         *
         * @param name The name.
         * @return The cleaned name.
         */
        fun clean(name: String): String = buildString {
            for (char in name) {
                append(
                    when {
                        char == '`' -> '\''
                        char.category in hidden -> '\uFFFD'
                        else -> char
                    },
                )
            }
        }

        /**
         * A name read from a repository as a rule's text shows it: [clean], and cut past [MAX_SHOWN] characters.
         *
         * @param name The name.
         * @return The text.
         */
        fun shown(name: String): String {
            val clean = clean(name)
            return if (clean.length > MAX_SHOWN) clean.take(MAX_SHOWN - 1) + "\u2026" else clean
        }

        /**
         * Names in backticks, joined: `` `a`, `b` ``, each [shown].
         *
         * @param names The names.
         * @return The text.
         */
        fun quoted(names: List<String>): String = names.joinToString(", ") { "`${shown(it)}`" }

        /**
         * A count and its noun: `1 approval`, `2 approvals`.
         *
         * @param count The count.
         * @param noun The noun, singular.
         * @return The text.
         */
        fun counted(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
    }
}
