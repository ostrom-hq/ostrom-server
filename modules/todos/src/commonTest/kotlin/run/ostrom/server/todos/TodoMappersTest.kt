// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.core.api.models.TodoCheck as TodoCheckDto
import run.ostrom.core.api.models.TodoCheckReport as TodoCheckReportDto
import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoStopAction as TodoStopActionDto
import run.ostrom.core.api.models.TodoStopItemReport as TodoStopItemReportDto
import run.ostrom.core.api.models.TodoStopPhase as TodoStopPhaseDto
import run.ostrom.core.api.models.TodoStopRequest
import run.ostrom.core.api.models.WaitRef
import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoCheckReport
import run.ostrom.server.todos.domain.TodoException
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoProof
import run.ostrom.server.todos.domain.TodoRef
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.domain.TodoStopAction
import run.ostrom.server.todos.domain.TodoStopDecision
import run.ostrom.server.todos.domain.TodoStopItemReport
import run.ostrom.server.todos.domain.TodoStopPhase
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.item
import run.ostrom.server.todos.presentation.link
import run.ostrom.server.todos.presentation.toDomain
import run.ostrom.server.todos.presentation.toDto
import run.ostrom.server.todos.presentation.toInput
import run.ostrom.server.todos.presentation.toRefs
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The contract's forms of a todo, and the domain's forms of what a caller sends. */
class TodoMappersTest {
    private val t0 = Instant.parse("2026-11-13T14:02:00Z")
    private val t1 = Instant.parse("2026-11-13T15:00:00Z")

    private val checks = listOf(
        TodoCheck.PrMerged("acme/x"),
        TodoCheck.PrClosed("acme/x", "superseded"),
        TodoCheck.ReleaseContains("acme/x", of = "a"),
        TodoCheck.CiGreen("acme/x", of = "a", ref = "main", workflow = "ci"),
        TodoCheck.Command("make test", cwd = "/w", expectExit = 2, timeoutSeconds = 90),
        TodoCheck.ArtifactPublished("art", 2),
        TodoCheck.WaitAnswered("yes"),
        TodoCheck.Attested("seen"),
    )

    private fun list() = TodoRules.create(
        Uuid.random(),
        ORG_A,
        "s1",
        "The plan",
        listOf(TodoRef("artifact", id = "plan-1", title = "Plan")),
        listOf(item("a", *checks.toTypedArray()), item("b", TodoCheck.Attested("seen"), deps = listOf("a"))),
        t0,
    )

    @Test
    fun aListIsWrittenAsTheContractSaysWithTheNextItem() {
        val created = list()
        assertEquals("a", created.toDto().nextItemId, "the first ready item")
        val started = TodoRules.start(created, "a", false, null, t0)
        var changed = TodoRules.add(started, item("c"), "Found on the way")
        changed = TodoRules.recordEvent(changed, TodoHookEvent.HOOK_ERROR, "gh failed", t0)
        val proof = TodoProof(t0, true, "merged", listOf("https://github.com/acme/x/pull/1"))
        val links = listOf(TodoLink("pr", "https://github.com/acme/x/pull/1", 1, at = t1), TodoLink("wait", "w"))
        changed = changed.copy(
            updatedAt = t1.plus(kotlin.time.Duration.parse("1h")),
            items = changed.items.map { if (it.id == "a") it.copy(proof = proof, links = links) else it },
        )
        val dto = changed.toDto()
        assertEquals("a", dto.nextItemId, "the item in progress")
        assertEquals(listOf(WaitRef("artifact", "plan-1", null, "Plan")), dto.refs)
        val a = dto.items.first { it.id == "a" }
        assertEquals(checks, a.doneWhen.checks.map { it.toDomain() }, "every check reads back")
        assertEquals("doing", a.status.value)
        assertEquals(TodoItemOrigin.PLAN, a.origin)
        assertEquals("merged", a.proof!!.text)
        assertEquals(listOf(t1.toString(), t0.toString()), a.links.map { it.at }, "a link without a date: the list's")
        assertEquals(TodoItemOrigin.DISCOVERED, dto.items.first { it.id == "c" }.origin)
        assertNull(dto.items.first { it.id == "c" }.proof)
        assertEquals("hook_error", dto.hookEvents.single().kind.value)
        assertEquals(true, dto.items.first { it.id == "b" }.unverified)

        val closed = changed.copy(items = changed.items.map { it.copy(status = TodoItemStatus.DONE) })
        assertNull(closed.toDto().nextItemId, "nothing left to do")
    }

    @Test
    fun aCheckOrALinkOfAnotherVersionIsRefused() {
        val future = TodoCheckDto.Unknown("future", JsonObject(emptyMap()))
        val unknown = assertFailsWith<TodoException> { future.toDomain() }
        assertEquals("invalid_check", unknown.type)
        val kind = assertFailsWith<TodoException> { link(TodoLinkKind.UNKNOWN, "x", null, null, null) }
        assertEquals("invalid_link", kind.type)
        val pr = link(TodoLinkKind.entries.first { it.value == "pr" }, "https://x/pull/1", 1, "abc", null)
        assertEquals(TodoLink("pr", "https://x/pull/1", 1, "abc"), pr)
        assertEquals(listOf(TodoRef("url", url = "https://x")), listOf(WaitRef("url", url = "https://x")).toRefs())
    }

    @Test
    fun aStopQuestionReadsItsPhaseAndKeepsTheEndOfTheLastMessage() {
        val reports = listOf(TodoStopItemReportDto("a", listOf(TodoCheckReportDto(0, true, "merged", listOf("u")))))
        val request = TodoStopRequest(
            phase = TodoStopPhaseDto.VERIFY,
            background = true,
            items = reports,
            orphans = listOf("https://x/pull/2"),
            lastMessage = "m".repeat(3_000),
        )
        val input = request.toInput()
        assertEquals(TodoStopPhase.VERIFY, input.phase)
        val report = TodoStopItemReport("a", listOf(TodoCheckReport(0, true, "merged", listOf("u"))))
        assertEquals(listOf(report), input.items)
        assertEquals(2_000, input.lastMessage!!.length)
        assertEquals(listOf("https://x/pull/2"), input.orphans)
        assertNull(TodoStopRequest().toInput().lastMessage)
        val refused = assertFailsWith<TodoException> { TodoStopRequest(phase = TodoStopPhaseDto.UNKNOWN).toInput() }
        assertEquals("invalid_stop", refused.type)
    }

    @Test
    fun aStopAnswerCarriesTheListWhenThereIsOne() {
        val blocked = TodoStopDecision(TodoStopAction.BLOCK, list(), "stuck", "why", escalated = true).toDto()
        assertEquals(TodoStopActionDto.entries.first { it.value == "block" }, blocked.action)
        assertEquals("The plan", blocked.todo!!.reference)
        assertEquals(true, blocked.escalated)
        assertNull(TodoStopDecision(TodoStopAction.ALLOW, null).toDto().todo)
    }
}
