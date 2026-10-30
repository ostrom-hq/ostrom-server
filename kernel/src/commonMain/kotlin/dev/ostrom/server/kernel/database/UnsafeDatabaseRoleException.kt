// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

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
