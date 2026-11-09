// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.budgets.infrastructure

/** The SQL of the budgets that is built from parts. */
internal object BudgetsQueries {
    /** The rows that apply to one member's call: the org's, the default's, their own, its workflow's; two each. */
    const val LIMIT_ROWS: Int = 8

    /** The columns of a limit row. */
    const val LIMIT_COLUMNS: String = "scope, member_id, workflow_id, period, action, limit_micros, updated_at"

    /**
     * The rows that can apply to one member's call (`:org_id`, `:member_id`, `:workflow_id`, null without one): the
     * org's, the default member's, their own, and its workflow's; [LIMIT_ROWS] at most.
     */
    const val LIMITS: String = "SELECT $LIMIT_COLUMNS FROM budgets_limits " +
        "WHERE org_id = :org_id AND (member_id IS NULL OR member_id = :member_id) " +
        "AND (workflow_id IS NULL OR workflow_id = :workflow_id) ORDER BY period DESC LIMIT $LIMIT_ROWS"

    /**
     * Every row of the first `:limit` workflows by id that have a budget (`:org_id`): a workflow is listed whole,
     * both windows, or not at all.
     */
    const val WORKFLOW_LIMITS: String = "SELECT $LIMIT_COLUMNS FROM budgets_limits " +
        "WHERE org_id = :org_id AND scope = 'WORKFLOW' AND workflow_id IN (SELECT DISTINCT workflow_id " +
        "FROM budgets_limits WHERE org_id = :org_id AND scope = 'WORKFLOW' ORDER BY workflow_id LIMIT :limit) " +
        "ORDER BY workflow_id, period DESC"

    /** A deleted workflow's limits and alerts (`:org_id`, `:workflow_id`). */
    val REMOVE_WORKFLOW: List<String> = listOf(
        "DELETE FROM budgets_limits WHERE org_id = :org_id AND scope = 'WORKFLOW' AND workflow_id = :workflow_id",
        "DELETE FROM budgets_alerts WHERE org_id = :org_id AND scope = 'WORKFLOW' AND workflow_id = :workflow_id",
    )

    /**
     * The limits reached in their current period (`:day` for a daily limit, `:month` for a monthly one), from the
     * alerts the guard marked at 100 %, kept only while a limit of the same amount and action still applies: the
     * subject's own row, or for a member without limits of their own, the default member's. `:org_id`, `:day`,
     * `:month`, `:limit`, and `:member_id` if [member] (a member's own read: a workflow's limit is no member's).
     *
     * @param member Whether one member's only.
     */
    fun exceeded(member: Boolean): String =
        "SELECT a.scope, a.member_id, a.workflow_id, a.period, a.action, a.limit_micros, a.alerted_at " +
            "FROM budgets_alerts a " +
            "WHERE a.org_id = :org_id AND a.threshold = 100 " +
            "AND ((a.period = 'DAY' AND a.period_start = :day) OR (a.period = 'MONTH' AND a.period_start = :month))" +
            (if (member) " AND a.member_id = :member_id" else "") +
            " AND EXISTS (SELECT 1 FROM budgets_limits l WHERE l.org_id = a.org_id AND l.period = a.period " +
            "AND l.action = a.action AND l.limit_micros = a.limit_micros AND (" +
            "(l.scope = a.scope AND l.member_id IS NOT DISTINCT FROM a.member_id " +
            "AND l.workflow_id IS NOT DISTINCT FROM a.workflow_id) " +
            "OR (a.scope = 'MEMBER' AND l.scope = 'DEFAULT_MEMBER' AND NOT EXISTS (" +
            "SELECT 1 FROM budgets_limits o WHERE o.org_id = a.org_id AND o.scope = 'MEMBER' " +
            "AND o.member_id = a.member_id)))) " +
            "ORDER BY a.alerted_at, a.member_id NULLS FIRST, a.period DESC LIMIT :limit"
}
