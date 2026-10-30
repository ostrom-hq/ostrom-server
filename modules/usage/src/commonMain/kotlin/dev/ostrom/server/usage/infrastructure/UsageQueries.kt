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

    /** The account's spend since `:since`: `:org_id`, `:account_id`, `:since`. */
    const val ACCOUNT_SPENT: String =
        "SELECT CAST(COALESCE(SUM(cost_micros), 0) AS BIGINT) AS spent FROM usage_events " +
            "WHERE org_id = :org_id AND account_id = :account_id AND at >= :since"

    /** One batch of the retention purge: `:before`, `:limit`. */
    const val PURGE: String =
        "DELETE FROM usage_events WHERE id IN (SELECT id FROM usage_events WHERE at < :before LIMIT :limit)"

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
