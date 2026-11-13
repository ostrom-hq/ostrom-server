// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean

// pg_has_role(…, 'MEMBER'): the role is the owner or a member of it, inheriting its rights or able to SET ROLE to it.
private const val BYPASSES = """
SELECT r.rolsuper OR r.rolbypassrls OR EXISTS (
    SELECT 1 FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
      AND pg_has_role(current_user, c.relowner, 'MEMBER')
) AS bypasses
FROM pg_roles r WHERE r.rolname = current_user
"""

/**
 * Refuses a connection whose role can turn row-level security off, checked once at start before serving.
 *
 * @throws UnsafeDatabaseRoleException When the role is a superuser, has `BYPASSRLS`, or owns a table of the schema
 *   or is a member of its owner.
 */
suspend fun Driver.requireRowLevelSecurity() {
    val bypasses = fetchAll(BYPASSES.trimIndent()).getOrThrow().single().get("bypasses").asBoolean()
    if (bypasses) throw UnsafeDatabaseRoleException()
}
