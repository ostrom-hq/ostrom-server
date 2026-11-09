// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

/**
 * The role every organization-scoped query runs as: not a superuser, owner of nothing, so the row-level security
 * policies always apply to it (SECURITY-AUDIT S2-01). The same role and setting as the identity module's `inOrg` and
 * the relay modules' `inTenant`, so all converge on the kernel's entry point when it lands.
 */
internal const val TENANT_ROLE: String = "ostrom_tenant"

/** The transaction-local setting the policies read: the organization of the query. */
internal const val TENANT_SETTING: String = "ostrom.org_id"

/**
 * Runs [block] in a transaction confined to one organization: the transaction switches to [TENANT_ROLE] and sets
 * [TENANT_SETTING]; a tenant table then only shows and accepts the rows of [orgId], whatever the query says.
 *
 * @param orgId The organization.
 * @param block The queries; a failure inside rolls the transaction back.
 *
 * @return What [block] returns.
 */
internal suspend fun <T> Driver.inTenant(orgId: Uuid, block: suspend Transaction.() -> T): T = transaction {
    execute("SET LOCAL ROLE $TENANT_ROLE").getOrThrow()
    execute(
        Statement.create("SELECT set_config('$TENANT_SETTING', :org, true)").bind("org", orgId.toString()),
    ).getOrThrow()
    block()
}

/** One query confined to [orgId] (see [inTenant]). */
internal suspend fun Driver.fetchInOrg(orgId: Uuid, statement: Statement): Result<ResultSet> =
    inTenant(orgId) { Result.success(fetchAll(statement).getOrThrow()) }

/**
 * The SQL that creates [TENANT_ROLE] (tolerating one created by another module, database or test) and grants it to
 * the migrating user. Roles are global to the PostgreSQL cluster.
 */
internal val TENANT_ROLE_SQL: String = """
    DO $$
    BEGIN
        CREATE ROLE $TENANT_ROLE NOLOGIN;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN
        NULL;
    END
    $$;
    DO $$
    BEGIN
        GRANT $TENANT_ROLE TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END
    $$;
""".trimIndent()

/**
 * The SQL that confines a table with an `org_id` column: row-level security enabled and forced (its owner is confined
 * too), one fail-closed policy (no setting, no row), and the grants of [TENANT_ROLE].
 *
 * @param table The table.
 *
 * @return The statements.
 */
internal fun tenantTableSql(table: String): String = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_tenant ON $table
        USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID));
    GRANT SELECT, INSERT, UPDATE, DELETE ON $table TO $TENANT_ROLE;
""".trimIndent()
