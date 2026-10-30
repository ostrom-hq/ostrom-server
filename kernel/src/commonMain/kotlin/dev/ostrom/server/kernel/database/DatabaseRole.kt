// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean

/**
 * The serving process connects with a role that can turn row-level security off: a superuser, a `BYPASSRLS` role, or
 * a role that owns a table of the schema (or is a member of its owner), which may `ALTER TABLE … DISABLE ROW LEVEL
 * SECURITY`. Every organization's rows would be reachable from every request: the server refuses to start
 * (CONVENTIONS 16.1, S2-01).
 */
class UnsafeDatabaseRoleException : IllegalStateException(
    "The database role can bypass row-level security (superuser, BYPASSRLS, or owner of a table): serve with a role " +
        "that owns nothing and run migrations with `ostrom-server migrate` and the owner's credentials.",
)

// pg_has_role(…, 'USAGE'): the role is the owner, or inherits the owner's rights.
private const val BYPASSES = """
SELECT r.rolsuper OR r.rolbypassrls OR EXISTS (
    SELECT 1 FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
      AND pg_has_role(current_user, c.relowner, 'USAGE')
) AS bypasses
FROM pg_roles r WHERE r.rolname = current_user
"""

/**
 * Refuses a connection whose role can turn row-level security off, checked once at start before serving.
 *
 * @throws UnsafeDatabaseRoleException When the role is a superuser, has `BYPASSRLS`, or owns a table of the schema.
 */
suspend fun Driver.requireRowLevelSecurity() {
    val bypasses = fetchAll(BYPASSES.trimIndent()).getOrThrow().single().get("bypasses").asBoolean()
    if (bypasses) throw UnsafeDatabaseRoleException()
}
