// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/** The schema of the artifacts module; its tables start with `artifacts_`. */
val ArtifactsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_artifacts",
        sql = """
            CREATE TABLE artifacts_artifacts (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                owner_id TEXT NOT NULL,
                title TEXT NOT NULL,
                access TEXT NOT NULL,
                latest_version INTEGER NOT NULL,
                link_slug TEXT UNIQUE,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX artifacts_artifacts_org_updated ON artifacts_artifacts (org_id, updated_at DESC, id DESC);
            CREATE TABLE artifacts_versions (
                artifact_id UUID NOT NULL REFERENCES artifacts_artifacts (id) ON DELETE CASCADE,
                number INTEGER NOT NULL,
                entry TEXT NOT NULL,
                files TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                created_by TEXT NOT NULL,
                PRIMARY KEY (artifact_id, number)
            );
            CREATE TABLE artifacts_blobs (
                artifact_id UUID NOT NULL REFERENCES artifacts_artifacts (id) ON DELETE CASCADE,
                sha256 TEXT NOT NULL,
                bytes BYTEA NOT NULL,
                PRIMARY KEY (artifact_id, sha256)
            );
            CREATE TABLE artifacts_members (
                artifact_id UUID NOT NULL REFERENCES artifacts_artifacts (id) ON DELETE CASCADE,
                member_id TEXT NOT NULL,
                PRIMARY KEY (artifact_id, member_id)
            );
            CREATE INDEX artifacts_members_member ON artifacts_members (member_id);
        """.trimIndent(),
    ),
)
