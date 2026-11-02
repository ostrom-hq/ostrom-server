// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The rules schema; its tables start with `rules_`. Lists (scope, sources, gate) are stored as the JSON the contract
 * writes. Every table is confined to its organization by forced row level security: without `ostrom.org_id`, no row is
 * visible and none can be written (CONVENTIONS 16.1).
 */
val RulesMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        1,
        "create_rules",
        listOf(
            """
            CREATE TABLE rules_rules (
                org_id UUID NOT NULL,
                id TEXT NOT NULL,
                statement TEXT NOT NULL,
                output TEXT NOT NULL,
                rule_group TEXT NOT NULL,
                scope TEXT NOT NULL,
                sources TEXT NOT NULL,
                why TEXT,
                strength TEXT NOT NULL,
                confidence DOUBLE PRECISION NOT NULL,
                status TEXT NOT NULL,
                found_by TEXT NOT NULL,
                found_at TIMESTAMPTZ NOT NULL,
                review_by TEXT,
                decided_by TEXT,
                decided_at TIMESTAMPTZ,
                origin TEXT,
                gate TEXT,
                updated_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, id)
            )
            """.trimIndent(),
            "CREATE INDEX rules_rules_by_group ON rules_rules (org_id, rule_group, id)",
            """
            CREATE TABLE rules_decisions (
                org_id UUID NOT NULL,
                id TEXT NOT NULL,
                decided_at TIMESTAMPTZ NOT NULL,
                decided_by TEXT NOT NULL,
                subject_kind TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                subject_label TEXT,
                action TEXT NOT NULL,
                before_doc TEXT,
                after_doc TEXT,
                rule_digest TEXT,
                signed BOOLEAN NOT NULL,
                PRIMARY KEY (org_id, id)
            )
            """.trimIndent(),
            "CREATE INDEX rules_decisions_recent ON rules_decisions (org_id, decided_at DESC, id DESC)",
            "CREATE INDEX rules_decisions_by_subject " +
                "ON rules_decisions (org_id, subject_kind, subject_id, decided_at DESC, id DESC)",
            "CREATE INDEX rules_decisions_unsigned ON rules_decisions (org_id) WHERE NOT signed",
            """
            CREATE TABLE rules_gate_policy (
                org_id UUID PRIMARY KEY,
                self_approval TEXT NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            )
            """.trimIndent(),
            tenant("rules_rules"),
            tenant("rules_decisions"),
            tenant("rules_gate_policy"),
        ).joinToString(";\n"),
    ),
    // The gates catalogue reads the gated rules on every boundary action.
    SchemaMigration(
        2,
        "index_gated_rules",
        "CREATE INDEX rules_rules_gated ON rules_rules (org_id, id) WHERE gate IS NOT NULL",
    ),
    SchemaMigration(
        3,
        "create_analyses",
        listOf(
            """
            CREATE TABLE rules_analyses (
                org_id UUID NOT NULL,
                kind TEXT NOT NULL,
                run_id TEXT,
                phase TEXT NOT NULL,
                progress DOUBLE PRECISION NOT NULL,
                failure_stage TEXT,
                failure_reason TEXT,
                failed_at TIMESTAMPTZ,
                items TEXT NOT NULL,
                started_by TEXT,
                started_at TIMESTAMPTZ,
                updated_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (org_id, kind)
            )
            """.trimIndent(),
            tenant("rules_analyses"),
        ).joinToString(";\n"),
    ),
)

private fun tenant(table: String): String = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_org ON $table
        USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', TRUE), '') AS UUID))
""".trimIndent()
