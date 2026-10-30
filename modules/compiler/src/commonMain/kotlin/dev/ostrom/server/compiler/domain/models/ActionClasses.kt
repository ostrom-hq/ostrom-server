package dev.ostrom.server.compiler.domain.models

/**
 * A boundary action: what a gate can cover.
 *
 * @property cls The class (`push-main`, `deploy`…).
 * @property label What it does.
 * @property tools The agent tools it concerns (`Bash`, `Write`, `mcp`…).
 * @property patterns Regular expressions (JavaScript syntax, case-insensitive) on the command, path or MCP tool.
 * @property keywords Words that tie it to a gate that declared nothing.
 */
data class ActionClass(
    val cls: String,
    val label: String,
    val tools: List<String>,
    val patterns: List<String>,
    val keywords: List<String>,
)

/** The prototype's catalogue of boundary actions (`bridge/harness.ts` `ACTIONS`), in its order. */
object ActionClasses {
    /** The built-in classes. An action that matches none passes without asking anything. */
    val builtIn: List<ActionClass> = listOf(
        ActionClass(
            "push-main",
            "Push to the main branch",
            listOf("Bash"),
            listOf(
                "\\bgit\\s+push\\b[^\\n;&|]*\\b(main|master)\\b",
                "\\bgit\\s+push\\b[^\\n;&|]*HEAD:(refs/heads/)?(main|master)",
            ),
            listOf("push to main", "main", "pin", "production", "merge"),
        ),
        ActionClass(
            "push-tag",
            "Push a release tag",
            listOf("Bash"),
            listOf("\\bgit\\s+push\\b[^\\n;&|]*(--tags|\\bv\\d+\\.\\d+)", "\\bgh\\s+release\\s+create\\b"),
            listOf("tag", "release", "production"),
        ),
        ActionClass(
            "merge",
            "Merge a pull request",
            listOf("Bash"),
            listOf("\\bgh\\s+pr\\s+merge\\b"),
            listOf("merge", "approve", "review"),
        ),
        ActionClass(
            "deploy",
            "Deploy to production",
            listOf("Bash"),
            listOf(
                "\\bhelm\\s+(upgrade|install|rollback)\\b",
                "\\bkubectl\\s+(apply|rollout|set\\s+image|delete|scale)\\b",
                "\\bgh\\s+workflow\\s+run\\s+\\S*deploy",
                "\\bterraform\\s+apply\\b",
                "\\b(fly|flyctl)\\s+deploy\\b",
                "\\bvercel\\b[^\\n]*--prod",
                "\\bnpm\\s+publish\\b",
                "\\bdocker\\s+push\\b",
                "(^|[\\s;&|/])deploy(\\.sh)?\\s+(prod|production)\\b",
            ),
            listOf("deploy", "production", "helm", "rollout", "pin", "release"),
        ),
        ActionClass(
            "migration",
            "Run or add a database migration",
            listOf("Bash", "Write", "Edit", "MultiEdit"),
            listOf(
                "\\bflyway\\b[^\\n]*\\bmigrate\\b",
                "\\bprisma\\s+migrate\\s+deploy\\b",
                "\\balembic\\s+upgrade\\b",
                "\\bliquibase\\b[^\\n]*\\bupdate\\b",
                "\\b(rails|rake)\\s+db:migrate\\b",
                "\\bknex\\s+migrate",
                "/db/migration/",
            ),
            listOf("migration", "schema", "database", "flyway"),
        ),
        ActionClass(
            "mobile-release",
            "Release the mobile app to the stores",
            listOf("Bash"),
            listOf("\\bfastlane\\b", "\\beas\\s+(submit|build)\\b[^\\n]*production"),
            listOf("mobile", "store", "fastlane", "daily release", "app"),
        ),
        ActionClass(
            "client-message",
            "Send a message to a customer",
            listOf("mcp"),
            listOf(
                "send_validated_message",
                "discord_send",
                "discord_reply",
                "chat_postmessage",
                "send_email",
                "slack_post",
            ),
            listOf("client", "customer", "support", "message", "reply"),
        ),
    )

    /** Classes the compiler knows on top of the gates' catalogue (`shared/src/compiler.ts` `EXTRA_ACTIONS`). */
    val extra: List<ActionClass> = listOf(
        ActionClass("push-any", "Push to any branch", emptyList(), listOf("\\bgit\\s+push\\b"), emptyList()),
        ActionClass("commit", "Commit", emptyList(), listOf("\\bgit\\s+commit\\b"), emptyList()),
    )
}

/**
 * The gate that covers an action in a repository (contract `GateRef`).
 *
 * @property workflowId The workflow.
 * @property workflowName Its name.
 * @property nodeId The gate node.
 * @property label The gate's label.
 * @property approvers Who may say yes.
 * @property status The workflow's status.
 * @property enforce False while the workflow is only proposed.
 * @property basis `declared` (the gate lists the action) or `guessed` (from its words).
 * @property reason Where the approvers come from.
 */
data class CatalogGate(
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val approvers: List<String>,
    val status: String,
    val enforce: Boolean,
    val basis: String,
    val reason: String,
)

/**
 * One line of a repository's gates catalogue: an action class, its patterns for this repository and its gate.
 *
 * @property action The action class, its patterns replaced by those the gate declares for this repository.
 * @property gate The gate that covers it, or null.
 */
data class CatalogEntry(val action: ActionClass, val gate: CatalogGate?)
