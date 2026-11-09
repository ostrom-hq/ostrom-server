// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.models

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
            // Every push: a bare `git push` on main names no branch. The server reads the refs the hook sends and
            // answers a push that reaches neither main nor master as ungated (second audit H3).
            listOf(GIT_PUSH, "\\bgh\\s+api\\b[^\\n]*git/refs/heads/(main|master)\\b"),
            listOf("push to main", "main", "pin", "production", "merge"),
        ),
        ActionClass(
            "push-tag",
            "Push a release tag",
            listOf("Bash"),
            // Every push too: a tag needs no `v` (`git push origin 1.4.0`); the server reads the refs for a tag.
            listOf(GIT_PUSH, "\\bgh\\s+release\\s+create\\b", "\\bgh\\s+api\\b[^\\n]*git/refs/tags\\b"),
            listOf("tag", "release", "production"),
        ),
        ActionClass(
            "merge",
            "Merge a pull request",
            // The GitHub MCP server merges too (`mcp__github__merge_pull_request`), and so do the REST and GraphQL
            // APIs.
            listOf("Bash", "mcp"),
            listOf(
                "\\bgh\\s+pr\\s+merge\\b",
                "merge_pull_request",
                "\\bgh\\s+api\\b[^\\n]*(/pulls/[^\\s/]+/merge\\b|/merges\\b|mergePullRequest)",
            ),
            listOf("merge", "approve", "review"),
        ),
        ActionClass(
            "deploy",
            "Deploy to production",
            listOf("Bash"),
            // Global options may come before the verb: `kubectl -n prod apply`, `helm --kube-context x upgrade`,
            // `terraform -chdir=infra apply` (second audit H3).
            listOf(
                "\\bhelm\\b[^\\n;&|]*\\s(upgrade|install|rollback|uninstall)\\b",
                "\\bkubectl\\b[^\\n;&|]*\\s(apply|rollout|set\\s+image|delete|scale|replace|patch)\\b",
                "\\bgh\\s+workflow\\s+run\\b",
                "\\bgh\\s+api\\b[^\\n]*/dispatches\\b",
                "\\bterraform\\b[^\\n;&|]*\\s(apply|destroy)\\b",
                "\\b(fly|flyctl)\\b[^\\n;&|]*\\sdeploy\\b",
                "\\bvercel\\b[^\\n]*--prod",
                "\\b(npm|pnpm|yarn|bun)\\b[^\\n;&|]*\\spublish\\b",
                "\\bdocker\\b[^\\n;&|]*(\\spush\\b|--push\\b)",
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

// `git`, its global options (`-C dir`, `-c key=value`, `--no-pager`, `--git-dir=…`), then `push` as the command:
// `git stash push` is not one.
// The alternatives never overlap, so a long run of options is read once, never backtracked through.
private const val GIT_PUSH =
    "\\bgit(\\s+(-[cC]\\s*[^\\s-]\\S*|--[a-zA-Z][\\w-]*(=\\S+)?|-[abd-zABD-Z]\\w*))*\\s+push\\b"
