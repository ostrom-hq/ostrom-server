// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoNote
import run.ostrom.server.todos.domain.TodoProgress
import run.ostrom.server.todos.domain.TodoProof
import run.ostrom.server.todos.domain.TodoRef
import run.ostrom.server.todos.infrastructure.TodoJson
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The JSON columns read back what they wrote; the module registers under its name. */
class TodoJsonTest {
    private val t0 = Instant.parse("2026-11-13T14:02:00.123Z")

    @Test
    fun everyColumnReadsBackWhatItWrote() {
        val doneWhen = TodoDoneWhen(
            "Shipped",
            listOf(
                TodoCheck.PrMerged("acme/x"),
                TodoCheck.PrClosed("acme/x", "superseded"),
                TodoCheck.ReleaseContains("acme/x", of = "a"),
                TodoCheck.ReleaseContains("acme/x", commit = "4e1c9a2"),
                TodoCheck.CiGreen("acme/x", of = "a", workflow = "ci"),
                TodoCheck.CiGreen("acme/x", ref = "main"),
                TodoCheck.Command("make test", cwd = "/w", expectExit = 2, timeoutSeconds = 90),
                TodoCheck.ArtifactPublished("art", 2),
                TodoCheck.ArtifactPublished(),
                TodoCheck.WaitAnswered("yes"),
                TodoCheck.WaitAnswered(),
                TodoCheck.Attested("seen"),
            ),
        )
        assertEquals(doneWhen, TodoJson.doneWhen(TodoJson.doneWhen(doneWhen)))
        assertEquals(listOf("a", "b"), TodoJson.strings(TodoJson.strings(listOf("a", "b"))))
        val notes = listOf(TodoNote(t0, "n"))
        assertEquals(notes, TodoJson.notes(TodoJson.notes(notes)))
        val proof = TodoProof(t0, null, "unknown", listOf("https://x/1"))
        assertEquals(proof, TodoJson.proof(TodoJson.proof(proof)))
        assertEquals(proof.copy(passed = true), TodoJson.proof(TodoJson.proof(proof.copy(passed = true))))
        val links = listOf(TodoLink("pr", "https://x/pull/1", 1, "sha", null, t0), TodoLink("wait", "w"))
        assertEquals(links, TodoJson.links(TodoJson.links(links)))
        val events = listOf(TodoHookEvent(t0, TodoHookEvent.BLOCKED, "stuck"))
        assertEquals(events, TodoJson.events(TodoJson.events(events)))
        val refs = listOf(TodoRef("url", url = "https://x"), TodoRef("artifact", id = "a", title = "Plan"))
        assertEquals(refs, TodoJson.refs(TodoJson.refs(refs)))
        assertEquals(TodoProgress(1, 2, 3), TodoJson.progress(TodoJson.progress(TodoProgress(1, 2, 3))))
    }

    @Test
    fun aCheckOfAnotherVersionIsReadAsUnverifiableAndMissingFieldsAsEmpty() {
        val stored = """{"text":"t","checks":[{"type":"future_check","what":"x"},{"type":"future"},""" +
            """{"type":"pr_merged"},{"type":"command"}]}"""
        assertEquals(
            TodoDoneWhen(
                "t",
                listOf(
                    TodoCheck.Attested("x"),
                    TodoCheck.Attested(""),
                    TodoCheck.PrMerged(""),
                    TodoCheck.Command("", expectExit = 0, timeoutSeconds = 0),
                ),
            ),
            TodoJson.doneWhen(stored),
        )
        val closed = TodoJson.doneWhen("""{"text":"t","checks":[{"type":"pr_closed"},{"type":"ci_green"}]}""")
        assertEquals(listOf(TodoCheck.PrClosed("", ""), TodoCheck.CiGreen("")), closed.checks)
        val shipped = TodoJson.doneWhen("""{"text":"t","checks":[{"type":"release_contains"}]}""")
        assertEquals(listOf<TodoCheck>(TodoCheck.ReleaseContains("")), shipped.checks)
    }

    @Test
    fun theModuleRegistersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("todos", TodosModule.name)
        assertEquals(1, TodosModule.migrations.size)
        koinApplication { modules(TodosModule.koinModule) }.close()
    }
}
