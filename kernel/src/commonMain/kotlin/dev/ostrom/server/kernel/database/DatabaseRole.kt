// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean

/**
 * The serving process connects with a role that can bypass row-level security (a superuser or `BYPASSRLS`): every
 * organization's rows would be visible to every request. The server refuses to start (CONVENTIONS 16.1, S2-01).
 */
class UnsafeDatabaseRoleException : IllegalStateException(
    "The database role can bypass row-level security (superuser or BYPASSRLS): serve with a role that owns nothing " +
        "and run migrations with `ostrom-server migrate` and the owner's credentials.",
)

/**
 * Refuses a connection whose role is a superuser or has `BYPASSRLS`, checked once at start before serving.
 *
 * @throws UnsafeDatabaseRoleException When the role can bypass row-level security.
 */
suspend fun Driver.requireRowLevelSecurity() {
    val bypasses = fetchAll("SELECT rolsuper OR rolbypassrls AS bypasses FROM pg_roles WHERE rolname = current_user")
        .getOrThrow()
        .single()
        .get("bypasses")
        .asBoolean()
    if (bypasses) throw UnsafeDatabaseRoleException()
}
