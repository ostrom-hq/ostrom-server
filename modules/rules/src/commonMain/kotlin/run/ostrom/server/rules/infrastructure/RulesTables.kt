// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/*
 * The tables of [RulesMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
 * only name its columns. Every one is a tenant table, read and written by the serving role with the organization set
 * (`withOrganization`), each query filtered on `org_id` too. Lists are the JSON text the contract writes.
 */

/** An organization's rules. */
internal object RulesRules : Table("rules_rules") {
    val orgId = uuid("org_id")
    val id = text("id")
    val statement = text("statement")
    val output = text("output")
    val group = text("rule_group")
    val scope = text("scope")
    val sources = text("sources")
    val why = text("why").nullable()
    val strength = text("strength")
    val confidence = double("confidence")
    val status = text("status")
    val foundBy = text("found_by")
    val foundAt = instant("found_at")
    val reviewBy = text("review_by").nullable()
    val decidedBy = text("decided_by").nullable()
    val decidedAt = instant("decided_at").nullable()
    val origin = text("origin").nullable()
    val gate = text("gate").nullable()
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId, id)
}

/** The decisions on an organization's rules and policies, signed later. */
internal object RulesDecisions : Table("rules_decisions") {
    val orgId = uuid("org_id")
    val id = text("id")
    val decidedAt = instant("decided_at")
    val decidedBy = text("decided_by")
    val subjectKind = text("subject_kind")
    val subjectId = text("subject_id")
    val subjectLabel = text("subject_label").nullable()
    val action = text("action")
    val beforeDoc = text("before_doc").nullable()
    val afterDoc = text("after_doc").nullable()
    val ruleDigest = text("rule_digest").nullable()
    val signed = bool("signed")

    override val primaryKey = PrimaryKey(orgId, id)
}

/** An organization's gate policy: who may approve their own gate. */
internal object RulesGatePolicy : Table("rules_gate_policy") {
    val orgId = uuid("org_id")
    val selfApproval = text("self_approval")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** The last run of each kind of analysis of an organization; a kind never started has an empty row. */
internal object RulesAnalyses : Table("rules_analyses") {
    val orgId = uuid("org_id")
    val kind = text("kind")
    val runId = text("run_id").nullable()
    val phase = text("phase")
    val progress = double("progress")
    val failureStage = text("failure_stage").nullable()
    val failureReason = text("failure_reason").nullable()
    val failedAt = instant("failed_at").nullable()
    val items = text("items")
    val startedBy = text("started_by").nullable()
    val startedAt = instant("started_at").nullable()
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId, kind)
}
