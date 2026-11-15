// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/*
 * The tables of [AutomationsMigrations], as Exposed reads and writes them. The migrations create the schema; these
 * objects only name its columns. Every one is a tenant table, read and written in `inOrg` (the tenant role with the
 * organization set), each query filtered on `org_id` too. The JSON documents are text, written and read by the
 * module's own serializers.
 */

/** The waits (questions) of agents and members, and the timers and events that resolve by themselves. */
internal object AutomationsWaits : Table("automations_waits") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val kind = text("kind")
    val status = text("status")
    val sessionId = text("session_id").nullable()
    val runId = text("run_id").nullable()
    val assignee = text("assignee")
    val createdBy = text("created_by")
    val question = text("question")
    val answerAction = text("answer_action").nullable()
    val answerValues = text("answer_values").nullable()
    val answeredBy = text("answered_by").nullable()
    val answeredAt = instant("answered_at").nullable()
    val createdAt = instant("created_at")
    val resolvedAt = instant("resolved_at").nullable()
    val idempotencyKey = text("idempotency_key").nullable()
    val wakePending = bool("wake_pending").default(false)
    val resolver = text("resolver")
    val dueAt = instant("due_at").nullable()
    val timeoutAt = instant("timeout_at").nullable()
    val eventSource = text("event_source").nullable()
    val resumeMessage = text("resume_message").nullable()
    val eventSecretHash = text("event_secret_hash").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The sealed secrets of answered waits, one row per variable of a session. */
internal object AutomationsWaitSecrets : Table("automations_wait_secrets") {
    val orgId = uuid("org_id")
    val sessionId = text("session_id")
    val envName = text("env_name")
    val waitId = uuid("wait_id")
    val fieldId = text("field_id")
    val ownerId = text("owner_id")
    val oneTime = bool("one_time")
    val value = text("value")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(orgId, sessionId, envName)
}

/** An organization's automations. */
internal object AutomationsAutomations : Table("automations_automations") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val owner = text("owner")
    val name = text("name")
    val triggerKind = text("trigger_kind")
    val definition = text("definition")
    val enabled = bool("enabled")
    val version = integer("version")
    val secretGeneration = integer("secret_generation")
    val previousSecretValidUntil = instant("previous_secret_valid_until").nullable()
    val nextRunAt = instant("next_run_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val workflowId = text("workflow_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The runs of the automations. */
internal object AutomationsRuns : Table("automations_runs") {
    val id = uuid("id")
    val automationId = uuid("automation_id")
    val orgId = uuid("org_id")
    val origin = text("source")
    val status = text("status")
    val version = integer("version")
    // NOT NULL in the table; nullable here as the run's is, so that a run without one is refused by the table.
    val deliveryKey = text("delivery_key").nullable()
    val sessionId = text("session_id").nullable()
    val reason = text("reason").nullable()
    val problems = text("problems")
    val slotAt = instant("slot_at").nullable()
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val prompt = text("prompt").nullable()
    val followedAt = instant("followed_at").nullable()
    val workflowRunId = text("workflow_run_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** An automation's next cron slot, the one table read across organizations (through the router's functions). */
internal object AutomationsRoutes : Table("automations_routes") {
    val automationId = uuid("automation_id")
    val orgId = uuid("org_id")
    val nextRunAt = instant("next_run_at").nullable()

    override val primaryKey = PrimaryKey(automationId)
}
