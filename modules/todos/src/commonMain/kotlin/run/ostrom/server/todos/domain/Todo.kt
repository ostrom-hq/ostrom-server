// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Where a session's todo is in its life.
 *
 * @property wire The contract's value.
 */
enum class TodoListStatus(val wire: String) {
    /** Items remain, or the final verification has not passed yet. */
    ACTIVE("active"),

    /** Every item is done or dropped, and the final verification held. */
    DONE("done"),

    /** Given up by the session's owner. */
    ABANDONED("abandoned"),
}

/**
 * Where an item is in its life. Nothing deletes an item: a dropped one stays, shown as such.
 *
 * @property wire The contract's value.
 */
enum class TodoItemStatus(val wire: String) {
    /** Not started. */
    TODO("todo"),

    /** In progress. */
    DOING("doing"),

    /** Every check of its end condition passed. */
    DONE("done"),

    /** Waiting for the owner to agree to drop it. */
    DROP_REQUESTED("drop_requested"),

    /** Dropped with the owner's yes. */
    DROPPED("dropped"),
    ;

    /** Whether nothing remains to do on it: done, or dropped by the owner. */
    val closed: Boolean get() = this == DONE || this == DROPPED

}

/**
 * Whether an item was in the plan or found on the way.
 *
 * @property wire The contract's value.
 */
enum class TodoOrigin(val wire: String) {
    /** Written with the list. */
    PLAN("plan"),

    /** Added later, with its reason. */
    DISCOVERED("discovered"),
}

/**
 * A typed check of an item's end condition. A check never names a number written in advance: a PR, a release or an
 * artifact is found and linked to the item when it exists, and `${pr:<item>.number}` style values in [Command.run]
 * cite it.
 */
sealed interface TodoCheck {
    /** The contract's name of the check. */
    val type: String

    /**
     * The item's pull request is merged; it is found by the marker of `start` in its body.
     *
     * @property repo `owner/name`.
     */
    data class PrMerged(val repo: String) : TodoCheck {
        override val type: String get() = "pr_merged"
    }

    /**
     * The item's pull request is closed without merge, for [reason].
     *
     * @property repo `owner/name`.
     * @property reason Why it is closed.
     */
    data class PrClosed(val repo: String, val reason: String) : TodoCheck {
        override val type: String get() = "pr_closed"
    }

    /**
     * A published release contains the merge of the PR of item [of], or [commit].
     *
     * @property repo `owner/name`.
     * @property of The id of the item whose PR must be shipped.
     * @property commit A commit, instead of [of].
     */
    data class ReleaseContains(val repo: String, val of: String? = null, val commit: String? = null) : TodoCheck {
        override val type: String get() = "release_contains"
    }

    /**
     * Every CI run on the merge of item [of], or on [ref], completed green.
     *
     * @property repo `owner/name`.
     * @property of The id of the item whose merge commit is checked.
     * @property ref A ref, instead of [of].
     * @property workflow Only this workflow's runs.
     */
    data class CiGreen(
        val repo: String,
        val of: String? = null,
        val ref: String? = null,
        val workflow: String? = null,
    ) : TodoCheck {
        override val type: String get() = "ci_green"
    }

    /**
     * A command exits with [expectExit], run on the agent's machine.
     *
     * @property run The command, which may cite `${pr:<item>.number}`, `${pr:<item>.mergeSha}`,
     *   `${release:<item>.tag}`.
     * @property cwd Where it runs.
     * @property expectExit The exit code that passes.
     * @property timeoutSeconds How long it may run.
     */
    data class Command(
        val run: String,
        val cwd: String? = null,
        val expectExit: Int = 0,
        val timeoutSeconds: Int = TodoRules.DEFAULT_COMMAND_SECONDS,
    ) : TodoCheck {
        override val type: String get() = "command"
    }

    /**
     * An artifact is published, at least at [minVersion]; verified by the server.
     *
     * @property id The artifact, or null: the one linked to the item later.
     * @property minVersion The lowest version that passes.
     */
    data class ArtifactPublished(val id: String? = null, val minVersion: Int? = null) : TodoCheck {
        override val type: String get() = "artifact_published"
    }

    /**
     * The wait linked to the item was answered, with [action] when it is set; verified by the server.
     *
     * @property action The action that passes, or null for any.
     */
    data class WaitAnswered(val action: String? = null) : TodoCheck {
        override val type: String get() = "wait_answered"
    }

    /**
     * The agent gives a proof that nobody verifies: shown as "unverified".
     *
     * @property what What is attested.
     */
    data class Attested(val what: String) : TodoCheck {
        override val type: String get() = "attested"
    }
}

/**
 * An item's end condition.
 *
 * @property text What done means, in words.
 * @property checks What is observed to say so.
 */
data class TodoDoneWhen(val text: String, val checks: List<TodoCheck>)

/**
 * What the agent writes for an item (create, add).
 *
 * @property id A slug, unique in the list.
 * @property name One line.
 * @property description Enough for a new agent to do it alone.
 * @property doneWhen Its end condition.
 * @property dependsOn Items of the same list to finish first.
 */
data class TodoItemDefinition(
    val id: String,
    val name: String,
    val description: String,
    val doneWhen: TodoDoneWhen,
    val dependsOn: List<String> = emptyList(),
)

/**
 * What an item is bound to once it exists: a `pr` or a `release` ([ref] is its https address), an `artifact` or a
 * `wait` ([ref] is its id). A link is frozen: see [TodoRules.link].
 *
 * @property kind `pr`, `release`, `artifact` or `wait`.
 * @property ref Its address or id.
 * @property number A PR's number.
 * @property sha A PR's merge commit.
 * @property tag A release's tag.
 * @property at When it was linked.
 */
data class TodoLink(
    val kind: String,
    val ref: String,
    val number: Int? = null,
    val sha: String? = null,
    val tag: String? = null,
    val at: Instant? = null,
) {
    /** The kinds of link. */
    companion object {
        /** Every kind, in the contract's order. */
        val KINDS: List<String> = listOf("pr", "release", "artifact", "wait")
    }
}

/**
 * A dated note on an item; notes are only appended.
 *
 * @property at When.
 * @property text What.
 */
data class TodoNote(val at: Instant, val text: String)

/**
 * What the last verification of an item observed.
 *
 * @property at When.
 * @property passed True, false, or null for unknown (never done).
 * @property text What was seen.
 * @property urls Where.
 */
data class TodoProof(val at: Instant, val passed: Boolean?, val text: String, val urls: List<String> = emptyList())

/**
 * An item of a session's todo.
 *
 * @property id Its slug.
 * @property name One line.
 * @property description Complete.
 * @property doneWhen Its end condition, frozen.
 * @property dependsOn The items to finish first.
 * @property origin In the plan, or found on the way.
 * @property originReason Why it was added, for a discovered item.
 * @property status Where it is.
 * @property proof The last verification.
 * @property notes Its notes, oldest first.
 * @property dropReason Why a drop was asked.
 * @property dropWaitId The wait asked of the owner while the item is [TodoItemStatus.DROP_REQUESTED].
 * @property links What it is bound to.
 */
data class TodoItem(
    val id: String,
    val name: String,
    val description: String,
    val doneWhen: TodoDoneWhen,
    val dependsOn: List<String>,
    val origin: TodoOrigin,
    val originReason: String?,
    val status: TodoItemStatus,
    val proof: TodoProof? = null,
    val notes: List<TodoNote> = emptyList(),
    val dropReason: String? = null,
    val dropWaitId: String? = null,
    val links: List<TodoLink> = emptyList(),
) {
    /** True when every check is `attested`: nothing verifies it. */
    val unverified: Boolean get() = doneWhen.checks.all { it is TodoCheck.Attested }
}

/**
 * A counter snapshot taken at a blocked stop: a later stop made progress when an item was finished or added, or a
 * note written, since.
 *
 * @property done Items done.
 * @property items Items.
 * @property notes Notes.
 */
data class TodoProgress(val done: Int, val items: Int, val notes: Int)

/**
 * What the Stop hook recorded about itself: `blocked` (a stall opened a wait) or `hook_error` (it failed open).
 *
 * @property at When.
 * @property kind `blocked` or `hook_error`.
 * @property text What happened.
 */
data class TodoHookEvent(val at: Instant, val kind: String, val text: String) {
    /** The kinds of event. */
    companion object {
        /** A stall opened a wait for the owner. */
        const val BLOCKED: String = "blocked"

        /** The hook failed and let the stop pass. */
        const val HOOK_ERROR: String = "hook_error"
    }
}

/**
 * Something the plan's reference points to, like a wait's: `artifact` or `session` by id, or `url` (https).
 *
 * @property kind `artifact`, `session` or `url`.
 * @property id The artifact's or session's id.
 * @property url The address.
 * @property title Its label.
 */
data class TodoRef(val kind: String, val id: String? = null, val url: String? = null, val title: String? = null)

/**
 * A session's todo: one per session, keyed on the session's first id so a resumed session keeps its list.
 *
 * @property id Its id; its first 8 characters are the marker's prefix.
 * @property orgId The organization.
 * @property sessionId The session's first id (its root).
 * @property reference The plan's reference, shown first everywhere.
 * @property refs What the reference points to.
 * @property status Where it is.
 * @property stall Blocked stops without progress in a row.
 * @property lastStop The counters at the last blocked stop.
 * @property items Its items, in order.
 * @property createdAt When it was created.
 * @property updatedAt When it last changed.
 * @property hookEvents What the Stop hook recorded, oldest first, the newest [TodoRules.MAX_HOOK_EVENTS].
 * @property lastHookAt When the Stop hook last asked about the session.
 */
data class TodoList(
    val id: Uuid,
    val orgId: Uuid,
    val sessionId: String,
    val reference: String,
    val refs: List<TodoRef>,
    val status: TodoListStatus,
    val stall: Int,
    val lastStop: TodoProgress?,
    val items: List<TodoItem>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val hookEvents: List<TodoHookEvent> = emptyList(),
    val lastHookAt: Instant? = null,
) {
    /**
     * An item by id.
     *
     * @param id The item.
     * @return It, or null.
     */
    fun item(id: String): TodoItem? = items.firstOrNull { it.id == id }

    /** Items done. */
    val doneCount: Int get() = items.count { it.status == TodoItemStatus.DONE }

    /** The counters a stop compares. */
    val progress: TodoProgress get() = TodoProgress(doneCount, items.size, items.sumOf { it.notes.size })

    /** Every item is done or dropped by the owner. */
    val allClosed: Boolean get() = items.isNotEmpty() && items.all { it.status.closed }
}

/**
 * One check's outcome, as the process that ran it observed it.
 *
 * @property check The check.
 * @property passed True, false, or null for unknown (timeout, unreachable, not linked yet).
 * @property observed What was seen.
 * @property urls Where.
 */
data class TodoCheckResult(
    val check: TodoCheck,
    val passed: Boolean?,
    val observed: String,
    val urls: List<String> = emptyList(),
)

/**
 * What a blocked stop leads to.
 *
 * @property list The list with its counter updated.
 * @property progressed Whether anything moved since the previous blocked stop.
 * @property escalate Whether the counter reached [TodoRules.STALL_LIMIT]: the owner is asked.
 */
data class TodoStop(val list: TodoList, val progressed: Boolean, val escalate: Boolean)

/**
 * The outcome of [TodoRules.complete]: done, or refused with the list carrying the proof of what was seen.
 */
sealed interface TodoCompletion {
    /** The list after the attempt. */
    val list: TodoList

    /**
     * Every check passed.
     *
     * @property list The list with the item done.
     */
    data class Done(override val list: TodoList) : TodoCompletion

    /**
     * A check failed, was unknown or was not reported.
     *
     * @property list The list with the item's proof.
     * @property failure What to tell the caller (`not_done`).
     */
    data class Refused(override val list: TodoList, val failure: TodoException) : TodoCompletion
}
