// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The repositories a workflow is limited to (audit of the runner 2, R6): its optional `repos` field, a list of
 * `owner/name` where `*` stands for any part of a name. Without it, a workflow applies to every repository of its
 * organization, as before; with it, only to these, whatever its status (observed or enforced).
 */
object WorkflowRepos {
    /** The field of the workflow's JSON. */
    const val FIELD: String = "repos"

    /** The most repositories one workflow lists. */
    const val MAX: Int = 100

    private val SHAPE = Regex("[A-Za-z0-9_.*-]+/[A-Za-z0-9_.*-]+")

    /**
     * The repositories of a stored workflow.
     *
     * @param raw The workflow's JSON.
     * @return The list, or null when the workflow names none (it applies everywhere). A list that is not one of
     *   texts keeps only its texts: a stored workflow is checked when saved ([problem]).
     */
    fun of(raw: JsonObject): List<String>? = when (val field = raw[FIELD]) {
        null, JsonNull -> null
        else -> texts(field).filterNotNull()
    }

    /**
     * What is wrong with a workflow's `repos`, before it is saved.
     *
     * @param raw The workflow's JSON.
     * @return The reason, or null when the field is absent or right.
     */
    fun problem(raw: JsonObject): String? {
        val field = raw[FIELD]
        if (field == null || field == JsonNull) return null
        val items = texts(field)
        return when {
            field !is JsonArray -> "repos is a list of owner/name."
            items.isEmpty() -> "repos names at least one repository; leave it out for all of them."
            items.size > MAX -> "repos names $MAX repositories at most."
            items.any { it == null || !SHAPE.matches(it) } -> "repos is a list of owner/name, where * is any part."
            else -> null
        }
    }

    /**
     * The workflow's JSON without `repos`, for the core model (which does not know the field yet).
     *
     * @param raw The workflow's JSON.
     * @return The same without the field.
     */
    fun withoutField(raw: JsonObject): JsonObject = JsonObject(raw - FIELD)

    /**
     * Tells whether a workflow's repositories cover a repository.
     *
     * @param repos The workflow's repositories, null for all.
     * @param repo The repository, or null for the organization: a limited workflow is never in the organization's.
     * @return True when covered.
     */
    fun covers(repos: List<String>?, repo: String?): Boolean = when {
        repos == null -> true
        repo == null -> false
        else -> repos.any { matches(it, repo) }
    }

    // The items of a list, null for an item that is not a text; nothing for what is not a list.
    private fun texts(field: JsonElement): List<String?> =
        (field as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun matches(pattern: String, repo: String): Boolean =
        Regex(pattern.split('*').joinToString("[^/]*") { Regex.escape(it) }, RegexOption.IGNORE_CASE).matches(repo)
}
