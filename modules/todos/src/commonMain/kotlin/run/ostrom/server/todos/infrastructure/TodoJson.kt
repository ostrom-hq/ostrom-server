// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.infrastructure

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoNote
import run.ostrom.server.todos.domain.TodoProgress
import run.ostrom.server.todos.domain.TodoProof
import run.ostrom.server.todos.domain.TodoRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** A check as stored: its type and the fields of that type. */
@Serializable
internal data class CheckRow(
    val type: String,
    val repo: String? = null,
    val reason: String? = null,
    val of: String? = null,
    val commit: String? = null,
    val ref: String? = null,
    val workflow: String? = null,
    val run: String? = null,
    val cwd: String? = null,
    val expectExit: Int? = null,
    val timeoutSeconds: Int? = null,
    val id: String? = null,
    val minVersion: Int? = null,
    val action: String? = null,
    val what: String? = null,
)

/** An end condition as stored. */
@Serializable
internal data class DoneWhenRow(val text: String, val checks: List<CheckRow>)

/** A note as stored, its time in epoch milliseconds. */
@Serializable
internal data class NoteRow(val at: Long, val text: String)

/** A proof as stored. */
@Serializable
internal data class ProofRow(val at: Long, val passed: Boolean? = null, val text: String, val urls: List<String>)

/** A link as stored. */
@Serializable
internal data class LinkRow(
    val kind: String,
    val ref: String,
    val number: Int? = null,
    val sha: String? = null,
    val tag: String? = null,
    val at: Long? = null,
)

/** A hook event as stored. */
@Serializable
internal data class EventRow(val at: Long, val kind: String, val text: String)

/** A ref as stored. */
@Serializable
internal data class RefRow(val kind: String, val id: String? = null, val url: String? = null, val title: String? = null)

/** The counters of the last blocked stop as stored. */
@Serializable
internal data class ProgressRow(val done: Int, val items: Int, val notes: Int)

/** The JSON columns of the todo tables: written by this module only, read back as written. */
@Suppress("TooManyFunctions") // A writer and a reader per column.
internal object TodoJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())
    private val notes = ListSerializer(NoteRow.serializer())
    private val links = ListSerializer(LinkRow.serializer())
    private val events = ListSerializer(EventRow.serializer())
    private val refs = ListSerializer(RefRow.serializer())

    fun doneWhen(value: TodoDoneWhen): String =
        json.encodeToString(DoneWhenRow.serializer(), DoneWhenRow(value.text, value.checks.map(::checkRow)))

    fun doneWhen(text: String): TodoDoneWhen = json.decodeFromString(DoneWhenRow.serializer(), text)
        .let { row -> TodoDoneWhen(row.text, row.checks.map(::check)) }

    fun strings(value: List<String>): String = json.encodeToString(strings, value)

    fun strings(text: String): List<String> = json.decodeFromString(strings, text)

    fun notes(value: List<TodoNote>): String =
        json.encodeToString(notes, value.map { NoteRow(it.at.toEpochMilliseconds(), it.text) })

    fun notes(text: String): List<TodoNote> =
        json.decodeFromString(notes, text).map { TodoNote(Instant.fromEpochMilliseconds(it.at), it.text) }

    fun proof(value: TodoProof): String = json.encodeToString(
        ProofRow.serializer(),
        ProofRow(value.at.toEpochMilliseconds(), value.passed, value.text, value.urls),
    )

    fun proof(text: String): TodoProof = json.decodeFromString(ProofRow.serializer(), text)
        .let { TodoProof(Instant.fromEpochMilliseconds(it.at), it.passed, it.text, it.urls) }

    fun links(value: List<TodoLink>): String = json.encodeToString(
        links,
        value.map { LinkRow(it.kind, it.ref, it.number, it.sha, it.tag, it.at?.toEpochMilliseconds()) },
    )

    fun links(text: String): List<TodoLink> = json.decodeFromString(links, text).map {
        TodoLink(it.kind, it.ref, it.number, it.sha, it.tag, it.at?.let(Instant::fromEpochMilliseconds))
    }

    fun events(value: List<TodoHookEvent>): String =
        json.encodeToString(events, value.map { EventRow(it.at.toEpochMilliseconds(), it.kind, it.text) })

    fun events(text: String): List<TodoHookEvent> = json.decodeFromString(events, text)
        .map { TodoHookEvent(Instant.fromEpochMilliseconds(it.at), it.kind, it.text) }

    fun refs(value: List<TodoRef>): String =
        json.encodeToString(refs, value.map { RefRow(it.kind, it.id, it.url, it.title) })

    fun refs(text: String): List<TodoRef> =
        json.decodeFromString(refs, text).map { TodoRef(it.kind, it.id, it.url, it.title) }

    fun progress(value: TodoProgress): String =
        json.encodeToString(ProgressRow.serializer(), ProgressRow(value.done, value.items, value.notes))

    fun progress(text: String): TodoProgress =
        json.decodeFromString(ProgressRow.serializer(), text).let { TodoProgress(it.done, it.items, it.notes) }

    private fun checkRow(check: TodoCheck): CheckRow = when (check) {
        is TodoCheck.PrMerged -> CheckRow(check.type, repo = check.repo)
        is TodoCheck.PrClosed -> CheckRow(check.type, repo = check.repo, reason = check.reason)
        is TodoCheck.ReleaseContains -> CheckRow(check.type, repo = check.repo, of = check.of, commit = check.commit)
        is TodoCheck.CiGreen ->
            CheckRow(check.type, repo = check.repo, of = check.of, ref = check.ref, workflow = check.workflow)
        is TodoCheck.Command -> CheckRow(
            check.type,
            run = check.run,
            cwd = check.cwd,
            expectExit = check.expectExit,
            timeoutSeconds = check.timeoutSeconds,
        )
        is TodoCheck.ArtifactPublished -> CheckRow(check.type, id = check.id, minVersion = check.minVersion)
        is TodoCheck.WaitAnswered -> CheckRow(check.type, action = check.action)
        is TodoCheck.Attested -> CheckRow(check.type, what = check.what)
    }

    /** Reads a stored check; a type this version does not know is read as unverifiable, so it never passes alone. */
    private fun check(row: CheckRow): TodoCheck = when (row.type) {
        "pr_merged" -> TodoCheck.PrMerged(row.repo.orEmpty())
        "pr_closed" -> TodoCheck.PrClosed(row.repo.orEmpty(), row.reason.orEmpty())
        "release_contains" -> TodoCheck.ReleaseContains(row.repo.orEmpty(), row.of, row.commit)
        "ci_green" -> TodoCheck.CiGreen(row.repo.orEmpty(), row.of, row.ref, row.workflow)
        "command" -> TodoCheck.Command(row.run.orEmpty(), row.cwd, row.expectExit ?: 0, row.timeoutSeconds ?: 0)
        "artifact_published" -> TodoCheck.ArtifactPublished(row.id, row.minVersion)
        "wait_answered" -> TodoCheck.WaitAnswered(row.action)
        else -> TodoCheck.Attested(row.what.orEmpty())
    }
}
