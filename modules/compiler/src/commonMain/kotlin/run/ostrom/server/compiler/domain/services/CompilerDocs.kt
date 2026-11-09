// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.Workflow
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import run.ostrom.server.compiler.domain.models.WorkflowDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Reads the stored documents (workflow, rule, decision) the compiler works on, keeping their raw JSON for digests. */
object CompilerDocs {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A workflow document.
     *
     * @param raw Its JSON, as saved.
     * @return The document.
     */
    fun workflow(raw: JsonObject): WorkflowDoc =
        WorkflowDoc(raw, json.decodeFromJsonElement(Workflow.serializer(), raw))

    /**
     * A rule document.
     *
     * @param raw Its JSON, as saved.
     * @return The document.
     */
    fun rule(raw: JsonObject): RuleDoc = RuleDoc(
        raw = raw,
        id = text(raw, "id").orEmpty(),
        statement = text(raw, "statement").orEmpty(),
        scope = texts(raw["scope"]),
        sourceRefs = (raw["sources"] as? JsonArray).orEmpty().mapNotNull { source ->
            (source as? JsonObject)?.let { text(it, "ref") }
        },
        status = text(raw, "status").orEmpty(),
        strength = text(raw, "strength").orEmpty(),
        confidence = (raw["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        group = text(raw, "group"),
    )

    /**
     * A decision document.
     *
     * @param raw Its JSON, as saved.
     * @return The document.
     */
    fun decision(raw: JsonObject): DecisionDoc {
        val subject = raw["subject"] as? JsonObject ?: JsonObject(emptyMap())
        return DecisionDoc(
            id = text(raw, "id").orEmpty(),
            subjectKind = text(subject, "kind").orEmpty(),
            subjectId = text(subject, "id").orEmpty(),
            by = text(raw, "by").orEmpty(),
            at = text(raw, "at").orEmpty(),
            receiptId = text(raw, "receiptId"),
        )
    }

    private fun text(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun texts(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
