// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

private val TABLES = listOf(
    "compiler_workflows",
    "compiler_decisions",
    "compiler_harnesses",
    "compiler_packages",
    "compiler_pins",
    "compiler_audit",
)

// Row level security forced on every table (the owner included): a transaction that has not set `ostrom.org_id`
// sees and writes nothing.
private fun isolated(table: String) = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID));
""".trimIndent()

/**
 * The compiler schema: workflows (with their version for optimistic saves), the decisions taken on them, harness
 * versions, signed packages, pins and the packages audit. A repository key is the repository name, or `*` for the
 * organization.
 */
val CompilerMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_compiler",
        """
        CREATE TABLE compiler_workflows (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            doc TEXT NOT NULL,
            version INT NOT NULL,
            updated_by TEXT NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE TABLE compiler_decisions (
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            subject_kind TEXT NOT NULL,
            subject_id TEXT NOT NULL,
            decided_by TEXT NOT NULL,
            decided_at TEXT NOT NULL,
            receipt_id TEXT,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX compiler_decisions_by_subject ON compiler_decisions (org_id, subject_kind, subject_id);
        CREATE TABLE compiler_harnesses (
            org_id UUID NOT NULL,
            repo_key TEXT NOT NULL,
            digest TEXT NOT NULL,
            version TEXT NOT NULL,
            receipt_id TEXT,
            PRIMARY KEY (org_id, repo_key, digest)
        );
        CREATE TABLE compiler_packages (
            org_id UUID NOT NULL,
            content_digest TEXT NOT NULL,
            digest TEXT NOT NULL,
            version TEXT NOT NULL,
            repo TEXT,
            compiled_at TEXT NOT NULL,
            envelope TEXT NOT NULL,
            counts TEXT NOT NULL,
            revoked BOOLEAN NOT NULL DEFAULT FALSE,
            revoked_by TEXT,
            revoke_reason TEXT,
            PRIMARY KEY (org_id, content_digest)
        );
        CREATE INDEX compiler_packages_by_digest ON compiler_packages (org_id, digest);
        CREATE INDEX compiler_packages_recent ON compiler_packages (org_id, compiled_at DESC);
        CREATE TABLE compiler_pins (
            org_id UUID NOT NULL,
            repo_key TEXT NOT NULL,
            content_digest TEXT NOT NULL,
            pinned_by TEXT NOT NULL,
            pinned_at TIMESTAMPTZ NOT NULL,
            PRIMARY KEY (org_id, repo_key)
        );
        CREATE TABLE compiler_audit (
            seq BIGSERIAL,
            org_id UUID NOT NULL,
            id TEXT NOT NULL,
            at TEXT NOT NULL,
            event TEXT NOT NULL,
            actor TEXT NOT NULL,
            subject TEXT NOT NULL,
            meta TEXT NOT NULL,
            PRIMARY KEY (org_id, id)
        );
        CREATE INDEX compiler_audit_recent ON compiler_audit (org_id, seq DESC);
        """.trimIndent() + "\n" + TABLES.joinToString("\n", transform = ::isolated).removeSuffix(";"),
    ),
    // One repository's packages newest first, the order `GET /packages?repo=` answers in (CompilerReadsBenchTest).
    // The workflows need none: the bench reads an organization's 1000 through the primary key and sorts them within
    // the budget. Built in the migration's transaction, not CONCURRENTLY: the table is small today.
    SchemaMigration(
        2,
        "packages_by_repo",
        "CREATE INDEX compiler_packages_by_repo ON compiler_packages (org_id, repo, compiled_at DESC, content_digest)",
    ),
)
