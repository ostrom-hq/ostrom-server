// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The tables of [RunnersMigrations] version 16 as Exposed reads and writes them (TeamCode's
 * `CloudConversationTables`). The migrations create the schema; these objects only name its columns.
 */
internal object RunnersSessionEvents : Table("runners_session_events") {
    val sessionId = uuid("session_id")
    val seq = long("seq")
    val orgId = uuid("org_id")

    /** The execution that produced the event; 0 for events the server writes. */
    val epoch = long("epoch")

    /** The runner's own counter within [epoch]; null for server events. */
    val runnerSeq = long("runner_seq").nullable()
    val type = text("type")
    val at = instant("at")
    val data = text("data")

    /** The `tool.call` id of the subagent that produced the event; empty for the main agent. */
    val feed = text("feed")

    override val primaryKey = PrimaryKey(sessionId, seq)
}

/** The subagents of a session, kept as their events arrive. */
internal object RunnersSessionSubagents : Table("runners_session_subagents") {
    val sessionId = uuid("session_id")
    val id = text("id")
    val orgId = uuid("org_id")
    val type = text("type").nullable()
    val description = text("description")
    val state = text("state")
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at").nullable()
    val steps = integer("steps")
    val parentId = text("parent_id").nullable()
    val taskId = text("task_id").nullable()
    val resultError = bool("result_error")

    override val primaryKey = PrimaryKey(sessionId, id)
}

/** Per-session transcript cursors, kept apart from the events so the retention purge never resets them. */
internal object RunnersSessionTranscripts : Table("runners_session_transcripts") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")
    val lastSeq = long("last_seq")
    val runnerEpoch = long("runner_epoch")
    val runnerSeq = long("runner_seq")

    override val primaryKey = PrimaryKey(sessionId)
}

/** Messages, interrupts, answers and changes with their acknowledgement. The text is never logged. */
internal object RunnersSessionInputs : Table("runners_session_inputs") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")
    val kind = text("kind")
    val status = text("status")
    val authorId = text("author_id")
    val credentialId = text("credential_id").nullable()
    val text = text("text").nullable()
    val wake = bool("wake")

    /** Waiting to wake a session that slept when the message arrived. */
    val wakePending = bool("wake_pending")
    val clientKey = text("client_key").nullable()
    val requestDigest = text("request_digest").nullable()
    val createdAt = instant("created_at")
    val deliveredAt = instant("delivered_at").nullable()
    val deliveredEpoch = long("delivered_epoch").nullable()
    val attempts = integer("attempts")
    val consumedAt = instant("consumed_at").nullable()
    val finishedAt = instant("finished_at").nullable()
    val reason = text("reason").nullable()
    val eventSeq = long("event_seq").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The columns of `runners_sessions` the conversation reads under its lock and writes (the rest is sqlx4k's). */
internal object RunnersSessionsTable : Table("runners_sessions") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val state = text("state")
    val desiredState = text("desired_state")
    val epoch = long("epoch")
    val generation = long("generation")
    val updatedAt = instant("updated_at")
    val model = text("model").nullable()
    val effort = text("effort").nullable()
    val bypassBy = text("bypass_by").nullable()
    val bypassCredentialId = text("bypass_credential_id").nullable()
    val bypassAt = instant("bypass_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
