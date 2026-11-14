// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.secrets.infrastructure.TENANT_ROLE_SQL
import run.ostrom.server.secrets.infrastructure.tenantTableSql

/**
 * The schema of the cloud module; its tables start with `cloud_` (TeamCode's cloud runtime, `cloud_clusters` and
 * `cloud_profiles`). `cloud_runtime` (one row) and `cloud_clusters` are the instance's, without `org_id`
 * (docs/tenancy.md); a cluster's CA bundle and token are sealed with the secrets key (context
 * `cloud-cluster:<id>:ca|token`), so a read of the table alone opens nothing. `cloud_profiles` and their immutable
 * `cloud_profile_versions` are an organization's, under row-level security, as are its `cloud_org_settings`.
 */
val CloudMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_cloud",
        sql = """
            CREATE TABLE cloud_runtime (
                id SMALLINT PRIMARY KEY CHECK (id = 1),
                body TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                updated_by UUID
            );
            CREATE TABLE cloud_clusters (
                id UUID PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                api_url TEXT NOT NULL,
                sealed_ca TEXT NOT NULL,
                sealed_token TEXT NOT NULL,
                allowed_namespaces TEXT NOT NULL,
                max_running_pods INTEGER NOT NULL CHECK (max_running_pods > 0),
                default_storage_class TEXT,
                version INTEGER NOT NULL CHECK (version > 0),
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE TABLE cloud_profiles (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                name TEXT NOT NULL,
                version INTEGER NOT NULL CHECK (version > 0),
                assigned_member_ids TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX cloud_profiles_org ON cloud_profiles (org_id, name);
            CREATE TABLE cloud_profile_versions (
                profile_id UUID NOT NULL REFERENCES cloud_profiles (id),
                org_id UUID NOT NULL,
                version INTEGER NOT NULL CHECK (version > 0),
                spec TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (profile_id, version)
            )
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "cloud_profiles_tenancy",
        sql = listOf(TENANT_ROLE_SQL, tenantTableSql("cloud_profiles"), tenantTableSql("cloud_profile_versions"))
            .joinToString("\n"),
    ),
    // An organization's cloud settings (TeamCode's team `cloud_settings`), under row-level security.
    SchemaMigration(
        version = 3,
        name = "cloud_org_settings",
        sql = """
            CREATE TABLE cloud_org_settings (
                org_id UUID PRIMARY KEY,
                body TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                updated_by UUID NOT NULL
            );
            ${tenantTableSql("cloud_org_settings")}
        """.trimIndent(),
    ),
    // runners.cloud.profiles.v1: the cluster imported from the former variables may be the server's own, reached with
    // the server pod's rotating service account, read from its files at each use: nothing sealed is stored for it.
    // It is marked, whatever its name becomes: the sessions created before profiles are given it.
    SchemaMigration(
        version = 4,
        name = "cloud_cluster_in_cluster",
        sql = """
            ALTER TABLE cloud_clusters ADD COLUMN in_cluster BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE cloud_clusters ADD COLUMN imported BOOLEAN NOT NULL DEFAULT FALSE;
        """.trimIndent(),
    ),
)
