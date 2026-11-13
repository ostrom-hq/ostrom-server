// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.services

/**
 * The share of a language among a repository's code files.
 *
 * @property name The language.
 * @property files Its files.
 */
data class LanguageShare(val name: String, val files: Int)

/**
 * A build tool found at a repository's root.
 *
 * @property name The tool (`Gradle`, `pnpm`…).
 * @property command The command that checks a change, when the repository says which; null otherwise.
 * @property evidence The files that show it, first the one that decides.
 * @property pinned True when a wrapper or a lockfile pins it, so the team surely uses it.
 */
data class BuildTool(val name: String, val command: String?, val evidence: List<String>, val pinned: Boolean)

/**
 * A kind of file whose change deserves a human yes.
 *
 * @property group The rules group it fills.
 */
enum class AreaKind(val group: String) {
    SECRETS("Secrets"),
    CI("CI"),
    INFRA("Infrastructure"),
    MIGRATIONS("Migrations"),
}

/**
 * The sensitive files of one kind in a repository.
 *
 * @property kind What they are.
 * @property prefixes The directories (ending with `/`) or files that hold them, the shortest that cover them.
 * @property evidence A few of the files, as found.
 * @property omitted How many more prefixes were found than listed.
 */
data class SensitiveArea(
    val kind: AreaKind,
    val prefixes: List<String>,
    val evidence: List<String>,
    val omitted: Int = 0,
)

/**
 * One line of a CODEOWNERS file.
 *
 * @property pattern Its pattern, as written.
 * @property owners Its owners, as written (`@alice`, `@acme/platform`, an e-mail).
 */
data class OwnerEntry(val pattern: String, val owners: List<String>)

/**
 * What the analysis reads of a repository beyond its file names. Everything it concludes is counted from these, never
 * guessed: the same files always give the same facts ([BuildFacts], [SensitiveFiles], [CodeOwners]).
 */
object RepositoryFacts {
    /** Where GitHub reads CODEOWNERS, in its order of precedence. */
    val codeownersPaths: List<String> = listOf(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS")

    /** The root manifest whose scripts name the check command. */
    const val PACKAGE_JSON: String = "package.json"

    private val vendored = setOf(
        "node_modules", ".git", "dist", "build", "out", ".gradle", ".next", "vendor", "Pods", "coverage", ".venv",
        "venv", "__pycache__", "target", "generated", "third_party", ".yarn",
    )

    /**
     * The files the analysis reads: the CODEOWNERS GitHub applies and the root `package.json`, nothing else.
     *
     * @param paths The repository's files.
     * @return Their paths, at most two.
     */
    fun wantedFiles(paths: List<String>): List<String> {
        val all = paths.toSet()
        return listOfNotNull(codeownersPaths.firstOrNull { it in all }, PACKAGE_JSON.takeIf { it in all })
    }

    /**
     * Tells whether a file is generated or vendored (`node_modules/`, `build/`…): it says nothing of the team.
     *
     * @param path The file.
     * @return True when one of its directories is such.
     */
    fun vendored(path: String): Boolean = path.split('/').dropLast(1).any { it in vendored }
}
