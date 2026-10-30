// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

/**
 * One versioned change of a module's schema. Versions start at 1 and follow each other without gaps within a
 * module; an applied migration is never edited (its checksum is verified at every start), a new one is added.
 *
 * Write plain PostgreSQL. Statements are split on `;`. Avoid `::type` casts (use `CAST(x AS type)`): the driver
 * reads `:name` as a named parameter.
 *
 * @property version The position of the migration in its module, from 1.
 * @property name A short snake_case description, e.g. `create_instances`.
 * @property sql The statements to run.
 */
data class SchemaMigration(
    val version: Long,
    val name: String,
    val sql: String,
)
