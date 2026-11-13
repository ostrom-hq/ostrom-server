// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.usage.domain.UsageGroupBy

/**
 * The SQL of the ledger's reads and purge, apart from the repository so that the query-plan test runs the very same
 * text under `EXPLAIN ANALYZE` (UsageQueryPlansTest).
 */
internal object UsageQueries {
    /** Where a page of the ledger starts. */
    enum class Cursor(val sql: String) {
        /** The newest events. */
        NONE(""),

        /** Before an instant (an older cursor without id). */
        AT(" AND at < :before_at"),

        /** Before an event: its instant, then its id. */
        AT_AND_ID(" AND (at, id) < (:before_at, :before_id)"),
    }

    /** The lock that admits an org's calls one at a time: `:key`. */
    const val LOCK_HOLDS: String =
        "SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS l"

    /**
     * The org's holds past their retention: `:org_id`, `:retained_since`. Until then an expired hold, whose call was
     * never recorded (its replica lost mid-call), stays charged.
     */
    const val DROP_RETIRED_HOLDS: String =
        "DELETE FROM usage_holds WHERE org_id = :org_id AND held_at < :retained_since"

    /**
     * A call's hold, replaced when it asks again (its first time kept): `:call_id`, `:org_id`, `:member_id`,
     * `:workflow_id`, `:micros`, `:expires_at`, `:now`.
     */
    const val PUT_HOLD: String =
        "INSERT INTO usage_holds (call_id, org_id, member_id, workflow_id, micros, expires_at, held_at) " +
        "VALUES (:call_id, :org_id, :member_id, :workflow_id, :micros, :expires_at, :now) " +
        "ON CONFLICT (call_id) DO UPDATE SET micros = EXCLUDED.micros, expires_at = EXCLUDED.expires_at"

    /** The hold a recorded call settles: `:call_id`. */
    const val DROP_HOLD: String = "DELETE FROM usage_holds WHERE call_id = :call_id"

    /**
     * What is spent since `:since` (the month) and since `:day_since` (the day), and held by the other calls at `:now`
     * (`:org_id`, `:call_id`), for the org, for `:member_id` and for `:workflow_id`; 0 for a sum not asked. The holds
     * are read for a side with a monthly or a daily limit: a call in flight counts against both. An expired hold, the
     * uncertain cost of a call never recorded, is spent at the time it was held (TeamCode's « Failure behavior »).
     *
     * @param org Whether the org's monthly sum is read.
     * @param member Whether the member's monthly sum is read.
     * @param orgDay Whether the org's daily sum is read.
     * @param memberDay Whether the member's daily sum is read.
     * @param workflow Whether the workflow's monthly sum is read.
     * @param workflowDay Whether the workflow's daily sum is read.
     */
    @Suppress("LongParameterList") // One flag per sum: the statement reads only those a limit needs.
    fun holdTotals(
        org: Boolean,
        member: Boolean,
        orgDay: Boolean,
        memberDay: Boolean,
        workflow: Boolean = false,
        workflowDay: Boolean = false,
    ): String {
        val held = "SELECT SUM(micros) FROM usage_holds WHERE org_id = :org_id AND expires_at > :now " +
            "AND call_id <> :call_id"
        val mine = " AND member_id = :member_id"
        val its = " AND workflow_id = :workflow_id"
        val columns = listOf(
            Triple("org_spent", org, spent(":since", "")),
            Triple("org_held", org || orgDay, held),
            Triple("member_spent", member, spent(":since", mine)),
            Triple("member_held", member || memberDay, "$held$mine"),
            Triple("org_day_spent", orgDay, spent(":day_since", "")),
            Triple("member_day_spent", memberDay, spent(":day_since", mine)),
            Triple("workflow_spent", workflow, spent(":since", its)),
            Triple("workflow_held", workflow || workflowDay, "$held$its"),
            Triple("workflow_day_spent", workflowDay, spent(":day_since", its)),
        )
        return "SELECT " + columns.joinToString(", ") { (name, read, sum) ->
            (if (read) "CAST($sum AS BIGINT)" else "CAST(0 AS BIGINT)") + " AS $name"
        }
    }

    /** What is recorded since [since], plus what the calls never recorded held then ([filter] on both). */
    private fun spent(since: String, filter: String) =
        "COALESCE((SELECT SUM(cost_micros) FROM usage_events WHERE org_id = :org_id AND at >= $since$filter), 0) + " +
            "COALESCE((SELECT SUM(micros) FROM usage_holds WHERE org_id = :org_id AND held_at >= $since " +
            "AND expires_at <= :now$filter), 0)"

    /** The account's spend since `:since`: `:org_id`, `:account_id`, `:since`. */
    const val ACCOUNT_SPENT: String =
        "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
            "WHERE org_id = :org_id AND account_id = :account_id AND at >= :since"

    /** A workflow's spend since `:since`: `:org_id`, `:workflow_id`, `:since` (index `usage_events_workflow`). */
    const val WORKFLOW_SPENT: String =
        "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
            "WHERE org_id = :org_id AND workflow_id = :workflow_id AND at >= :since"

    /**
     * What a launch spent in a window, NULL for nothing priced: `:org_id`, `:launch_id`, `:from`, `:to`. Half open, so
     * that a call on the boundary of two segments counts once; the window bounds the read to the org's index on `at`.
     */
    const val LAUNCH_SPENT: String =
        "SELECT CAST(SUM(cost_micros) AS BIGINT) AS spent FROM usage_events " +
            "WHERE org_id = :org_id AND at >= :from AND at < :to AND launch_id = :launch_id AND cost_micros > 0"

    /**
     * The org's or a member's spend since `:since`: `:org_id`, `:since`, and `:member_id` if [member].
     *
     * @param member Whether one member's.
     */
    fun spent(member: Boolean): String = "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent " +
        "FROM usage_events WHERE org_id = :org_id AND at >= :since" + memberFilter(member)

    /**
     * Totals by [groupBy]: `:org_id`, `:since`, `:limit`, `:until` if [until], `:member_id` if [member].
     *
     * @param groupBy The grouping.
     * @param until Whether the window has an end.
     * @param member Whether one member's.
     */
    fun summary(groupBy: UsageGroupBy, until: Boolean, member: Boolean): String {
        val end = if (until) " AND at < :until" else ""
        val models = if (groupBy == UsageGroupBy.MODEL) " AND kind = 'MODEL'" else ""
        return "SELECT ${groupBy.keyExpression()} AS group_key, COUNT(*) AS events, " +
            "CAST(COALESCE(SUM(input_tokens), 0) AS BIGINT) AS input_tokens, " +
            "CAST(COALESCE(SUM(output_tokens), 0) AS BIGINT) AS output_tokens, " +
            "CAST(COALESCE(SUM(cache_read_tokens), 0) AS BIGINT) AS cache_read_tokens, " +
            "CAST(COALESCE(SUM(cache_write_tokens), 0) AS BIGINT) AS cache_write_tokens, " +
            "CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS total_cost " +
            "FROM usage_events WHERE org_id = :org_id AND at >= :since$end${memberFilter(member)}$models " +
            "GROUP BY 1 ORDER BY total_cost DESC, group_key NULLS LAST LIMIT :limit"
    }

    /**
     * A page of events, newest first: `:org_id`, `:limit`, `:member_id` if [member], and the [cursor]'s parameters.
     *
     * @param member Whether one member's.
     * @param cursor Where the page starts.
     */
    fun list(member: Boolean, cursor: Cursor): String =
        "SELECT $COLUMNS FROM usage_events WHERE org_id = :org_id${memberFilter(member)}${cursor.sql} " +
            "ORDER BY at DESC, id DESC LIMIT :limit"

    private fun memberFilter(member: Boolean) = if (member) " AND member_id = :member_id" else ""

    private fun UsageGroupBy.keyExpression(): String = when (this) {
        UsageGroupBy.MEMBER -> "CAST(member_id AS TEXT)"
        UsageGroupBy.KIND -> "kind"
        UsageGroupBy.MODEL -> "name"
        UsageGroupBy.PROVIDER -> "provider"
        UsageGroupBy.ACCOUNT -> "CAST(account_id AS TEXT)"
    }
}
