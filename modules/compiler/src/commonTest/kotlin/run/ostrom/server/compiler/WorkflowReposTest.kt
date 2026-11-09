// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.services.WorkflowRepos
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowReposTest {
    private fun raw(field: JsonElement?): JsonObject =
        JsonObject(mapOf("id" to JsonPrimitive("w")) + listOfNotNull(field?.let { WorkflowRepos.FIELD to it }))

    private fun list(vararg items: JsonElement) = raw(JsonArray(items.toList()))

    private fun texts(vararg items: String) = list(*items.map { JsonPrimitive(it) }.toTypedArray())

    @Test
    fun aWorkflowWithoutRepositoriesAppliesEverywhereAndAStoredListKeepsItsTexts() {
        assertNull(WorkflowRepos.of(raw(null)))
        assertNull(WorkflowRepos.of(raw(JsonNull)))
        val mixed = list(JsonPrimitive("acme/api"), JsonPrimitive(1), JsonNull, JsonArray(emptyList()))
        assertEquals(listOf("acme/api"), WorkflowRepos.of(mixed))
        val notAList = raw(JsonPrimitive("acme/api"))
        assertEquals(emptyList<String>(), WorkflowRepos.of(notAList), "not a list: applies nowhere")
        val bare: JsonObject = WorkflowRepos.withoutField(texts("acme/api"))
        assertEquals(JsonObject(mapOf("id" to JsonPrimitive("w"))), bare)
    }

    @Test
    fun onlyAListOfOwnerAndNameIsSaved() {
        assertNull(WorkflowRepos.problem(raw(null)))
        assertNull(WorkflowRepos.problem(raw(JsonNull)))
        assertNull(WorkflowRepos.problem(texts("acme/api", "acme/*", "*/*", "Acme_1/web.site-2")))
        assertEquals("repos is a list of owner/name.", WorkflowRepos.problem(raw(JsonPrimitive("acme/api"))))
        val empty = "repos names at least one repository; leave it out for all of them."
        assertEquals(empty, WorkflowRepos.problem(texts()))
        val tooMany = (0..WorkflowRepos.MAX).map { "acme/r$it" }.toTypedArray()
        assertEquals("repos names 100 repositories at most.", WorkflowRepos.problem(texts(*tooMany)))
        for (wrong in listOf(texts("acme"), texts("acme/api/x"), texts("acme/a b"), list(JsonPrimitive(1)))) {
            assertEquals("repos is a list of owner/name, where * is any part.", WorkflowRepos.problem(wrong))
        }
    }

    @Test
    fun aStarIsAnyPartOfANameAndTheCaseIsIgnored() {
        assertTrue(WorkflowRepos.covers(null, "acme/api"))
        assertTrue(WorkflowRepos.covers(null, null))
        assertFalse(WorkflowRepos.covers(listOf("acme/*"), null), "a limited workflow is not the organization's")
        assertTrue(WorkflowRepos.covers(listOf("acme/docs", "ACME/*"), "acme/api"))
        assertTrue(WorkflowRepos.covers(listOf("acme/web-*"), "acme/web-site"))
        assertFalse(WorkflowRepos.covers(listOf("acme/web-*"), "acme/api"))
        assertFalse(WorkflowRepos.covers(listOf("acme/*"), "acme/api/extra"), "a star never crosses a slash")
        assertFalse(WorkflowRepos.covers(listOf("acme/a.i"), "acme/api"), "a dot is a dot")
        assertFalse(WorkflowRepos.covers(emptyList(), "acme/api"))
    }
}
