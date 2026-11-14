// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/** The setting that opened the retention policies of version 2, which version 4 drops: kept for version 2's SQL. */
private const val RETENTION_SETTING_V2: String = "ostrom.usage_retention"

/** The retention purge, the one operation across organizations: a function owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf("usage_purge(TIMESTAMPTZ, INTEGER)")

/**
 * Version 4: the retention policies, which any transaction could open by setting the retention setting (SELECT and
 * DELETE on every organization's events, RLS audit R4), give way to a narrow `SECURITY DEFINER` function owned by
 * `ostrom_router` (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in its `search_path` and
 * executable by the serving role only: it deletes one batch of old events, the query it replaces, unchanged.
 */
private val RETENTION_THROUGH_THE_ROUTER_SQL: String =
    """
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    DROP POLICY usage_events_retention_read ON usage_events;
    DROP POLICY usage_events_retention ON usage_events;
    CREATE POLICY usage_events_router_read ON usage_events FOR SELECT TO ostrom_router USING (true);
    CREATE POLICY usage_events_router_purge ON usage_events FOR DELETE TO ostrom_router USING (true);
    GRANT SELECT, DELETE ON usage_events TO ostrom_router;
    CREATE FUNCTION usage_purge(p_before TIMESTAMPTZ, p_limit INTEGER) RETURNS BIGINT
        LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH gone AS (
            DELETE FROM usage_events WHERE id IN (SELECT e.id FROM usage_events e WHERE e.at < p_before LIMIT p_limit)
            RETURNING 1
        ) SELECT COUNT(*) FROM gone;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/** The daily totals of the whole ledger, as version 7 writes them for the calls recorded before it. */
internal const val DAILY_TOTALS_OF_THE_LEDGER: String =
    "INSERT INTO usage_daily (org_id, day, member_id, session_id, launch_id, cost_micros) " +
        "SELECT org_id, CAST(at AT TIME ZONE 'UTC' AS DATE), member_id, session_id, launch_id, SUM(cost_micros) " +
        "FROM usage_events WHERE cost_micros > 0 GROUP BY 1, 2, 3, 4, 5"

/** The key of a daily total, in the order each statement locks the totals (see the migration's KDoc on deadlocks). */
private const val DAILY_KEY = "org_id, day, member_id, session_id, launch_id"

/** The calls of a transition table that cost something, by [DAILY_KEY], their cost signed. */
private fun signedCalls(calls: String, sign: String) =
    "SELECT c.org_id, CAST(c.at AT TIME ZONE 'UTC' AS DATE) AS day, c.member_id, c.session_id, c.launch_id, " +
        "${sign}c.cost_micros AS cost_micros FROM $calls c WHERE c.cost_micros > 0"

/**
 * Adds the calls of [added] to their totals and takes those of [removed] away, in one upsert through the key's
 * unique index, in key order; then drops the totals a removal brought to zero, among those of its days.
 */
private fun applyCalls(added: String?, removed: String?): String {
    val signed = listOfNotNull(added?.let { signedCalls(it, "") }, removed?.let { signedCalls(it, "-") })
    val upsert = "INSERT INTO usage_daily AS d ($DAILY_KEY, cost_micros) SELECT $DAILY_KEY, SUM(cost_micros) " +
        "FROM (${signed.joinToString(" UNION ALL ")}) s GROUP BY 1, 2, 3, 4, 5 HAVING SUM(cost_micros) <> 0 " +
        "ORDER BY 1, 2, 3, 4, 5 ON CONFLICT ($DAILY_KEY) " +
        "DO UPDATE SET cost_micros = d.cost_micros + EXCLUDED.cost_micros;"
    val drop = removed?.let {
        " DELETE FROM usage_daily d WHERE d.cost_micros <= 0 AND (d.org_id, d.day) IN " +
            "(SELECT c.org_id, CAST(c.at AT TIME ZONE 'UTC' AS DATE) FROM $it c);"
    }.orEmpty()
    return upsert + drop
}

/** A trigger function of the totals, run as `ostrom_router` whoever writes the ledger. */
private fun dailyFunction(name: String, body: String) = """
    CREATE FUNCTION $name() RETURNS trigger
        LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
    BEGIN
        $body
        RETURN NULL;
    END $$;
""".trimIndent()

/** The trigger functions of version 7, handed to `ostrom_router`. */
private val DAILY_FUNCTIONS = listOf("usage_daily_add", "usage_daily_remove", "usage_daily_change", "usage_daily_clear")

/**
 * Version 7: the daily totals of the ledger, by organization, UTC day, member, session and launch, for the calls that
 * cost something (`cost_micros > 0`), which the spending report reads instead of adding up a month of calls
 * (ReportsQueries.spending). Statement triggers keep them in the transaction of every write of the ledger, whatever
 * the role that writes: an insert adds its calls, a delete (the retention purge among others) takes them away, an
 * update does both, a truncate empties them. Their functions run as `ostrom_router` (`SECURITY DEFINER`, the module's
 * pattern since version 4), the only role that writes the totals: an organization reads its own and changes none.
 * Every write, an update's old and new keys together, reaches the totals in one upsert in key order through their
 * unique index, so two writers of one statement each never deadlock; calls of one session and day wait for each
 * other's commit on their total. Two transactions that each write two keys in opposite statement orders could; no
 * writer does today (each records or settles one call per transaction), and PostgreSQL would cancel one with 40P01.
 * The ledger is locked against writes while the totals of the calls recorded before are written, by the
 * router, which reads every organization's calls inside its role: writes wait for one pass over the ledger, short on
 * the ledgers of today, and no call is counted twice or missed.
 */
private val DAILY_TOTALS_SQL: String = listOf(
    """
    LOCK TABLE usage_events IN SHARE ROW EXCLUSIVE MODE;
    CREATE TABLE usage_daily (
        org_id UUID NOT NULL,
        day DATE NOT NULL,
        member_id UUID,
        session_id TEXT,
        launch_id TEXT,
        cost_micros BIGINT NOT NULL
    );
    CREATE UNIQUE INDEX usage_daily_key ON usage_daily ($DAILY_KEY) NULLS NOT DISTINCT;
    ALTER TABLE usage_daily ENABLE ROW LEVEL SECURITY;
    ALTER TABLE usage_daily FORCE ROW LEVEL SECURITY;
    CREATE POLICY usage_daily_tenant ON usage_daily FOR SELECT
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
    GRANT SELECT ON usage_daily TO ostrom_tenant;
    CREATE POLICY usage_daily_router ON usage_daily FOR ALL TO ostrom_router
        USING (current_user = 'ostrom_router') WITH CHECK (current_user = 'ostrom_router');
    GRANT SELECT, INSERT, UPDATE, DELETE ON usage_daily TO ostrom_router;
    """.trimIndent(),
    dailyFunction("usage_daily_add", applyCalls("usage_events_added", null)),
    dailyFunction("usage_daily_remove", applyCalls(null, "usage_events_removed")),
    dailyFunction("usage_daily_change", applyCalls("usage_events_added", "usage_events_removed")),
    dailyFunction("usage_daily_clear", "DELETE FROM usage_daily;"),
    "GRANT CREATE ON SCHEMA public TO ostrom_router;",
    DAILY_FUNCTIONS.joinToString("\n") {
        "REVOKE EXECUTE ON FUNCTION $it() FROM PUBLIC; ALTER FUNCTION $it() OWNER TO ostrom_router;"
    },
    "REVOKE CREATE ON SCHEMA public FROM ostrom_router;",
    """
    CREATE TRIGGER usage_daily_add AFTER INSERT ON usage_events
        REFERENCING NEW TABLE AS usage_events_added FOR EACH STATEMENT EXECUTE FUNCTION usage_daily_add();
    CREATE TRIGGER usage_daily_remove AFTER DELETE ON usage_events
        REFERENCING OLD TABLE AS usage_events_removed FOR EACH STATEMENT EXECUTE FUNCTION usage_daily_remove();
    CREATE TRIGGER usage_daily_change AFTER UPDATE ON usage_events
        REFERENCING OLD TABLE AS usage_events_removed NEW TABLE AS usage_events_added
        FOR EACH STATEMENT EXECUTE FUNCTION usage_daily_change();
    CREATE TRIGGER usage_daily_clear AFTER TRUNCATE ON usage_events
        FOR EACH STATEMENT EXECUTE FUNCTION usage_daily_clear();
    SET LOCAL ROLE ostrom_router;
    $DAILY_TOTALS_OF_THE_LEDGER;
    RESET ROLE
    """.trimIndent(),
).joinToString("\n")

/** The spend of a day of the whole ledger, as version 10 writes it for the calls recorded before it. */
internal const val SPEND_DAYS_OF_THE_LEDGER: String =
    "INSERT INTO usage_spend_days (org_id, day, member_id, workflow_id, cost_micros) " +
        "SELECT org_id, CAST(at AT TIME ZONE 'UTC' AS DATE), member_id, workflow_id, SUM(cost_micros) " +
        "FROM usage_events WHERE cost_micros <> 0 GROUP BY 1, 2, 3, 4 HAVING SUM(cost_micros) <> 0"

/** The key of a day's spend, in the order each statement locks them. */
private const val SPEND_DAY_KEY = "org_id, day, member_id, workflow_id"

/** The calls of a transition table that cost something, by [SPEND_DAY_KEY], their cost signed. */
private fun signedSpend(calls: String, sign: String) =
    "SELECT c.org_id, CAST(c.at AT TIME ZONE 'UTC' AS DATE) AS day, c.member_id, c.workflow_id, " +
        "${sign}c.cost_micros AS cost_micros FROM $calls c WHERE c.cost_micros <> 0"

/** As [applyCalls], on the spend of the days: a day's spend that comes back to zero is dropped. */
private fun applySpend(added: String?, removed: String?): String {
    val signed = listOfNotNull(added?.let { signedSpend(it, "") }, removed?.let { signedSpend(it, "-") })
    val upsert = "INSERT INTO usage_spend_days AS d ($SPEND_DAY_KEY, cost_micros) " +
        "SELECT $SPEND_DAY_KEY, SUM(cost_micros) FROM (${signed.joinToString(" UNION ALL ")}) s " +
        "GROUP BY 1, 2, 3, 4 HAVING SUM(cost_micros) <> 0 ORDER BY 1, 2, 3, 4 ON CONFLICT ($SPEND_DAY_KEY) " +
        "DO UPDATE SET cost_micros = d.cost_micros + EXCLUDED.cost_micros;"
    val drop = removed?.let {
        " DELETE FROM usage_spend_days d WHERE d.cost_micros = 0 AND (d.org_id, d.day) IN " +
            "(SELECT c.org_id, CAST(c.at AT TIME ZONE 'UTC' AS DATE) FROM $it c);"
    }.orEmpty()
    return upsert + drop
}

/** The trigger functions of version 10, handed to `ostrom_router`. */
private val SPEND_DAY_FUNCTIONS =
    listOf("usage_spend_days_add", "usage_spend_days_remove", "usage_spend_days_change", "usage_spend_days_clear")

/**
 * Version 10, TeamCode #163: the spend of each UTC day, by organization, member and workflow, which a budget's
 * admission reads instead of adding up the month's calls while it holds the organization's lock (UsageQueries
 * .holdTotals): a month is at most 31 days of an organization's members and workflows, whatever its calls. Kept as
 * version 7 keeps `usage_daily` (statement triggers run as `ostrom_router`, one upsert in key order, the ledger locked
 * while the router writes the spend of the calls recorded before), and exact: every call whose cost is not zero
 * counts, as in the sums it replaces.
 */
private val SPEND_DAYS_SQL: String = listOf(
    """
    LOCK TABLE usage_events IN SHARE ROW EXCLUSIVE MODE;
    CREATE TABLE usage_spend_days (
        org_id UUID NOT NULL,
        day DATE NOT NULL,
        member_id UUID,
        workflow_id TEXT,
        cost_micros BIGINT NOT NULL
    );
    CREATE UNIQUE INDEX usage_spend_days_key ON usage_spend_days ($SPEND_DAY_KEY) INCLUDE (cost_micros)
        NULLS NOT DISTINCT;
    CREATE INDEX usage_spend_days_member ON usage_spend_days (org_id, member_id, day) INCLUDE (cost_micros);
    CREATE INDEX usage_spend_days_workflow ON usage_spend_days (org_id, workflow_id, day) INCLUDE (cost_micros)
        WHERE workflow_id IS NOT NULL;
    ALTER TABLE usage_spend_days ENABLE ROW LEVEL SECURITY;
    ALTER TABLE usage_spend_days FORCE ROW LEVEL SECURITY;
    CREATE POLICY usage_spend_days_tenant ON usage_spend_days FOR SELECT
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
    GRANT SELECT ON usage_spend_days TO ostrom_tenant;
    CREATE POLICY usage_spend_days_router ON usage_spend_days FOR ALL TO ostrom_router
        USING (current_user = 'ostrom_router') WITH CHECK (current_user = 'ostrom_router');
    GRANT SELECT, INSERT, UPDATE, DELETE ON usage_spend_days TO ostrom_router;
    """.trimIndent(),
    dailyFunction("usage_spend_days_add", applySpend("usage_events_added", null)),
    dailyFunction("usage_spend_days_remove", applySpend(null, "usage_events_removed")),
    dailyFunction("usage_spend_days_change", applySpend("usage_events_added", "usage_events_removed")),
    dailyFunction("usage_spend_days_clear", "DELETE FROM usage_spend_days;"),
    "GRANT CREATE ON SCHEMA public TO ostrom_router;",
    SPEND_DAY_FUNCTIONS.joinToString("\n") {
        "REVOKE EXECUTE ON FUNCTION $it() FROM PUBLIC; ALTER FUNCTION $it() OWNER TO ostrom_router;"
    },
    "REVOKE CREATE ON SCHEMA public FROM ostrom_router;",
    """
    CREATE TRIGGER usage_spend_days_add AFTER INSERT ON usage_events
        REFERENCING NEW TABLE AS usage_events_added FOR EACH STATEMENT EXECUTE FUNCTION usage_spend_days_add();
    CREATE TRIGGER usage_spend_days_remove AFTER DELETE ON usage_events
        REFERENCING OLD TABLE AS usage_events_removed FOR EACH STATEMENT EXECUTE FUNCTION usage_spend_days_remove();
    CREATE TRIGGER usage_spend_days_change AFTER UPDATE ON usage_events
        REFERENCING OLD TABLE AS usage_events_removed NEW TABLE AS usage_events_added
        FOR EACH STATEMENT EXECUTE FUNCTION usage_spend_days_change();
    CREATE TRIGGER usage_spend_days_clear AFTER TRUNCATE ON usage_events
        FOR EACH STATEMENT EXECUTE FUNCTION usage_spend_days_clear();
    SET LOCAL ROLE ostrom_router;
    $SPEND_DAYS_OF_THE_LEDGER;
    RESET ROLE
    """.trimIndent(),
).joinToString("\n")

/**
 * The schema of the usage module; its tables start with `usage_`. The spend indexes include `cost_micros` so that the
 * budget sums are index-only scans; `usage_events_at` serves the retention purge. `usage_holds` keeps the budget held
 * by the model calls in flight.
 */
val UsageMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_usage_events",
        sql = """
            CREATE TABLE usage_events (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                provider TEXT,
                account_id UUID,
                session_id TEXT,
                input_tokens BIGINT NOT NULL,
                output_tokens BIGINT NOT NULL,
                cache_read_tokens BIGINT NOT NULL,
                cache_write_tokens BIGINT NOT NULL,
                cost_micros BIGINT NOT NULL,
                duration_ms BIGINT NOT NULL,
                outcome TEXT NOT NULL,
                at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX usage_events_org_at ON usage_events (org_id, at, id) INCLUDE (cost_micros);
            CREATE INDEX usage_events_member_at ON usage_events (org_id, member_id, at) INCLUDE (cost_micros);
            CREATE INDEX usage_events_account_at ON usage_events (account_id, at) INCLUDE (cost_micros) WHERE account_id IS NOT NULL;
            CREATE INDEX usage_events_at ON usage_events (at)
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "confine_usage_events_to_their_org",
        sql = listOf(
            TENANT_ROLE_SQL,
            tenantTableSql("usage_events"),
            // Retention deletes old events of every org, and nothing else (UsageRepositoryImpl.purge); a DELETE with a
            // WHERE also needs the rows to pass a SELECT policy.
            "CREATE POLICY usage_events_retention_read ON usage_events FOR SELECT " +
                "USING (current_setting('$RETENTION_SETTING_V2', true) = 'on');",
            "CREATE POLICY usage_events_retention ON usage_events FOR DELETE " +
                "USING (current_setting('$RETENTION_SETTING_V2', true) = 'on');",
        ).joinToString("\n"),
    ),
    SchemaMigration(
        version = 3,
        name = "usage_events_name_the_agent_launch",
        // The launch whose agent token sent the event (server#108), null for a person's own credential.
        sql = "ALTER TABLE usage_events ADD COLUMN launch_id TEXT",
    ),
    SchemaMigration(version = 4, name = "retention_through_the_router", sql = RETENTION_THROUGH_THE_ROUTER_SQL),
    SchemaMigration(
        version = 5,
        name = "create_usage_holds",
        // What the model calls in flight may still cost (UsageRepository.holdWithin): each hold lives until its call is
        // recorded (settle), or until it expires when a replica is lost before.
        sql = listOf(
            """
            CREATE TABLE usage_holds (
                call_id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                member_id UUID NOT NULL,
                micros BIGINT NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX usage_holds_org_member ON usage_holds (org_id, member_id)
                INCLUDE (micros, expires_at, call_id);
            """.trimIndent(),
            tenantTableSql("usage_holds"),
        ).joinToString("\n"),
    ),
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 6,
        name = "usage_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY usage_events_router_read ON usage_events USING (current_user = 'ostrom_router');
            ALTER POLICY usage_events_router_purge ON usage_events USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    SchemaMigration(version = 7, name = "usage_daily_totals", sql = DAILY_TOTALS_SQL),
    // A model call of a launch that plays a workflow names it, and so does its hold: the workflow's budgets sum them.
    // The index reads a workflow's spend of a day or a month without the org's other calls.
    SchemaMigration(
        version = 8,
        name = "usage_names_the_workflow",
        sql = """
            ALTER TABLE usage_events ADD COLUMN workflow_id TEXT;
            CREATE INDEX usage_events_workflow ON usage_events (org_id, workflow_id, at) INCLUDE (cost_micros)
                WHERE workflow_id IS NOT NULL;
            ALTER TABLE usage_holds ADD COLUMN workflow_id TEXT;
        """.trimIndent(),
    ),
    // TeamCode's « Failure behavior »: an expired hold, whose call a lost replica never recorded, stays charged at the
    // time it was held, and is kept 90 days. The index reads an org's month or day of them.
    SchemaMigration(
        version = 9,
        name = "usage_uncertain_holds_stay_charged",
        sql = """
            ALTER TABLE usage_holds ADD COLUMN held_at TIMESTAMPTZ NOT NULL DEFAULT now();
            CREATE INDEX usage_holds_org_held ON usage_holds (org_id, held_at)
                INCLUDE (member_id, workflow_id, micros, expires_at);
        """.trimIndent(),
    ),
    SchemaMigration(version = 10, name = "usage_spend_days", sql = SPEND_DAYS_SQL),
)
