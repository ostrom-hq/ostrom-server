// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoCheckResult
import run.ostrom.server.todos.domain.TodoCompletion
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoException
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoItemDefinition
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoOrigin
import run.ostrom.server.todos.domain.TodoRef
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.fakes.LONG
import run.ostrom.server.todos.fakes.ORG_A
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rules of TeamCode's `TodoRulesTest`, ported: what a list accepts, and how its items move. */
class TodoRulesTest {
    private val repo = "acme/backend"
    private val listId = Uuid.parse("7f3a9c10-aaaa-4bbb-8ccc-000000000001")
    private val pr = TodoCheck.PrMerged(repo)
    private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

    private fun item(id: String, vararg deps: String, checks: List<TodoCheck> = listOf(pr)) =
        TodoItemDefinition(id, "Item $id", LONG, TodoDoneWhen("It is merged", checks), deps.toList())

    private fun list(vararg items: TodoItemDefinition, refs: List<TodoRef> = emptyList(), reference: String = "Plan") =
        TodoRules.create(listId, ORG_A, "s1", reference, refs, items.toList(), at(100))

    private fun code(block: () -> Unit) = assertFailsWith<TodoException> { block() }.type

    /** The refusal of a list of one item with [checks]. */
    private fun refused(vararg checks: TodoCheck) = code { list(item("a", checks = checks.toList())) }

    /** The refusal of a list with [refs]. */
    private fun refusedRefs(vararg refs: TodoRef) = code { list(item("a"), refs = refs.toList()) }

    private fun pass(list: TodoList, id: String) =
        list.item(id)!!.doneWhen.checks.map { TodoCheckResult(it, true, "ok") }

    private fun done(list: TodoList, id: String) =
        assertIs<TodoCompletion.Done>(TodoRules.complete(list, id, pass(list, id), at(200))).list

    private fun start(list: TodoList, id: String) = TodoRules.start(list, id, false, null, at(1))

    private val plan
        get() = list(
            item("a"),
            item("b", "a"),
            item("c", "b", checks = listOf(TodoCheck.ReleaseContains(repo, of = "b"), TodoCheck.Command(CITE_C))),
        )

    @Test
    fun aListIsActiveAtOnceWithItsItemsInOrder() {
        val created = plan
        assertEquals(TodoListStatus.ACTIVE, created.status)
        assertEquals(listOf("a", "b", "c"), created.items.map { it.id })
        assertTrue(created.items.all { it.status == TodoItemStatus.TODO && it.origin == TodoOrigin.PLAN })
    }

    @Test
    fun creationRefusesWhatCannotBeChecked() {
        assertEquals("check_required", refused())
        assertEquals("description_too_short", code { list(item("a").copy(description = "short")) })
        assertEquals("description_too_short", code { list(item("a").copy(description = "x".repeat(4_001))) })
        assertEquals("duplicate_item", code { list(item("a"), item("a")) })
        assertEquals("unknown_dependency", code { list(item("a", "ghost")) })
        assertEquals("unknown_dependency", refused(TodoCheck.ReleaseContains(repo, of = "ghost")))
        assertEquals("unknown_dependency", refused(TodoCheck.CiGreen(repo, of = "ghost")))
        assertEquals("unknown_dependency", refused(TodoCheck.Command("echo \${pr:ghost.number}")))
        assertEquals("dependency_cycle", code { list(item("a", "b"), item("b", "a")) })
        assertEquals("dependency_cycle", code { list(item("a", "a")) })
        assertEquals("too_many_items", code { list(*(1..501).map { item("i$it") }.toTypedArray()) })
    }

    @Test
    fun creationRefusesAnItemOutOfItsBounds() {
        val noText = TodoDoneWhen("", listOf(pr))
        assertEquals("invalid_todo", code { list(item("Bad Id")) })
        assertEquals("invalid_todo", code { list() })
        assertEquals("invalid_todo", code { list(item("a"), reference = " ") })
        assertEquals("invalid_todo", code { list(item("a").copy(name = " ")) })
        assertEquals("invalid_todo", code { list(item("a").copy(doneWhen = noText)) })
        assertEquals("invalid_todo", code { list(item("a").copy(dependsOn = List(501) { "x$it" })) })
        assertEquals("invalid_todo", refused(TodoCheck.PrClosed(repo, "")))
        assertEquals("invalid_todo", refused(TodoCheck.Attested(" ")))
        assertEquals("invalid_todo", refused(TodoCheck.WaitAnswered("")))
        assertEquals("invalid_todo", refused(TodoCheck.Command("true", cwd = "")))
        assertEquals("invalid_todo", refused(TodoCheck.ReleaseContains(repo, commit = "")))
        assertEquals("invalid_todo", refused(TodoCheck.Command("")))
    }

    @Test
    fun creationRefusesAMalformedCheck() {
        assertEquals("invalid_check", refused(*Array(21) { pr }))
        assertEquals("invalid_check", refused(TodoCheck.PrMerged("nope")))
        assertEquals("invalid_check", refused(TodoCheck.PrMerged("a/" + "b".repeat(200))))
        assertEquals("invalid_check", refused(TodoCheck.ReleaseContains(repo)))
        assertEquals("invalid_check", refused(TodoCheck.CiGreen(repo)))
        assertEquals("invalid_check", refused(TodoCheck.Command("true", timeoutSeconds = 500)))
        assertEquals("invalid_check", refused(TodoCheck.Command("true", timeoutSeconds = 0)))
        assertEquals("invalid_check", refused(TodoCheck.ArtifactPublished(minVersion = 0)))
    }

    @Test
    fun everyCheckTypeIsAcceptedWithItsFields() {
        val checks = listOf(
            pr,
            TodoCheck.PrClosed(repo, "superseded"),
            TodoCheck.ReleaseContains(repo, commit = "4e1c9a2"),
            TodoCheck.CiGreen(repo, ref = "main", workflow = "ci"),
            TodoCheck.Command("make test", cwd = "/work", expectExit = 0, timeoutSeconds = 120),
            TodoCheck.ArtifactPublished("art-1", minVersion = 2),
            TodoCheck.ArtifactPublished(),
            TodoCheck.WaitAnswered("approve"),
            TodoCheck.WaitAnswered(),
            TodoCheck.Attested("watched it"),
        )
        assertEquals(checks, list(item("a", checks = checks)).item("a")!!.doneWhen.checks)
        assertEquals(
            listOf(
                "pr_merged", "pr_closed", "release_contains", "ci_green", "command", "artifact_published",
                "artifact_published", "wait_answered", "wait_answered", "attested",
            ),
            checks.map { it.type },
        )
    }

    @Test
    fun theReferencesOfAListAreBounded() {
        val ok = listOf(
            TodoRef("artifact", id = "a1"),
            TodoRef("session", id = "s0", title = "The plan"),
            TodoRef("url", url = "https://x.test/plan"),
        )
        assertEquals(ok, list(item("a"), refs = ok).refs)
        assertEquals("invalid_todo", refusedRefs(*Array(11) { TodoRef("artifact", id = "a$it") }))
        assertEquals("invalid_todo", refusedRefs(TodoRef("url", url = "http://x.test")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("url", url = "https://x.test", id = "x")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("url")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("url", url = "https://" + "x".repeat(2_000))))
        assertEquals("invalid_todo", refusedRefs(TodoRef("artifact")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("artifact", id = "")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("artifact", id = "x".repeat(201))))
        assertEquals("invalid_todo", refusedRefs(TodoRef("artifact", id = "a", url = "https://x.test")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("tweet", id = "a")))
        assertEquals("invalid_todo", refusedRefs(TodoRef("artifact", id = "a", title = "t".repeat(81))))
    }

    @Test
    fun aGuessedPrNumberOrVersionIsRefused() {
        assertEquals("guessed_number", refused(TodoCheck.Command("gh api repos/acme/backend/pulls/1310")))
        assertEquals("guessed_number", refused(TodoCheck.Command("gh release view releases/tag/v1.2.3")))
        assertEquals("guessed_number", refused(TodoCheck.CiGreen(repo, ref = "PR #12")))
        list(item("a", checks = listOf(TodoCheck.Command("gh api repos/acme/backend/pulls/\${pr:a.number}"))))
    }

    @Test
    fun oneListPerSession() {
        TodoRules.requireNoList(null)
        assertEquals("todo_exists", code { TodoRules.requireNoList(plan) })
    }

    @Test
    fun nextIsTheFirstTodoWhoseDependenciesAreDone() {
        var l = plan
        assertEquals("a", TodoRules.next(l)?.id)
        l = done(l, "a")
        assertEquals("b", TodoRules.next(l)?.id)
        l = start(l, "b")
        assertNull(TodoRules.next(l), "b is doing and c waits for it")
        l = done(l, "b")
        assertEquals("c", TodoRules.next(l)?.id)
    }

    @Test
    fun startRefusesOpenDependenciesAndKeepsOneItemInProgress() {
        val l = list(item("a"), item("b"), item("c", "a"))
        assertEquals("dependency_open", code { start(l, "c") })
        val started = start(start(l, "a"), "b")
        assertEquals(TodoItemStatus.TODO, started.item("a")!!.status)
        assertEquals(TodoItemStatus.DOING, started.item("b")!!.status)
        assertEquals("item_closed", code { start(done(l, "a"), "a") })
        assertEquals("unknown_item", code { start(l, "ghost") })
    }

    @Test
    fun aBackgroundAgentKeepsSeveralItemsInProgress() {
        val l = list(item("a"), item("b"))
        val first = TodoRules.start(l, "a", background = true, agent = "scout", now = at(5))
        val started = TodoRules.start(first, "b", background = true, agent = null, now = at(6))
        assertEquals(listOf(TodoItemStatus.DOING, TodoItemStatus.DOING), started.items.map { it.status })
        assertTrue(started.item("a")!!.notes.single().text.contains("scout"))
        assertTrue(started.item("b")!!.notes.isEmpty(), "no agent named, no note")
    }

    @Test
    fun anUnknownCheckIsNeverDone() {
        val l = plan
        val checks = l.item("c")!!.doneWhen.checks
        val results = listOf(
            TodoCheckResult(checks[0], true, "release v2"),
            TodoCheckResult(checks[1], null, "waiting for the release"),
        )
        val refused = assertIs<TodoCompletion.Refused>(TodoRules.complete(l, "c", results, at(300)))
        assertEquals("not_done", refused.failure.type)
        assertEquals(TodoItemStatus.TODO, refused.list.item("c")!!.status)
        assertNull(refused.list.item("c")!!.proof!!.passed, "unknown is never done")
        assertTrue(refused.failure.message.contains("unknown: waiting for the release"))
    }

    @Test
    fun anItemIsDoneOnlyWhenEveryCheckPasses() {
        val l = plan
        val checks = l.item("c")!!.doneWhen.checks
        val failing = checks.map { TodoCheckResult(it, false, "OPEN, 2 checks failing") }
        val failed = assertIs<TodoCompletion.Refused>(TodoRules.complete(l, "c", failing, at(300)))
        assertEquals(false, failed.list.item("c")!!.proof!!.passed)
        val partial = listOf(TodoCheckResult(checks[0], true, "ok"))
        val missing = assertIs<TodoCompletion.Refused>(TodoRules.complete(l, "c", partial, at(300)))
        assertTrue(missing.failure.message.contains("command"))
        val nothing = assertIs<TodoCompletion.Refused>(TodoRules.complete(l, "c", emptyList(), at(300)))
        val proof = nothing.list.item("c")!!.proof!!
        assertTrue(proof.text.startsWith("not every check ran"), "the reason is the proof when nothing was seen")
        val foreign = TodoCheckResult(TodoCheck.PrMerged("other/repo"), false, "not this item's", listOf("https://y/1"))
        val passing = checks.map { TodoCheckResult(it, true, "merged", listOf("https://x/1")) } + foreign
        val ok = assertIs<TodoCompletion.Done>(TodoRules.complete(l, "c", passing, at(300)))
        val finished = ok.list.item("c")!!
        assertEquals(TodoItemStatus.DONE, finished.status)
        assertEquals(true, finished.proof!!.passed)
        assertEquals(listOf("https://x/1"), finished.proof!!.urls)
    }

    @Test
    fun aDroppedItemCannotBeCompleted() {
        val l = TodoRules.requestDrop(plan, "a", "No longer needed", "w1")
        assertEquals("item_closed", code { TodoRules.complete(l, "a", pass(l, "a"), at(1)) })
        val dropped = TodoRules.resolveDrop(l, "a", true)
        assertEquals("item_closed", code { TodoRules.complete(dropped, "a", pass(l, "a"), at(1)) })
        val finished = done(plan, "a")
        assertEquals("item_closed", code { TodoRules.complete(finished, "a", emptyList(), at(1)) }, "the proof is kept")
    }

    @Test
    fun anItemAddedOnTheWayIsPlacedAfterTheOneInProgressOrItsDependencies() {
        var l = start(plan, "a")
        l = TodoRules.add(l, item("extra"), "The plan needs a migration")
        assertEquals(listOf("a", "extra", "b", "c"), l.items.map { it.id })
        assertEquals(TodoOrigin.DISCOVERED, l.item("extra")!!.origin)
        assertEquals("The plan needs a migration", l.item("extra")!!.originReason)
        l = TodoRules.add(l, item("late", "c"), "After the release")
        assertEquals("late", l.items.last().id)
        assertEquals("duplicate_item", code { TodoRules.add(l, item("late"), "again") })
        assertEquals("unknown_dependency", code { TodoRules.add(l, item("x", "ghost"), "why") })
        assertEquals("invalid_todo", code { TodoRules.add(l, item("y"), " ") })
        val appended = TodoRules.add(plan, item("tail"), "No item in progress")
        assertEquals("tail", appended.items.last().id)
    }

    @Test
    fun aListHoldsAtMostFiveHundredItems() {
        val full = list(*(1..500).map { item("i$it") }.toTypedArray())
        assertEquals(500, full.items.size, "500 items are accepted (TeamCode #250)")
        assertEquals("too_many_items", code { TodoRules.add(full, item("one-more"), "found") })
        assertEquals(full, TodoRules.addOrphan(full, "$X_PULL/7", at(1)), "no orphan past the limit")
    }

    @Test
    fun conditionsAreFrozenAndNotesOnlyAppend() {
        var l = TodoRules.note(plan, "a", "Tried the first approach", at(150))
        l = TodoRules.note(l, "a", "Second approach", at(160))
        assertEquals(listOf("Tried the first approach", "Second approach"), l.item("a")!!.notes.map { it.text })
        assertEquals(plan.item("a")!!.doneWhen, l.item("a")!!.doneWhen)
        assertEquals("unknown_item", code { TodoRules.note(l, "ghost", "x", at(1)) })
        assertEquals("invalid_todo", code { TodoRules.note(l, "a", "", at(1)) })
        var full = plan
        repeat(100) { full = TodoRules.note(full, "a", "note $it", at(1)) }
        assertEquals("too_many_notes", code { TodoRules.note(full, "a", "one more", at(1)) })
    }

    @Test
    fun anItemIsDroppedOnlyOnTheOwnersYes() {
        var l = TodoRules.requestDrop(plan, "b", "No longer makes sense", "w1")
        assertEquals(TodoItemStatus.DROP_REQUESTED, l.item("b")!!.status)
        assertEquals("w1", l.item("b")!!.dropWaitId)
        assertEquals("drop_pending", code { TodoRules.requestDrop(l, "b", "again", null) })
        assertEquals("drop_pending", code { start(l, "b") })
        val refused = TodoRules.resolveDrop(l, "b", approved = false)
        assertEquals(TodoItemStatus.TODO, refused.item("b")!!.status)
        assertNull(refused.item("b")!!.dropWaitId)
        l = TodoRules.resolveDrop(l, "b", approved = true)
        assertEquals(TodoItemStatus.DROPPED, l.item("b")!!.status)
        assertEquals(3, l.items.size, "nothing is deleted")
        assertEquals("no_drop_request", code { TodoRules.resolveDrop(l, "a", true) })
        assertEquals("c", TodoRules.next(done(l, "a"))?.id, "a dropped dependency no longer blocks")
        assertEquals("invalid_todo", code { TodoRules.requestDrop(plan, "a", "", null) })
    }

    @Test
    fun aDoneItemCannotBeDropped() {
        val l = done(list(item("a")), "a")
        assertEquals("item_closed", code { TodoRules.requestDrop(l, "a", "why", null) })
    }

    @Test
    fun anAttestedItemStaysDoneAtTheFinalVerification() {
        val attested = TodoCheck.Attested("Published the summary")
        val l = done(done(list(item("a", checks = listOf(attested)), item("b")), "a"), "b")
        // The hook has no proof to give for an attested check at this phase and reports it as not passed (#247).
        val noProof = mapOf(
            "a" to listOf(TodoCheckResult(attested, false, "This check is attested: give proof.text")),
            "b" to pass(l, "b"),
        )
        val settled = TodoRules.settle(l, noProof, at(400))
        assertEquals(TodoListStatus.DONE, settled.status)
        assertEquals(TodoItemStatus.DONE, settled.item("a")!!.status)
        // Another check of the same item still reopens it.
        val closed = done(list(item("c", checks = listOf(attested, pr))), "c")
        val results = listOf(
            TodoCheckResult(attested, false, "no proof"),
            TodoCheckResult(pr, false, "PR reverted", listOf("https://x/2")),
        )
        val reverted = TodoRules.settle(closed, mapOf("c" to results), at(400)).item("c")!!
        assertEquals(TodoItemStatus.TODO, reverted.status)
        assertEquals("PR reverted", reverted.proof!!.text)
        assertEquals(listOf("https://x/2"), reverted.proof!!.urls)
    }

    @Test
    fun theListIsDoneOnlyAfterTheFinalVerification() {
        val l = done(done(list(item("a"), item("b")), "a"), "b")
        assertTrue(l.allClosed)
        val revertedA = pass(l, "a").map { it.copy(passed = false, observed = "PR reverted") }
        val regress = TodoRules.settle(l, mapOf("a" to revertedA, "b" to pass(l, "b")), at(400))
        assertEquals(TodoListStatus.ACTIVE, regress.status)
        assertEquals(TodoItemStatus.TODO, regress.item("a")!!.status, "a reverted PR reopens its item")
        assertEquals(TodoItemStatus.DONE, regress.item("b")!!.status)
        val unknown = TodoRules.settle(l, mapOf("a" to pass(l, "a").map { it.copy(passed = null) }), at(400))
        assertEquals(TodoListStatus.DONE, unknown.status, "only a failing check reopens, an unanswered one does not")
        val other = listOf(TodoCheckResult(TodoCheck.PrMerged("other/repo"), false, "not this item's"))
        val foreign = TodoRules.settle(l, mapOf("a" to other), at(400))
        assertEquals(TodoListStatus.DONE, foreign.status, "a result of a check the item does not have changes nothing")
        val finished = TodoRules.settle(l, emptyMap(), at(400))
        assertEquals(TodoListStatus.DONE, finished.status)
        assertEquals(finished, TodoRules.settle(finished, emptyMap(), at(500)), "a finished list is not settled again")
        val open = list(item("a"))
        assertEquals(open, TodoRules.settle(open, emptyMap(), at(1)))
        val dropped = TodoRules.resolveDrop(TodoRules.requestDrop(list(item("a")), "a", "why", "w"), "a", true)
        assertEquals(TodoListStatus.DONE, TodoRules.settle(dropped, emptyMap(), at(1)).status, "dropped is closed")
    }

    @Test
    fun aClosedListRefusesChanges() {
        val finished = TodoRules.settle(done(list(item("a")), "a"), emptyMap(), at(1))
        assertEquals("todo_closed", code { TodoRules.add(finished, item("b"), "why") })
        assertEquals("todo_closed", code { start(finished, "a") })
        assertEquals("todo_closed", code { TodoRules.note(finished, "a", "x", at(1)) })
        assertEquals("todo_closed", code { TodoRules.complete(finished, "a", emptyList(), at(1)) })
        assertEquals("todo_closed", code { TodoRules.link(finished, "a", TodoLink("wait", "w"), false, at(1)) })
        assertEquals("todo_closed", code { TodoRules.requestDrop(finished, "a", "why", null) })
        assertEquals(finished, TodoRules.addOrphan(finished, "$X_PULL/7", at(1)))
    }

    @Test
    fun theStallCounterCountsStopsWithoutProgressAndAsksAtThree() {
        var stop = TodoRules.stop(plan)
        assertFalse(stop.progressed, "the first blocked stop has nothing to compare with")
        assertEquals(1, stop.list.stall)
        stop = TodoRules.stop(stop.list)
        assertEquals(2, stop.list.stall)
        assertFalse(stop.escalate)
        stop = TodoRules.stop(stop.list)
        assertEquals(3, stop.list.stall)
        assertTrue(stop.escalate)
        assertEquals(0, TodoRules.resetStall(stop.list).stall)
    }

    @Test
    fun progressResetsTheCounter() {
        var stop = TodoRules.stop(TodoRules.stop(plan).list)
        assertEquals(2, stop.list.stall)
        stop = TodoRules.stop(TodoRules.note(stop.list, "a", "Working on it", at(5)))
        assertTrue(stop.progressed)
        assertEquals(0, stop.list.stall)
        stop = TodoRules.stop(TodoRules.add(stop.list, item("more"), "Found"))
        assertTrue(stop.progressed, "an added item is progress")
        val finished = TodoRules.stop(done(stop.list, "a"))
        assertTrue(finished.progressed, "an item done is progress")
        assertFalse(TodoRules.stop(finished.list).progressed)
    }

    @Test
    fun markersLinkAPrWhateverItsNumber() {
        val marker = TodoRules.marker(listId, "backend-endpoint")
        assertEquals("<!-- ostrom-todo: 7f3a9c10/backend-endpoint -->", marker)
        assertEquals(listOf("7f3a9c10" to "backend-endpoint"), TodoRules.markers("Fixes things\n\n$marker\n"))
        assertTrue(TodoRules.markers("no marker here").isEmpty())
    }

    @Test
    fun aListMostlyAttestedIsFlagged() {
        val attested = listOf(TodoCheck.Attested("research done"))
        assertFalse(TodoRules.mostlyAttested(list(item("a"), item("b"), item("c", checks = attested))))
        val two = list(item("a"), item("b", checks = attested), item("c", checks = attested))
        assertTrue(TodoRules.mostlyAttested(two))
    }

    @Test
    fun aLinkIsFrozenButMayBeCompleted() {
        val l = list(item("a"))
        val first = TodoLink("pr", "$PULL/7", number = 7)
        var linked = TodoRules.link(l, "a", first, false, at(10))
        assertEquals(listOf(first.copy(at = at(10))), linked.item("a")!!.links)
        // The same PR learns its merge commit; another PR is refused.
        linked = TodoRules.link(linked, "a", first.copy(number = null, sha = "4e1c9a2"), false, at(20))
        assertEquals("4e1c9a2", linked.item("a")!!.links.single().sha)
        assertEquals(7, linked.item("a")!!.links.single().number)
        assertEquals("already_linked", code { TodoRules.link(linked, "a", TodoLink("pr", "$PULL/8"), false, at(30)) })
        // Other kinds are independent; a release is completed with its tag.
        val release = TodoLink("release", "https://github.com/acme/backend/releases/tag/v1.2.0")
        var both = TodoRules.link(linked, "a", release, false, at(50))
        both = TodoRules.link(both, "a", release.copy(tag = "v1.2.0"), false, at(55))
        assertEquals(setOf("pr", "release"), both.item("a")!!.links.map { it.kind }.toSet())
        assertEquals("v1.2.0", both.item("a")!!.links.last().tag)
        assertEquals("4e1c9a2", both.item("a")!!.links.first().sha, "the PR is kept as it was")
        val waited = TodoRules.link(both, "a", TodoLink("wait", "w1"), true, at(1))
        assertEquals("already_linked", code { TodoRules.link(waited, "a", TodoLink("wait", "w2"), true, at(2)) })
    }

    @Test
    fun onlyAPrClosedWithoutMergeIsReplacedAndTheChangeIsNoted() {
        val linked = TodoRules.link(list(item("a")), "a", TodoLink("pr", "$PULL/7", number = 7), false, at(10))
        val replaced = TodoRules.link(linked, "a", TodoLink("pr", "$PULL/8", number = 8), true, at(40)).item("a")!!
        assertEquals(8, replaced.links.single().number)
        assertTrue(replaced.notes.single().text.contains("pull/7"), "the change is noted")
    }

    @Test
    fun aLinkHasBounds() {
        val l = list(item("a"))
        fun linkCode(link: TodoLink) = code { TodoRules.link(l, "a", link, false, at(1)) }
        assertEquals("invalid_link", linkCode(TodoLink("pr", "http://insecure/1")))
        assertEquals("invalid_link", linkCode(TodoLink("release", "v1")))
        assertEquals("invalid_link", linkCode(TodoLink("tweet", "x")))
        assertEquals("invalid_link", linkCode(TodoLink("wait", " ")))
        assertEquals("invalid_link", linkCode(TodoLink("wait", "w\n1")))
        assertEquals("invalid_link", linkCode(TodoLink("wait", "w", sha = "x".repeat(2_001))))
        assertEquals("invalid_link", linkCode(TodoLink("wait", "w", tag = "x".repeat(2_001))))
        assertEquals("unknown_item", code { TodoRules.link(l, "ghost", TodoLink("wait", "w"), false, at(1)) })
        val artifact = TodoRules.link(l, "a", TodoLink("artifact", "art-1"), false, at(1))
        assertEquals("artifact", artifact.item("a")!!.links.single().kind)
    }

    @Test
    fun aPullRequestNoItemCoversBecomesAnItemLinkedToIt() {
        val l = TodoRules.link(list(item("a")), "a", TodoLink("pr", "$X_PULL/1", 1), false, at(1))
        val orphan = TodoRules.addOrphan(l, "$X_PULL/7", at(2))
        assertEquals(listOf("a", "finish-pr-7"), orphan.items.map { it.id })
        val added = orphan.item("finish-pr-7")!!
        assertEquals(TodoOrigin.DISCOVERED, added.origin)
        assertEquals(TodoRules.ORPHAN_REASON, added.originReason)
        assertEquals(listOf<TodoCheck>(TodoCheck.PrMerged("acme/x")), added.doneWhen.checks)
        assertEquals(TodoLink("pr", "$X_PULL/7", 7, at = at(2)), added.links.single())
        assertEquals(orphan, TodoRules.addOrphan(orphan, "$X_PULL/7", at(3)), "the same PR is not added twice")
        assertEquals(l, TodoRules.addOrphan(l, "$X_PULL/1", at(3)), "a linked PR is covered")
        assertEquals(l, TodoRules.addOrphan(l, "https://example.com/acme/x/pull/9", at(3)), "only a GitHub PR")
        val other = TodoRules.addOrphan(orphan, "https://github.com/acme/y/pull/7", at(4))
        assertEquals("finish-pr-7-2", other.items.last().id, "another repository's PR of the same number")
        assertEquals(l, TodoRules.addOrphan(l, "$X_PULL/99999999999", at(3)), "not a PR number")
    }

    @Test
    fun theHookEventsKeepTheNewestTwentyWithoutControlCharacters() {
        var l = plan
        repeat(25) {
            l = TodoRules.recordEvent(l, TodoHookEvent.HOOK_ERROR, "failed ($it)\u0007\nagain", at(it.toLong()))
        }
        assertEquals(20, l.hookEvents.size)
        assertEquals("failed (24)\nagain", l.hookEvents.last().text)
        val long = TodoRules.recordEvent(plan, TodoHookEvent.BLOCKED, "x".repeat(600), at(1))
        assertEquals("x".repeat(500), long.hookEvents.single().text)
    }

    @Test
    fun theCountersOfAListAreItsItemsDoneAndNotes() {
        val l = TodoRules.note(done(plan, "a"), "b", "n", at(1))
        assertEquals(1, l.doneCount)
        assertEquals(listOf(1, 3, 1), l.progress.let { listOf(it.done, it.items, it.notes) })
        assertFalse(l.allClosed)
        assertFalse(l.copy(items = emptyList()).allClosed, "an empty list is not closed")
        assertTrue(list(item("a", checks = listOf(TodoCheck.Attested("x")))).items.single().unverified)
    }

    private companion object {
        const val CITE_C = "test \${release:c.tag} = x"
        const val PULL = "https://github.com/acme/backend/pull"
        const val X_PULL = "https://github.com/acme/x/pull"
    }
}
