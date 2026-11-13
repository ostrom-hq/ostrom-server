// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

/**
 * The messages Ostrom writes to an agent about its todo (TeamCode `TodoMessages`): facts and one instruction; the
 * reference and the items are copied as the agent wrote them. The reference comes first, so the agent sees a point of
 * the plan its list is missing, and the list is whole, with only the next item in full.
 */
object TodoMessages {
    /** Longest message. */
    const val MAX_CHARS: Int = 8_000

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val ID_WIDTH = 22
    private const val NAME_WIDTH = 60
    private const val NEXT_NAME_WIDTH = 80
    private const val CHECK_WIDTH = 400
    private const val DONE_REF_WIDTH = 40
    private const val LAST_MESSAGE_WIDTH = 600
    private const val TITLE_WIDTH = 120
    private const val BODY_WIDTH = 4_000

    /** `HH:MM` in UTC. */
    private fun clock(at: kotlin.time.Instant): String {
        val minutes = (at.toEpochMilliseconds() / MILLIS_PER_MINUTE) % MINUTES_PER_DAY
        val hours = (minutes / MINUTES_PER_HOUR).toString().padStart(2, '0')
        return "$hours:${(minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')}"
    }

    private fun oneLine(text: String, max: Int): String {
        val line = text.filter { !it.isISOControl() || it == '\n' }.replace('\n', ' ').trim()
        return if (line.length > max) line.take(max - 1) + "…" else line
    }

    private fun mark(item: TodoItem, next: TodoItem?): String = when {
        item.status == TodoItemStatus.DONE -> "✓"
        item.status == TodoItemStatus.DROPPED -> "✗"
        item.status == TodoItemStatus.DROP_REQUESTED -> "?"
        item.id == next?.id -> "→"
        else -> "·"
    }

    private fun detail(item: TodoItem): String {
        val pr = item.links.firstOrNull { it.kind == "pr" }
        return when {
            item.status == TodoItemStatus.DONE && pr?.number != null -> "PR #${pr.number} merged"
            item.status == TodoItemStatus.DONE ->
                item.links.firstOrNull()?.let { "${it.kind} ${it.ref.takeLast(DONE_REF_WIDTH)}" }.orEmpty()
            item.status == TodoItemStatus.DROPPED -> "dropped by the owner"
            item.status == TodoItemStatus.DROP_REQUESTED -> "drop requested, waiting for the owner"
            item.unverified -> "(attested)"
            item.originReason == TodoRules.ORPHAN_REASON -> "(opened in this session, not covered by an item)"
            else -> ""
        }
    }

    /**
     * The message that sends the agent back to work: the `block` reason of the Stop hook.
     *
     * @param list The list.
     * @return The message, at most [MAX_CHARS].
     */
    fun resume(list: TodoList): String = compose(
        list,
        "[Ostrom todo] ${list.doneCount} of ${list.items.size} items done. Items remain and no wait is open, so " +
            "the turn goes on.",
        "Do the next item now. If a person must decide or act, call ask_human; to wait\nfor a CI or a delay, call " +
            "wait_for; if an item no longer makes sense, call\ntodo_drop. Each of these ends the turn properly.",
    )

    /**
     * The same block without the instruction to go on: what a session is reminded of after a compaction, when it
     * resumes, and (once the waits can wake a session) after an answer. It says where the plan stands; what to do
     * comes from the message it follows.
     *
     * @param list The list.
     * @return The block, at most [MAX_CHARS].
     */
    fun context(list: TodoList): String {
        val state = if (list.status == TodoListStatus.ACTIVE) "." else " (${list.status.wire})."
        return compose(list, "[Ostrom todo] Your todo: ${list.doneCount} of ${list.items.size} items done$state", null)
    }

    private fun compose(list: TodoList, head: String, tail: String?): String {
        // The item in progress comes first: after a compaction or a pause it is the one to finish.
        val next = list.items.firstOrNull { it.status == TodoItemStatus.DOING } ?: TodoRules.next(list)
        val reference = list.reference.trim().lines().joinToString("\n") { "  $it" }
        val items = list.items.joinToString("\n") { item ->
            val detail = detail(item)
            val line = "  ${mark(item, next)} ${item.id.padEnd(ID_WIDTH)} ${oneLine(item.name, NAME_WIDTH)}"
            if (detail.isEmpty()) line else "$line   $detail"
        }
        val nextBlock = nextBlock(list, next)
        val text = listOfNotNull(head, "Plan reference:\n$reference", nextBlock, "Items:\n$items", tail)
            .joinToString("\n")
        if (text.length <= MAX_CHARS) return text
        // Too long: the item list gives way (the reference and the next item are what the agent needs).
        val short = list.items.joinToString("\n") { "  ${mark(it, next)} ${it.id}" }
        return listOfNotNull(head, "Plan reference:\n$reference", nextBlock, "Items:\n$short", tail)
            .joinToString("\n").take(MAX_CHARS)
    }

    private fun nextBlock(list: TodoList, next: TodoItem?): String = when {
        list.status != TodoListStatus.ACTIVE || list.allClosed -> "No item remains open."
        next == null -> "No item is ready: an item waits for another one, or for the owner. Call todo_show."
        else -> buildString {
            appendLine("Next item: ${next.id} — ${oneLine(next.name, NEXT_NAME_WIDTH)}")
            next.description.trim().lines().forEach { appendLine("  $it") }
            appendLine("  Done when: ${next.doneWhen.text.trim().lines().joinToString("\n  ")}")
            next.proof?.let { appendLine("  Last check (${clock(it.at)}): ${oneLine(it.text, CHECK_WIDTH)}") }
        }.trimEnd()
    }

    /**
     * The title of the wait opened for the owner on the third stop without progress.
     *
     * @param list The list.
     * @return The title.
     */
    fun blockedTitle(list: TodoList): String = "Blocked: ${TodoRules.next(list)?.name ?: "the todo"}".take(TITLE_WIDTH)

    /**
     * The body of that wait: the list, and the agent's last message.
     *
     * @param list The list.
     * @param lastMessage The agent's last message, if the hook read one.
     * @return The body.
     */
    fun blockedBody(list: TodoList, lastMessage: String?): String = buildString {
        appendLine(
            "The agent stopped three times in a row without finishing an item, adding one or writing a note, and no " +
                "wait is open.",
        )
        appendLine()
        appendLine("${list.doneCount} of ${list.items.size} items done:")
        val next = TodoRules.next(list)
        list.items.forEach { appendLine("- ${mark(it, next)} `${it.id}` ${oneLine(it.name, NEXT_NAME_WIDTH)}") }
        if (!lastMessage.isNullOrBlank()) {
            appendLine()
            appendLine("Its last message:")
            appendLine("> " + oneLine(lastMessage, LAST_MESSAGE_WIDTH))
        }
    }.take(BODY_WIDTH)
}
