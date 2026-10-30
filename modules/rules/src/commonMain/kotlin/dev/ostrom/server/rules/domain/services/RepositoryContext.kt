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

    /** The CODEOWNERS file GitHub applies, if any. */
    val codeownersPath: String? = RepositoryFacts.codeownersPaths.firstOrNull { it in snapshot.files }

    /** Its CODEOWNERS entries, in file order. */
    val owners: List<OwnerEntry> = codeownersPath?.let { CodeOwners.parse(snapshot.files.getValue(it)) }.orEmpty()

    /** The owners of every file, from the last catch-all entry (`*`), as written. */
    val defaultOwners: List<String> = owners.lastOrNull { it.pattern in catchAll }?.owners.orEmpty()

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

        /**
         * Names in backticks, joined: `` `a`, `b` ``.
         *
         * @param names The names.
         * @return The text.
         */
        fun quoted(names: List<String>): String = names.joinToString(", ") { "`$it`" }

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
