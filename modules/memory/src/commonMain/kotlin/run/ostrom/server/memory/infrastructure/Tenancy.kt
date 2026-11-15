// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.TENANT_ROLE
import run.ostrom.server.kernel.database.TENANT_SETTING

// The memory's queries run through the kernel's `inOrg` (S2-01): every one also names its organization and the
// caller's visibility, so a later move off row-level security keeps the same rules.

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
