// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Bounds and pure rules of a session's todo (TeamCode `docs/todos.md`, `TodoRules`): nothing here reads a store, a
 * clock or GitHub. An item is done only when its checks pass, never because the agent says so; items are added,
 * never deleted without the owner's yes.
 */
// The rules of one aggregate, each a few lines: splitting them would only scatter the list's invariants.
@Suppress("TooManyFunctions")
object TodoRules {
    /** Longest plan reference. */
    const val MAX_REFERENCE: Int = 4_000

    /** Most refs of a list. */
    const val MAX_REFS: Int = 10

    /** Most items of a list (TeamCode #250). */
    const val MAX_ITEMS: Int = 500

    /** Longest item id. */
    const val MAX_ID: Int = 40

    /** Longest item name. */
    const val MAX_NAME: Int = 80

    /** Shortest item description. */
    const val MIN_DESCRIPTION: Int = 80

    /** Longest item description. */
    const val MAX_DESCRIPTION: Int = 4_000

    /** Longest done condition text. */
    const val MAX_DONE_TEXT: Int = 1_000

    /** Longest note. */
    const val MAX_NOTE: Int = 2_000

    /** Most notes of an item. */
    const val MAX_NOTES_PER_ITEM: Int = 100

    /** Longest proof text. */
    const val MAX_PROOF: Int = 2_000

    /** Most URLs of a proof. */
    const val MAX_PROOF_URLS: Int = 10

    /** Longest reason (add, drop, pr_closed). */
    const val MAX_REASON: Int = 1_000

    /** Most checks of an item. */
    const val MAX_CHECKS: Int = 20

    /** A command check's default timeout. */
    const val DEFAULT_COMMAND_SECONDS: Int = 60

    /** A command check's longest timeout. */
    const val MAX_COMMAND_SECONDS: Int = 120

    /** Longest command. */
    const val MAX_COMMAND: Int = 2_000

    /** Longest link reference. */
    const val MAX_LINK: Int = 2_000

    /** Blocked stops without progress before the owner is asked. */
    const val STALL_LIMIT: Int = 3

    /** Characters of the list id a marker keeps. */
    const val MARKER_PREFIX: Int = 8

    /** A list is flagged when more than one item in this many rests on the agent's word only. */
    const val ATTESTED_SHARE: Int = 3

    /** Hook events kept, newest. */
    const val MAX_HOOK_EVENTS: Int = 20

    /** Longest hook event text. */
    const val MAX_HOOK_TEXT: Int = 500

    private val markerPattern = Regex("""<!-- ostrom-todo: ([A-Za-z0-9]+)/([a-z0-9][a-z0-9-]*) -->""")
    private val pullRequest = Regex("https://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/pull/([1-9]\\d{0,8})")

    /**
     * The marker an agent puts in the body of an item's PR, so the PR is found whatever number it gets.
     *
     * @param listId The list.
     * @param itemId The item.
     * @return `<!-- ostrom-todo: <first 8 of the list id>/<item> -->`.
     */
    fun marker(listId: Uuid, itemId: String): String =
        "<!-- ostrom-todo: ${listId.toString().take(MARKER_PREFIX)}/$itemId -->"

    /**
     * The markers found in a PR body.
     *
     * @param body The body.
     * @return The (list prefix, item id) pairs.
     */
    fun markers(body: String): List<Pair<String, String>> =
        markerPattern.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()

    private fun toItem(definition: TodoItemDefinition, origin: TodoOrigin, reason: String?) = TodoItem(
        definition.id,
        definition.name,
        definition.description,
        definition.doneWhen,
        definition.dependsOn.distinct(),
        origin,
        reason,
        TodoItemStatus.TODO,
    )

    /**
     * A new list, active at once. One list per session: the store refuses a second one.
     *
     * @return The list.
     * @throws TodoException When the reference, an item, a dependency or a check is refused.
     */
    @Suppress("LongParameterList") // What a list is made of, each checked here.
    fun create(
        id: Uuid,
        orgId: Uuid,
        sessionId: String,
        reference: String,
        refs: List<TodoRef>,
        items: List<TodoItemDefinition>,
        now: Instant,
    ): TodoList {
        TodoValidation.reference(reference, refs)
        if (items.isEmpty()) throw invalidTodo("invalid_todo", "A todo needs at least one item.")
        if (items.size > MAX_ITEMS) {
            throw invalidTodo(
                "too_many_items",
                "At most $MAX_ITEMS items: split the plan into several sessions, or group items.",
            )
        }
        val duplicate = items.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) throw invalidTodo("duplicate_item", "Item id '${duplicate.key}' is used twice.")
        val known = items.map { it.id }.toSet()
        items.forEach {
            TodoValidation.item(it)
            TodoValidation.links(it, known)
        }
        TodoValidation.acyclic(items)
        return TodoList(
            id,
            orgId,
            sessionId,
            reference,
            refs,
            TodoListStatus.ACTIVE,
            0,
            null,
            items.map { toItem(it, TodoOrigin.PLAN, null) },
            now,
            now,
        )
    }

    /**
     * Refuses a second list for a session.
     *
     * @throws TodoException `todo_exists` when [existing] is set.
     */
    fun requireNoList(existing: TodoList?) {
        if (existing != null) {
            throw todoConflict("todo_exists", "This session already has a todo: use todo_add to add an item.")
        }
    }

    private fun requireActive(list: TodoList) {
        if (list.status != TodoListStatus.ACTIVE) throw todoConflict("todo_closed", "This todo is ${list.status.wire}.")
    }

    private fun requireItem(list: TodoList, id: String): TodoItem = list.item(id) ?: throw unknownItem(id)

    /** A dropped dependency no longer blocks: the owner decided it makes no sense. */
    private fun satisfied(list: TodoList, id: String): Boolean = list.item(id)?.status?.closed == true

    /**
     * The first `todo` item, in list order, whose dependencies are all finished.
     *
     * @return It, or null when there is none.
     */
    fun next(list: TodoList): TodoItem? = list.items.firstOrNull {
        it.status == TodoItemStatus.TODO && it.dependsOn.all { dep -> satisfied(list, dep) }
    }

    /**
     * Adds an item found on the way, with a [reason]. It goes after the item in progress (or at the end), and after
     * its own dependencies.
     *
     * @return The list.
     * @throws TodoException When the list is closed, full, or the item is refused.
     */
    fun add(list: TodoList, definition: TodoItemDefinition, reason: String): TodoList {
        requireActive(list)
        TodoValidation.text(reason, MAX_REASON, "a reason")
        if (list.items.size >= MAX_ITEMS) throw invalidTodo("too_many_items", "At most $MAX_ITEMS items.")
        if (list.item(definition.id) != null) {
            throw invalidTodo("duplicate_item", "Item id '${definition.id}' is already used.")
        }
        TodoValidation.item(definition)
        TodoValidation.links(definition, list.items.map { it.id }.toSet() + definition.id)
        val doing = list.items.indexOfLast { it.status == TodoItemStatus.DOING }
        val afterDeps = definition.dependsOn.maxOfOrNull { dep -> list.items.indexOfFirst { it.id == dep } } ?: -1
        val position = maxOf(if (doing >= 0) doing + 1 else list.items.size, afterDeps + 1)
        val added = toItem(definition, TodoOrigin.DISCOVERED, reason)
        val items = list.items.toMutableList().apply { add(position, added) }
        return list.copy(items = items)
    }

    /**
     * Puts [id] in progress. Another item in progress goes back to `todo`, unless [background]: an item given to a
     * background agent stays in progress while the agent runs.
     *
     * @return The list.
     * @throws TodoException When the item is closed, waits for a drop answer, or has open dependencies.
     */
    fun start(list: TodoList, id: String, background: Boolean, agent: String?, now: Instant): TodoList {
        requireActive(list)
        val item = requireItem(list, id)
        if (item.status.closed) throw todoConflict("item_closed", "Item $id is ${item.status.wire}.")
        if (item.status == TodoItemStatus.DROP_REQUESTED) {
            throw todoConflict("drop_pending", "Item $id waits for the owner to answer its drop request.")
        }
        val missing = item.dependsOn.filterNot { satisfied(list, it) }
        if (missing.isNotEmpty()) {
            throw todoConflict("dependency_open", "Item $id needs ${missing.joinToString()} done first.")
        }
        val handed = background && agent != null
        val items = list.items.map {
            when {
                it.id == id -> it.copy(
                    status = TodoItemStatus.DOING,
                    notes = if (handed) it.notes + TodoNote(now, "Given to background agent $agent.") else it.notes,
                )
                !background && it.status == TodoItemStatus.DOING -> it.copy(status = TodoItemStatus.TODO)
                else -> it
            }
        }
        return list.copy(items = items)
    }

    /**
     * Appends a dated note. Notes are never rewritten.
     *
     * @return The list.
     * @throws TodoException When the note or the item is refused.
     */
    fun note(list: TodoList, id: String, text: String, now: Instant): TodoList {
        requireActive(list)
        TodoValidation.text(text, MAX_NOTE, "a note")
        val item = requireItem(list, id)
        if (item.notes.size >= MAX_NOTES_PER_ITEM) {
            throw invalidTodo("too_many_notes", "An item holds at most $MAX_NOTES_PER_ITEM notes.")
        }
        return withItem(list, id) { it.copy(notes = it.notes + TodoNote(now, text)) }
    }

    /**
     * Applies the outcome of running the end condition of [id]: every check must pass for the item to be done, with
     * the observed proof. A check that did not answer is unknown, never done.
     *
     * @return Done, or refused with the proof kept.
     * @throws TodoException When the list is closed or the item dropped.
     */
    fun complete(list: TodoList, id: String, results: List<TodoCheckResult>, now: Instant): TodoCompletion {
        requireActive(list)
        val item = requireItem(list, id)
        // A done item keeps the proof it was done with: only the final verification reopens it.
        if (item.status.closed || item.status == TodoItemStatus.DROP_REQUESTED) {
            throw todoConflict("item_closed", "Item $id is ${item.status.wire}.")
        }
        // Only the item's own checks count, and only they make its proof.
        val own = results.filter { it.check in item.doneWhen.checks }
        val failed = own.filter { it.passed != true }
        val missing = item.doneWhen.checks.filterNot { check -> own.any { it.check == check } }
        val summary = own.joinToString("; ") { it.observed }.take(MAX_PROOF)
        val urls = own.flatMap { it.urls }.distinct().take(MAX_PROOF_URLS)
        if (failed.isEmpty() && missing.isEmpty()) {
            val proof = TodoProof(now, true, summary, urls)
            return TodoCompletion.Done(withItem(list, id) { it.copy(status = TodoItemStatus.DONE, proof = proof) })
        }
        val why = if (missing.isNotEmpty()) {
            "not every check ran: ${missing.joinToString { it.type }}"
        } else {
            failed.joinToString("; ") { if (it.passed == null) "unknown: ${it.observed}" else it.observed }
        }
        val passed = if (failed.any { it.passed == null }) null else false
        val proof = TodoProof(now, passed, summary.ifBlank { why }.take(MAX_PROOF), urls)
        return TodoCompletion.Refused(
            withItem(list, id) { it.copy(proof = proof) },
            todoConflict("not_done", "Item $id is not done: ${why.take(MAX_PROOF)}"),
        )
    }

    private fun withItem(list: TodoList, id: String, change: (TodoItem) -> TodoItem): TodoList =
        list.copy(items = list.items.map { if (it.id == id) change(it) else it })

    /**
     * Binds [link] to [id]. A link is frozen: the same address may be completed (a PR learns its number and merge
     * commit, a release its tag), but another of the same kind is refused with `already_linked`, so the agent cannot
     * swap in an easier PR. The one exception is a PR closed without merge ([replaceClosed]): the new one replaces it
     * and a note records the change.
     *
     * @return The list.
     * @throws TodoException When the link is malformed or another one is already bound.
     */
    fun link(list: TodoList, id: String, link: TodoLink, replaceClosed: Boolean, now: Instant): TodoList {
        requireActive(list)
        val item = requireItem(list, id)
        validLink(link)
        val existing = item.links.firstOrNull { it.kind == link.kind }
        val stamped = link.copy(at = now)
        val links = when {
            existing == null -> item.links + stamped
            existing.ref == link.ref -> item.links.map {
                if (it === existing) {
                    existing.copy(
                        number = link.number ?: existing.number,
                        sha = link.sha ?: existing.sha,
                        tag = link.tag ?: existing.tag,
                    )
                } else {
                    it
                }
            }
            link.kind == "pr" && replaceClosed -> item.links.map { if (it === existing) stamped else it }
            else -> throw todoConflict(
                "already_linked",
                "Item $id is already linked to ${existing.ref}: a link does not change. Only a PR closed without " +
                    "merge may be replaced.",
            )
        }
        val notes = if (existing != null && existing.ref != link.ref) {
            item.notes + TodoNote(now, "Linked ${link.ref} instead of ${existing.ref}, which was closed without merge.")
        } else {
            item.notes
        }
        return withItem(list, id) { it.copy(links = links, notes = notes) }
    }

    private fun validLink(link: TodoLink) {
        if (link.kind !in TodoLink.KINDS) {
            throw invalidTodo("invalid_link", "Use a link of kind ${TodoLink.KINDS.joinToString(", ")}.")
        }
        val text = listOfNotNull(link.ref, link.sha, link.tag)
        if (link.ref.isBlank() || text.any { it.length > MAX_LINK || it.any(Char::isISOControl) }) {
            throw invalidTodo("invalid_link", "Use a link reference of 1 to $MAX_LINK characters.")
        }
        if (link.kind in setOf("pr", "release") && !link.ref.startsWith("https://")) {
            throw invalidTodo("invalid_link", "A ${link.kind} link is an https address.")
        }
    }

    /**
     * Asks the owner to drop an item. Nothing is deleted: the item waits in `drop_requested` for the answer.
     *
     * @param waitId The wait asked of the owner, or null to only check the request.
     * @return The list.
     * @throws TodoException When the item is closed or already waits for an answer.
     */
    fun requestDrop(list: TodoList, id: String, reason: String, waitId: String?): TodoList {
        requireActive(list)
        TodoValidation.text(reason, MAX_REASON, "a reason")
        val item = requireItem(list, id)
        if (item.status.closed) throw todoConflict("item_closed", "Item $id is ${item.status.wire}.")
        if (item.status == TodoItemStatus.DROP_REQUESTED) {
            throw todoConflict("drop_pending", "Item $id already waits for the owner.")
        }
        return withItem(list, id) {
            it.copy(status = TodoItemStatus.DROP_REQUESTED, dropReason = reason, dropWaitId = waitId)
        }
    }

    /**
     * The owner's answer: yes drops the item, anything else puts it back to `todo`.
     *
     * @return The list.
     * @throws TodoException `no_drop_request` when the item waits for no answer.
     */
    fun resolveDrop(list: TodoList, id: String, approved: Boolean): TodoList {
        val item = requireItem(list, id)
        if (item.status != TodoItemStatus.DROP_REQUESTED) {
            throw todoConflict("no_drop_request", "Item $id has no drop request.")
        }
        val status = if (approved) TodoItemStatus.DROPPED else TodoItemStatus.TODO
        return withItem(list, id) { it.copy(status = status, dropWaitId = null) }
    }

    /**
     * The final verification once every item is closed: [results] maps each done item to its checks' outcomes. An
     * item whose conditions no longer all pass (a reverted PR, a deleted release) is reopened; if none is, the list is
     * done. An `attested` check is never re-run: its proof was given once and nothing can observe it again, so the
     * hook's "no proof" at this phase never undoes what the agent validated (TeamCode #247, baf04038).
     *
     * @return The list, done or with reopened items.
     */
    fun settle(list: TodoList, results: Map<String, List<TodoCheckResult>>, now: Instant): TodoList {
        // Not every item is closed yet: nothing to settle.
        val open = list.status != TodoListStatus.ACTIVE || !list.allClosed
        val failures = if (open) emptyMap() else failures(list, results)
        return when {
            open -> list
            failures.isEmpty() -> list.copy(status = TodoListStatus.DONE)
            else -> reopen(list, failures, now)
        }
    }

    /** The checks of each done item that now fail, an `attested` one never. */
    private fun failures(
        list: TodoList,
        results: Map<String, List<TodoCheckResult>>,
    ): Map<String, List<TodoCheckResult>> =
        list.items.filter { it.status == TodoItemStatus.DONE }.associate { item ->
            item.id to results[item.id].orEmpty().filter { result ->
                result.passed == false && result.check !is TodoCheck.Attested && result.check in item.doneWhen.checks
            }
        }.filterValues { it.isNotEmpty() }

    private fun reopen(list: TodoList, failures: Map<String, List<TodoCheckResult>>, now: Instant): TodoList =
        list.copy(
            items = list.items.map { item ->
                failures[item.id]?.let { failed ->
                    val proof = TodoProof(
                        now,
                        false,
                        failed.joinToString("; ") { it.observed }.take(MAX_PROOF),
                        failed.flatMap { it.urls }.distinct().take(MAX_PROOF_URLS),
                    )
                    item.copy(status = TodoItemStatus.TODO, proof = proof)
                } ?: item
            },
        )

    /**
     * Registers a blocked stop: progress (an item done or added, a note written) since the last blocked stop resets
     * the counter, no progress adds one; at [STALL_LIMIT] the owner is asked instead of blocking again.
     *
     * @return The list with its counter, and whether to ask the owner.
     */
    fun stop(list: TodoList): TodoStop {
        val last = list.lastStop
        val now = list.progress
        val progressed = last != null && (now.done > last.done || now.items > last.items || now.notes > last.notes)
        val stall = if (progressed) 0 else list.stall + 1
        return TodoStop(list.copy(stall = stall, lastStop = now), progressed, stall >= STALL_LIMIT)
    }

    /**
     * Keeps what the Stop hook says about itself, newest last, bounded.
     *
     * @param kind [TodoHookEvent.BLOCKED] or [TodoHookEvent.HOOK_ERROR].
     * @return The list.
     */
    fun recordEvent(list: TodoList, kind: String, text: String, now: Instant): TodoList {
        val clean = text.filter { !it.isISOControl() || it == '\n' }.take(MAX_HOOK_TEXT)
        return list.copy(hookEvents = (list.hookEvents + TodoHookEvent(now, kind, clean)).takeLast(MAX_HOOK_EVENTS))
    }

    /**
     * A pull request opened in the session that no item covers becomes an item of its own, already linked: "Finish
     * PR #n". A PR an item is linked to is not added twice.
     *
     * @param url The PR's address.
     * @return The list, unchanged for another address, a closed or full list, or a PR already covered.
     */
    fun addOrphan(list: TodoList, url: String, now: Instant): TodoList {
        val match = pullRequest.matchEntire(url)
        val covered = list.items.any { item -> item.links.any { it.ref == url } }
        if (match == null || list.status != TodoListStatus.ACTIVE || list.items.size >= MAX_ITEMS || covered) {
            return list
        }
        val repo = match.groupValues[1]
        val number = match.groupValues[2].toInt()
        var id = "finish-pr-$number"
        var n = 2
        while (list.item(id) != null) id = "finish-pr-$number-${n++}"
        val definition = TodoItemDefinition(
            id,
            "Finish PR #$number",
            "A pull request you opened in this session, $url, is not covered by any item of the plan. Get it " +
                "merged, or ask the owner to close it with todo_drop if it no longer makes sense. Out of scope: any " +
                "other change.",
            TodoDoneWhen("PR #$number of $repo is merged", listOf(TodoCheck.PrMerged(repo))),
        )
        val added = add(list, definition, ORPHAN_REASON)
        return link(added, id, TodoLink("pr", url, number), false, now)
    }

    /** Why an orphan PR became an item. */
    const val ORPHAN_REASON: String = "Opened in this session and not covered by an item."

    /**
     * After the owner was asked, the counter starts again.
     *
     * @return The list.
     */
    fun resetStall(list: TodoList): TodoList = list.copy(stall = 0)

    /**
     * Whether more than a third of the items rest on the agent's word only: the console flags it.
     *
     * @return True when flagged.
     */
    fun mostlyAttested(list: TodoList): Boolean = list.items.count { it.unverified } * ATTESTED_SHARE > list.items.size
}
