// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.statements.StatementType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every foreign key leads an index, read from PostgreSQL's catalog once every module has migrated (the database audit
 * of 18/11). TeamCode's MySQL creates that index with each foreign key; PostgreSQL does not, so a delete of the
 * referenced row (its `ON DELETE CASCADE` or `SET NULL`) would read the whole referencing table. The known exceptions
 * are listed with their reason: a new foreign key without an index fails, and so does a fixed one still listed. Each
 * reason is that no code deletes the parent: the change that first deletes one adds the index and removes the entry.
 */
class SchemaIndexesPostgresTest {
    @Test
    fun everyForeignKeyLeadsAnIndexOrIsListedWithItsReason() = serverTest(serverModules()) { server ->
        val found = server.database.superuserJdbc.query {
            val keys = mutableSetOf<String>()
            exec(FOREIGN_KEYS_WITHOUT_AN_INDEX, explicitStatementType = StatementType.SELECT) { rows ->
                while (rows.next()) keys += "${rows.getString("tbl")}.${rows.getString("cols")}"
            }
            keys
        }
        assertEquals(UNINDEXED.keys, found, "Foreign keys without an index, each listed with its reason")
    }

    private companion object {
        /** No code deletes a user (an account is disabled, never removed): its rows are never cascaded. */
        const val NO_USER_DELETE = "no code deletes a user"

        val UNINDEXED = mapOf(
            "identity_web_sessions.user_id" to "$NO_USER_DELETE; the live sessions' partial index serves the reads",
            "identity_api_tokens.user_id" to "$NO_USER_DELETE; the live tokens' partial index serves the reads",
            "identity_device_authorizations.user_id" to NO_USER_DELETE,
            "identity_oauth_attempts.link_user_id" to NO_USER_DELETE,
            "identity_sign_in_links.user_id" to NO_USER_DELETE,
            "identity_mobile_codes.user_id" to NO_USER_DELETE,
            "gates_sessions.gate_id" to "no code deletes a gate; reads go through (org_id, gate_id)",
            "automations_wait_secrets.wait_id" to "no code deletes a wait; its secrets go by session",
        )

        /**
         * The foreign keys of the schema that lead no index: an index whose first columns are exactly the key's, in any
         * order, or its columns but `org_id` (an id is one organization's: a cascade by it needs no `org_id`), total or
         * partial on `col IS NOT NULL` (a nullable key, whose null rows a delete never meets).
         */
        const val FOREIGN_KEYS_WITHOUT_AN_INDEX = """
            SELECT t.relname AS tbl, string_agg(a.attname, ',' ORDER BY k.ord) AS cols
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k (attnum, ord)
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
            WHERE c.contype = 'f' AND n.nspname = 'public' AND a.attname <> 'org_id'
                AND NOT EXISTS (
                    SELECT 1 FROM pg_index i
                    CROSS JOIN (VALUES (true), (false)) AS whole (with_org)
                    CROSS JOIN LATERAL (
                        SELECT array_agg(f.attnum ORDER BY f.attnum) AS cols, min(f.attname) AS col
                        FROM pg_attribute f WHERE f.attrelid = c.conrelid AND f.attnum = ANY (c.conkey)
                            AND (f.attname <> 'org_id' OR whole.with_org)
                    ) AS key
                    WHERE i.indrelid = c.conrelid AND i.indnkeyatts >= cardinality(key.cols)
                        AND key.cols = (
                            SELECT array_agg(x ORDER BY x)
                            FROM unnest(CAST(i.indkey AS int2[])) WITH ORDINALITY AS u (x, o)
                            WHERE o <= cardinality(key.cols)
                        )
                        AND (i.indpred IS NULL OR (cardinality(key.cols) = 1
                            AND pg_get_expr(i.indpred, i.indrelid) = '(' || key.col || ' IS NOT NULL)'))
                )
            GROUP BY t.relname, c.conname
            ORDER BY 1, 2
        """
    }
}
