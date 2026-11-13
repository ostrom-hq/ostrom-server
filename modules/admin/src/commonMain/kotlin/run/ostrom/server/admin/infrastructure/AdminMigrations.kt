// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the admin module; its tables start with `admin_`. Both are the instance's, not an organization's (no
 * `org_id`, docs/tenancy.md): the instance's administrators, removed ones kept (`removed_at`) so that neither the
 * setup link nor the former variable brings them back; and the settings sections, one row each, plain values and
 * sealed secrets as JSON objects, the secrets sealed with the secrets key (`AesGcmSecretCipher`, context
 * `instance-setting:<section>:<field>`), so a read of the table alone opens nothing.
 */
val AdminMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_admin",
        sql = """
            CREATE TABLE admin_instance_admins (
                user_id UUID PRIMARY KEY,
                source TEXT NOT NULL CHECK (source IN ('setup', 'dashboard', 'environment')),
                added_at TIMESTAMPTZ NOT NULL,
                added_by UUID,
                removed_at TIMESTAMPTZ,
                removed_by UUID
            );
            CREATE TABLE admin_settings (
                section TEXT PRIMARY KEY,
                plain_values TEXT NOT NULL,
                sealed_values TEXT NOT NULL,
                version INTEGER NOT NULL CHECK (version > 0),
                updated_at TIMESTAMPTZ NOT NULL,
                updated_by UUID
            )
        """.trimIndent(),
    ),
)
