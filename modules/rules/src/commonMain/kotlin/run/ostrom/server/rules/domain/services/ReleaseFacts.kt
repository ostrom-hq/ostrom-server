// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.services

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A way the repository releases or deploys: a script, a Makefile target, a `package.json` script, a fastlane lane.
 *
 * @property path The file it was read from.
 * @property line Its line, or null for a script found by its name.
 * @property command The command that runs it, built by the analysis from a name its pattern allows.
 */
data class ReleaseTarget(val path: String, val line: Int?, val command: String)

/**
 * Reads how a repository releases, from names only: `scripts/release*` files, the `release`, `deploy` and `publish`
 * targets of the root `Makefile` and scripts of the root `package.json`, and the release lanes of `fastlane/Fastfile`.
 * A name is kept only when it is made of letters, digits and `-_.:/`, so the command built from it is the one the
 * repository runs and carries nothing else; the recipe, the script's body and the lane's body are never read.
 */
object ReleaseFacts {
    /** The root Makefile. */
    const val MAKEFILE: String = "Makefile"

    /** The fastlane file. */
    const val FASTFILE: String = "fastlane/Fastfile"

    /** The targets one file gives at most. */
    const val MAX_TARGETS: Int = 10

    private const val NAMES = "(release|deploy|publish)"
    private val makeTarget = Regex("^($NAMES([-_][A-Za-z0-9_.-]+)?)\\s*:(?!=)")
    private val packageScript = Regex("^$NAMES([:_-][A-Za-z0-9:_.-]+)?$")
    private val script = Regex("^scripts/release[A-Za-z0-9_.-]*$")
    private val lane = Regex("^\\s*lane\\s+:([A-Za-z0-9_]+)")
    private val platform = Regex("^\\s*platform\\s+:([A-Za-z0-9_]+)")
    private val releaseLane = Regex("release|deploy|publish|beta|store|upload|production")

    /**
     * The release scripts of a repository, found by name.
     *
     * @param paths The repository's files.
     * @return One target per script, in path order.
     */
    fun scripts(paths: List<String>): List<ReleaseTarget> =
        paths.filter { script.matches(it) }.sorted().take(MAX_TARGETS).map { ReleaseTarget(it, null, "./$it") }

    /**
     * The release targets of the root Makefile.
     *
     * @param text Its text.
     * @return One target per rule, in file order.
     */
    fun makefile(text: String): List<ReleaseTarget> = text.lines().withIndex()
        .mapNotNull { (index, line) ->
            makeTarget.find(line)?.let { ReleaseTarget(MAKEFILE, index + 1, "make ${it.groupValues[1]}") }
        }
        .distinctBy { it.command }
        .take(MAX_TARGETS)

    /**
     * The release scripts of the root `package.json`.
     *
     * @param text Its text.
     * @param run How the repository's package manager runs a script ([BuildFacts.runScript]).
     * @return One target per script, in file order.
     */
    fun packageJson(text: String, run: (String) -> String): List<ReleaseTarget> {
        val scripts = (parse(text) as? JsonObject)?.get("scripts") as? JsonObject ?: return emptyList()
        val lines = text.lines()
        return scripts.keys.filter { packageScript.matches(it) }.take(MAX_TARGETS).map { name ->
            // A name written with escapes is not found as written: the target keeps no line.
            val line = (lines.indexOfFirst { "\"$name\"" in it } + 1).takeIf { it > 0 }
            ReleaseTarget(RepositoryFacts.PACKAGE_JSON, line, run(name))
        }
    }

    // A manifest that is not JSON names no script.
    private fun parse(text: String) = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    /**
     * The release lanes of `fastlane/Fastfile`, each under the platform block it follows.
     *
     * @param text Its text.
     * @return One target per lane, in file order.
     */
    fun fastfile(text: String): List<ReleaseTarget> {
        var current: String? = null
        val found = mutableListOf<ReleaseTarget>()
        text.lines().forEachIndexed { index, line ->
            platform.find(line)?.let { current = it.groupValues[1] }
            val name = lane.find(line)?.let { it.groupValues[1] }
            if (name != null && releaseLane.containsMatchIn(name.lowercase())) {
                val command = listOfNotNull("fastlane", current, name).joinToString(" ")
                found += ReleaseTarget(FASTFILE, index + 1, command)
            }
        }
        return found.take(MAX_TARGETS)
    }
}
