// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

/**
 * A scope a personal token may be given.
 *
 * @property scope The scope, `resource:action`.
 * @property description What it allows, one short sentence for the console's token form.
 */
data class TokenScope(val scope: String, val description: String)

/**
 * The catalogue of the scopes a personal token may be given, grouped by resource: the only source, served by
 * `GET /api/v1/tokens/scopes` and enforced by [validateScopes]. It lists what a route of the server checks for a
 * person's token; never [NEVER_ON_A_TOKEN] (a yes, an agent's scope, a browser session's), nor `billing:manage`,
 * which no longer allows anything more than `billing:read` (S2-52), nor [NOTIFICATIONS_WRITE_SCOPE]: a token handed
 * to an agent must not be able to silence the approvals its person is notified of; the console and the phone app set
 * them. `waits:ask` is in it: the least privilege for a
 * token handed to an agent, which asks and never answers. A module that checks a new scope adds its line here, in the
 * same pull request. The catalogue governs what a token is given: a token created before it keeps its scopes until it
 * is revoked.
 */
val PERSONAL_TOKEN_SCOPES: List<TokenScope> = listOf(
    TokenScope("profile:read", "Read your profile."),
    TokenScope("orgs:read", "Read your organizations, their members and invitations."),
    TokenScope("orgs:write", "Create and change organizations, invite and manage their members."),
    TokenScope("audit:read", "Read the organization's audit log (owners and admins)."),
    TokenScope("workflows:read", "Read the workflows."),
    TokenScope("workflows:write", "Propose workflows; accepting one stays in the console."),
    TokenScope("packages:read", "Read the session packages and the gates catalogue."),
    TokenScope("packages:write", "Revoke and restore a session package version."),
    TokenScope("sessions:read", "Read the agent sessions."),
    TokenScope("sessions:write", "Start, keep alive and end agent sessions."),
    TokenScope("gates:ask", "Open a gate and read it, as a CI job does; never answer one."),
    TokenScope("gates:read", "Read the gates and their approval requests; never answer one."),
    TokenScope("rules:read", "Read the rules, their signed decisions and the self-approval policy."),
    TokenScope("receipts:read", "Read and verify the signed receipts."),
    TokenScope("artifacts:read", "Read the artifacts."),
    TokenScope("artifacts:write", "Publish, share and delete artifacts."),
    TokenScope("secrets:read", "List the secrets, never their values."),
    TokenScope("secrets:write", "Save and delete secrets."),
    TokenScope("accounts:read", "Read the shared accounts and their capacity."),
    TokenScope("accounts:write", "Share, change and remove accounts."),
    TokenScope("mcp:read", "Read the MCP servers, their journal and usage."),
    TokenScope("mcp:admin", "Change the MCP servers, their assignments, policies and settings."),
    TokenScope("mcp:use", "Call the relayed MCP servers."),
    TokenScope("inference:read", "Read the model policy and the inference settings."),
    TokenScope("inference:admin", "Change the model policy and the inference settings."),
    TokenScope("inference:use", "Call the inference gateway."),
    TokenScope("cloud:read", "Read the cloud sessions."),
    TokenScope("cloud:write", "Create, pause, resume, delete and attach to cloud sessions."),
    TokenScope("automations:read", "Read the automations and their runs."),
    TokenScope("automations:write", "Create, change, test and delete automations."),
    TokenScope("waits:read", "Read the waits."),
    TokenScope("waits:ask", "Ask, read and cancel waits, as an agent does; never answer one."),
    TokenScope("waits:write", "Ask, answer and cancel waits."),
    TokenScope("budgets:read", "Read the budgets."),
    TokenScope("budgets:write", "Set the budgets."),
    TokenScope("usage:read", "Read the usage ledger."),
    TokenScope("usage:write", "Record usage events."),
    TokenScope("billing:read", "Read the plan, the usage and the invoices; a billing change stays in the console."),
    TokenScope("import:write", "Import a TeamCode team."),
)

/** The scopes of [PERSONAL_TOKEN_SCOPES], for a membership check. */
val PERSONAL_TOKEN_SCOPE_NAMES: Set<String> = PERSONAL_TOKEN_SCOPES.mapTo(LinkedHashSet()) { it.scope }
