// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets.infrastructure

/** The SQL of the budgets that is built from parts. */
internal object BudgetsQueries {
    /**
     * The limits reached in their current period (`:day` for a daily limit, `:month` for a monthly one), from the
     * alerts the guard marked at 100 %, kept only while a limit of the same amount and action still applies: the
     * subject's own row, or for a member without limits of their own, the default member's. `:org_id`, `:day`,
     * `:month`, `:limit`, and `:member_id` if [member].
     *
     * @param member Whether one member's only.
     */
    fun exceeded(member: Boolean): String =
        "SELECT a.scope, a.member_id, a.period, a.action, a.limit_micros, a.alerted_at FROM budgets_alerts a " +
            "WHERE a.org_id = :org_id AND a.threshold = 100 " +
            "AND ((a.period = 'DAY' AND a.period_start = :day) OR (a.period = 'MONTH' AND a.period_start = :month))" +
            (if (member) " AND a.member_id = :member_id" else "") +
            " AND EXISTS (SELECT 1 FROM budgets_limits l WHERE l.org_id = a.org_id AND l.period = a.period " +
            "AND l.action = a.action AND l.limit_micros = a.limit_micros AND (" +
            "(l.scope = a.scope AND l.member_id IS NOT DISTINCT FROM a.member_id) " +
            "OR (a.scope = 'MEMBER' AND l.scope = 'DEFAULT_MEMBER' AND NOT EXISTS (" +
            "SELECT 1 FROM budgets_limits o WHERE o.org_id = a.org_id AND o.scope = 'MEMBER' " +
            "AND o.member_id = a.member_id)))) " +
            "ORDER BY a.alerted_at, a.member_id NULLS FIRST, a.period DESC LIMIT :limit"
}
