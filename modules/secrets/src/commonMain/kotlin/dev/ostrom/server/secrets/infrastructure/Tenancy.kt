// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets.infrastructure

import dev.ostrom.server.kernel.database.inOrg
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

/**
 * The role every organization-scoped query of the RELAY modules runs as: not a superuser, owner of nothing, so the
 * row-level security policies always apply to it (SECURITY-AUDIT S2-01). The same role and setting as the identity
 * module's `inOrg`, so both converge on the kernel's entry point when it lands.
 */
const val TENANT_ROLE: String = "ostrom_tenant"

/** The transaction-local setting the policies read: the organization of the query. */
const val TENANT_SETTING: String = "ostrom.org_id"

/**
 * Runs [block] in a transaction confined to one organization, by the kernel's [inOrg]: the transaction switches to
 * [TENANT_ROLE] and sets [TENANT_SETTING] in one statement; a tenant table then only shows and accepts the rows of
 * [orgId], whatever the query says.
 *
 * @param orgId The organization.
 * @param block The queries; a failure inside rolls the transaction back.
 *
 * @return What [block] returns.
 */
suspend fun <T> Driver.inTenant(orgId: Uuid, block: suspend Transaction.() -> T): T =
    inOrg(orgId, block)

/** One query confined to [orgId] (see [inTenant]). */
suspend fun Driver.fetchInOrg(orgId: Uuid, statement: Statement): Result<ResultSet> =
    inTenant(orgId) { Result.success(fetchAll(statement).getOrThrow()) }

/** One statement confined to [orgId] (see [inTenant]). */
suspend fun Driver.executeInOrg(orgId: Uuid, statement: Statement): Result<Long> =
    inTenant(orgId) { Result.success(execute(statement).getOrThrow()) }

/**
 * The SQL that creates [TENANT_ROLE] (tolerating one created by another module, database or test) and grants it to
 * the migrating user. Roles are global to the PostgreSQL cluster.
 */
val TENANT_ROLE_SQL: String = """
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
fun tenantTableSql(table: String): String = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_tenant ON $table
        USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID));
    GRANT SELECT, INSERT, UPDATE, DELETE ON $table TO $TENANT_ROLE;
""".trimIndent()
