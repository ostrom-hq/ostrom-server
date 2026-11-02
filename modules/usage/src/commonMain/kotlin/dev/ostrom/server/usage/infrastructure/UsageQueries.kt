// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.infrastructure

import dev.ostrom.server.usage.domain.UsageGroupBy

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

    /** The org's expired holds: `:org_id`, `:now`. */
    const val DROP_EXPIRED_HOLDS: String = "DELETE FROM usage_holds WHERE org_id = :org_id AND expires_at <= :now"

    /** A call's hold, replaced when it asks again: `:call_id`, `:org_id`, `:member_id`, `:micros`, `:expires_at`. */
    const val PUT_HOLD: String = "INSERT INTO usage_holds (call_id, org_id, member_id, micros, expires_at) " +
        "VALUES (:call_id, :org_id, :member_id, :micros, :expires_at) " +
        "ON CONFLICT (call_id) DO UPDATE SET micros = EXCLUDED.micros, expires_at = EXCLUDED.expires_at"

    /** The hold a recorded call settles: `:call_id`. */
    const val DROP_HOLD: String = "DELETE FROM usage_holds WHERE call_id = :call_id"

    /**
     * What is spent since `:since` and held by the other calls at `:now` (`:org_id`, `:call_id`), for the org if [org]
     * and for `:member_id` if [member]; 0 for a side not asked.
     *
     * @param org Whether the org's sums are read.
     * @param member Whether the member's sums are read.
     */
    fun holdTotals(org: Boolean, member: Boolean): String {
        val held = "SELECT SUM(micros) FROM usage_holds WHERE org_id = :org_id AND expires_at > :now " +
            "AND call_id <> :call_id"
        val orgSums = listOf(spentSum("FROM usage_events WHERE org_id = :org_id AND at >= :since"), held)
        val memberSums = listOf(
            spentSum("FROM usage_events WHERE org_id = :org_id AND at >= :since AND member_id = :member_id"),
            "$held AND member_id = :member_id",
        )
        fun columns(read: Boolean, sums: List<String>, prefix: String) =
            sums.zip(listOf("spent", "held")).joinToString(", ") { (sum, name) ->
                (if (read) "CAST(COALESCE(($sum), 0) AS BIGINT)" else "CAST(0 AS BIGINT)") + " AS ${prefix}_$name"
            }
        return "SELECT ${columns(org, orgSums, "org")}, ${columns(member, memberSums, "member")}"
    }

    private fun spentSum(from: String) = "SELECT SUM(cost_micros) $from"

    /** The account's spend since `:since`: `:org_id`, `:account_id`, `:since`. */
    const val ACCOUNT_SPENT: String =
        "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
            "WHERE org_id = :org_id AND account_id = :account_id AND at >= :since"

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
