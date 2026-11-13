// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app.isolation

import run.ostrom.server.app.isolation.Seed.ACCOUNT
import run.ostrom.server.app.isolation.Seed.APPROVAL
import run.ostrom.server.app.isolation.Seed.ARTIFACT
import run.ostrom.server.app.isolation.Seed.AUTH_SESSION
import run.ostrom.server.app.isolation.Seed.AUTOMATION
import run.ostrom.server.app.isolation.Seed.CLOUD_SESSION
import run.ostrom.server.app.isolation.Seed.CONFIRMATION
import run.ostrom.server.app.isolation.Seed.DEVICE
import run.ostrom.server.app.isolation.Seed.GATE
import run.ostrom.server.app.isolation.Seed.GROUP
import run.ostrom.server.app.isolation.Seed.INVITATION
import run.ostrom.server.app.isolation.Seed.LAUNCH
import run.ostrom.server.app.isolation.Seed.LINK_SLUG
import run.ostrom.server.app.isolation.Seed.MCP_POLICY
import run.ostrom.server.app.isolation.Seed.MCP_SERVER
import run.ostrom.server.app.isolation.Seed.MEMBER_ID
import run.ostrom.server.app.isolation.Seed.MEMBER_LOGIN
import run.ostrom.server.app.isolation.Seed.ORG_ID
import run.ostrom.server.app.isolation.Seed.ORG_SLUG
import run.ostrom.server.app.isolation.Seed.PACKAGE
import run.ostrom.server.app.isolation.Seed.PUSH_DEVICE
import run.ostrom.server.app.isolation.Seed.RECEIPT
import run.ostrom.server.app.isolation.Seed.RULE
import run.ostrom.server.app.isolation.Seed.RUN
import run.ostrom.server.app.isolation.Seed.RUNNER
import run.ostrom.server.app.isolation.Seed.SECRET
import run.ostrom.server.app.isolation.Seed.SERVICE_KEY
import run.ostrom.server.app.isolation.Seed.SESSION
import run.ostrom.server.app.isolation.Seed.TOKEN
import run.ostrom.server.app.isolation.Seed.WAIT
import run.ostrom.server.app.isolation.Seed.WORKFLOW

private const val SIGN_IN = "a sign-in flow: it names no organization, its code or link is the only key"
private const val CALLBACK = "a redirect back from GitHub, bound to the state cookie of the browser that left"
private const val LINK_CALLBACK = "a redirect back from GitHub: its single-use state names the attempt, which only " +
    "the browser session that started it finishes"
private const val SIGNED = "a webhook signed with the provider's secret: unsigned, it is refused before any lookup"
/** The content origin's host in the matrix (OSTROM_ARTIFACTS_CONTENT_ORIGIN): the artifact pages are served there. */
internal const val CONTENT_HOST = "artifacts.test"

private const val LINK = "a link artifact is public by design: its unguessable slug is the capability " +
    "(a team artifact's content, behind a ticket, is attacked on /a/)"
private const val WEBSOCKET = "a WebSocket: plain HTTP never reaches its handler, and the client's socket opens with " +
    "a terminal ticket, attacked on POST .../terminal/ticket; the runner's names its own execution"
private const val CAPABILITY = "the secret in the body is the capability: holding it is the right (invitation link)"

/**
 * How the isolation matrix treats every route the server mounts, by `METHOD /path` as Ktor writes it. A route mounted
 * without its case here fails `everyMountedRouteHasItsCaseInTheIsolationRegistry`: say how it is attacked, or why not.
 */
internal val ISOLATION_REGISTRY: Map<String, Case> = mapOf(
    // Probes and public metadata.
    "GET /health" to Case.Target(),
    "GET /ready" to Case.Target(),
    "GET /api/v1/health" to Case.Target(),
    "GET /api/v1/instance" to Case.Target(),
    "GET /api/v1/instance/keys" to Case.Target(),
    // What the server speaks and serves: the same answer for every caller, nothing of an organization's.
    "GET /api/v1/capabilities" to Case.Target(),
    "GET /api/v1/auth/providers" to Case.Target(),
    "GET /api/v1/tokens/scopes" to Case.Target(),

    // Sign-in flows (identity).
    "GET /api/v1/auth/{provider}/start" to Case.Exempt(SIGN_IN),
    "GET /api/v1/auth/{provider}/callback" to Case.Exempt(CALLBACK),
    "POST /api/v1/auth/{provider}/link" to Case.Exempt(CALLBACK),
    "POST /api/v1/auth/email" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/email/verify" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/link" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/signup-grant" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/links" to Case.Own("makes a sign-in link for the caller"),
    "POST /api/v1/auth/launcher/login" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/mobile/token" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/mobile/unlock" to Case.Own("opens the caller's own biometric window"),
    "POST /api/v1/auth/device" to Case.Exempt(SIGN_IN),
    "GET /api/v1/auth/device" to Case.Exempt("reads a device authorization by its user code, the secret shown"),
    "POST /api/v1/auth/device/answer" to Case.Exempt("answers a device authorization by its user code"),
    "POST /api/v1/auth/device/token" to Case.Exempt(SIGN_IN),
    "POST /api/v1/auth/logout" to Case.Own("ends the caller's session"),
    "POST /api/v1/auth/logout/everywhere" to Case.Own("ends every session of the caller"),

    // The caller.
    "GET /api/v1/auth/me" to Case.Target(),
    "GET /api/v1/auth/me/account" to Case.Target(),
    "GET /api/v1/auth/me/audit" to Case.Target(),
    "GET /api/v1/auth/sessions" to Case.Target(),
    "DELETE /api/v1/auth/sessions/{id}" to target("id" to AUTH_SESSION),
    "GET /api/v1/me/organizations" to Case.Target(),
    "GET /api/v1/me/notifications" to Case.Target(),
    "PUT /api/v1/me/notifications" to Case.Own("the caller's own notification preferences"),
    "GET /api/v1/tokens" to Case.Target(),
    "POST /api/v1/tokens" to Case.Own("a personal token of the caller"),
    "DELETE /api/v1/tokens/{id}" to target("id" to TOKEN),
    "POST /api/v1/tokens/{id}/rotate" to target("id" to TOKEN),
    "GET /api/v1/devices" to Case.Target(),
    "DELETE /api/v1/devices/{id}" to target("id" to DEVICE),
    "POST /api/v1/push-devices" to Case.Own("registers the caller's phone"),
    "DELETE /api/v1/push-devices/{id}" to target("id" to PUSH_DEVICE),

    // Organizations, members, invitations, billing.
    "GET /api/v1/orgs" to Case.Target(),
    "POST /api/v1/orgs" to Case.Own("creates an organization owned by the caller"),
    "GET /api/v1/orgs/{org}" to target("org" to ORG_SLUG),
    "PATCH /api/v1/orgs/{org}" to target("org" to ORG_SLUG, body = """{"name": "Taken"}""", admin = true),
    "DELETE /api/v1/orgs/{org}" to target("org" to ORG_SLUG, body = """{"confirm": "{{ORG_SLUG}}"}""", admin = true),
    "POST /api/v1/orgs/{org}/transfer" to
        target("org" to ORG_SLUG, body = """{"login": "{{MEMBER_LOGIN}}"}""", admin = true),
    "POST /api/v1/orgs/{org}/leave" to target("org" to ORG_SLUG),
    "GET /api/v1/orgs/{org}/members" to target("org" to ORG_SLUG),
    "GET /api/v1/orgs/{org}/members/{login}" to target("org" to ORG_SLUG, "login" to MEMBER_LOGIN),
    "PATCH /api/v1/orgs/{org}/members/{login}" to
        target("org" to ORG_SLUG, "login" to MEMBER_LOGIN, body = """{"role": "admin"}""", admin = true),
    "DELETE /api/v1/orgs/{org}/members/{login}" to target("org" to ORG_SLUG, "login" to MEMBER_LOGIN, admin = true),
    "POST /api/v1/orgs/{org}/members/invites" to target(
        "org" to ORG_SLUG,
        body = """{"invites": [{"email": "taken@example.com", "role": "member"}]}""",
        admin = true,
    ),
    "GET /api/v1/orgs/{org}/invitations" to target("org" to ORG_SLUG),
    "POST /api/v1/orgs/{org}/invitations/links" to target("org" to ORG_SLUG, body = "{}", admin = true),
    "DELETE /api/v1/orgs/{org}/invitations/{id}" to target("org" to ORG_SLUG, "id" to INVITATION, admin = true),
    "GET /api/v1/orgs/{org}/teams" to target("org" to ORG_SLUG),
    "POST /api/v1/invitations/accept" to Case.Exempt(CAPABILITY),
    "POST /api/v1/invitations/preview" to Case.Exempt(CAPABILITY),
    "GET /api/v1/orgs/{orgId}/billing" to target("orgId" to ORG_ID),
    "GET /api/v1/orgs/{orgId}/billing/invoices" to target("orgId" to ORG_ID),
    "POST /api/v1/orgs/{orgId}/billing/checkout" to target("orgId" to ORG_ID, body = """{"plan": "team"}"""),
    "POST /api/v1/orgs/{orgId}/billing/portal" to target("orgId" to ORG_ID, body = "{}"),
    "POST /api/v1/orgs/{orgId}/billing/plan" to
        target("orgId" to ORG_ID, body = """{"plan": "team", "interval": "month"}"""),
    "POST /api/v1/orgs/{orgId}/billing/cancel" to target("orgId" to ORG_ID, body = "{}"),
    "POST /api/v1/orgs/{orgId}/billing/resume" to target("orgId" to ORG_ID, body = "{}"),
    "POST /api/v1/billing/stripe/webhook" to Case.Exempt(SIGNED),
    "POST /webhooks/stripe" to Case.Exempt(SIGNED),
    "GET /api/v1/audit" to Case.Target(),
    "GET /api/v1/service-keys" to Case.Target(),
    "POST /api/v1/service-keys" to
        Case.Target(body = """{"name": "Taken", "scopes": ["rules:read"], "expiresInDays": 1}""", admin = true),
    "DELETE /api/v1/service-keys/{id}" to target("id" to SERVICE_KEY, admin = true),

    // Groups (sharing).
    "GET /api/v1/groups" to Case.Target(),
    "POST /api/v1/groups" to Case.Target(
        body = """{"name": "Taken", "members": ["{{MEMBER_LOGIN}}"], "accounts": ["{{ACCOUNT}}"]}""",
        admin = true,
    ),
    "GET /api/v1/groups/{id}" to target("id" to GROUP),
    "PATCH /api/v1/groups/{id}" to target("id" to GROUP, body = """{"accounts": []}""", admin = true),
    "DELETE /api/v1/groups/{id}" to target("id" to GROUP, admin = true),
    "PUT /api/v1/groups/{id}/members/{login}" to target("id" to GROUP, "login" to MEMBER_LOGIN, admin = true),
    "DELETE /api/v1/groups/{id}/members/{login}" to target("id" to GROUP, "login" to MEMBER_LOGIN, admin = true),
    "POST /api/v1/groups/{id}/members/{login}/pause" to
        target("id" to GROUP, "login" to MEMBER_LOGIN, body = """{"until": "2099-01-01T00:00:00Z"}""", admin = true),

    // Shared accounts, inference.
    "GET /api/v1/accounts" to Case.Target(),
    "POST /api/v1/accounts" to Case.Target(
        body = """{"provider": "claude", "label": "Taken", "credentialSecret": "{{SECRET}}",""" +
            """ "sharedWith": ["{{MEMBER_LOGIN}}"]}""",
    ),
    "PATCH /api/v1/accounts/{id}" to target("id" to ACCOUNT, body = """{"label": "Taken"}"""),
    "DELETE /api/v1/accounts/{id}" to target("id" to ACCOUNT),
    "POST /api/v1/accounts/{id}/handover" to target("id" to ACCOUNT),
    "POST /api/v1/accounts/{id}/access-only" to target("id" to ACCOUNT),
    "GET /api/v1/capacity" to Case.Target(),
    "GET /api/v1/policy/models" to Case.Target(),
    "PUT /api/v1/policy/models" to Case.Target(body = """{"default": ["claude-sonnet-4-5"]}""", admin = true),
    "GET /api/v1/inference/queue" to Case.Target(),
    // A cancel names the caller's own call id, never a resource: the same id in both requests.
    "POST /api/v1/inference/requests/{id}/cancel" to
        Case.Target(mapOf("id" to Param.Fixed("6f1c2b0e-9a4d-4c3e-8b7a-2d5e1f0a9c41"))),
    "GET /api/v1/inference/settings" to Case.Target(),
    "PUT /api/v1/inference/settings" to Case.Target(body = "{}", admin = true),
    "GET /api/v1/inference/v1/models" to Case.Target(),
    "POST /api/v1/inference/v1/messages" to Case.Target(),
    "POST /api/v1/inference/v1/responses" to Case.Target(),
    "POST /api/v1/inference/v1/chat/completions" to Case.Target(),

    // Usage and spending.
    "GET /api/v1/usage" to Case.Target(),
    "GET /api/v1/usage/summary" to Case.Target(),
    "GET /api/v1/usage/live" to Case.Target(stream = true),
    "GET /api/v1/usage/events" to Case.Target(),
    "POST /api/v1/usage/events" to Case.Target(body = "{}"),
    "GET /api/v1/spending" to Case.Target(),
    "GET /api/v1/activity" to Case.Target(),
    "GET /api/v1/budgets" to Case.Target(),
    "GET /api/v1/budgets/me" to Case.Target(),
    "PUT /api/v1/budgets" to Case.Target(
        body = """{"scope": "member", "subject": "{{MEMBER_ID}}", "usdPerMonth": 1.0}""",
        admin = true,
    ),

    // Secrets.
    "GET /api/v1/secrets" to Case.Target(),
    "POST /api/v1/secrets" to Case.Target(body = """{"name": "TAKEN", "value": "v", "scope": "org"}"""),
    "PUT /api/v1/secrets/{name}" to target("name" to SECRET, body = """{"value": "taken", "scope": "org"}"""),
    "DELETE /api/v1/secrets/{name}" to target("name" to SECRET),

    // MCP relay.
    "GET /api/v1/mcp/servers" to Case.Target(),
    "POST /api/v1/mcp/servers" to Case.Target(body = "{}", admin = true),
    "PUT /api/v1/mcp/servers/{name}" to target(
        "name" to MCP_SERVER,
        body = """{"name": "{{MCP_SERVER}}", "url": "https://mcp.example.com/x", "transport": "http"}""",
        admin = true,
    ),
    "DELETE /api/v1/mcp/servers/{name}" to target("name" to MCP_SERVER, admin = true),
    "GET /api/v1/mcp/servers/{name}/assignments" to target("name" to MCP_SERVER),
    "PUT /api/v1/mcp/servers/{name}/assignments/{member}" to
        target("name" to MCP_SERVER, "member" to MEMBER_ID, body = """{"tools": ["send"]}""", admin = true),
    "DELETE /api/v1/mcp/servers/{name}/assignments/{member}" to
        target("name" to MCP_SERVER, "member" to MEMBER_ID, admin = true),
    "GET /api/v1/mcp/servers/{name}/policies" to target("name" to MCP_SERVER),
    "POST /api/v1/mcp/servers/{name}/policies" to target(
        "name" to MCP_SERVER,
        body = """{"tool": "query", "conditions": [], "effect": "deny", "message": "No."}""",
        admin = true,
    ),
    "DELETE /api/v1/mcp/servers/{name}/policies/{policyId}" to
        target("name" to MCP_SERVER, "policyId" to MCP_POLICY, admin = true),
    "GET /api/v1/mcp/settings" to Case.Target(),
    "PUT /api/v1/mcp/settings" to Case.Target(body = "{}", admin = true),
    "GET /api/v1/mcp/calls" to Case.Target(),
    "GET /api/v1/mcp/usage" to Case.Target(),
    "GET /api/v1/mcp/{server}" to target("server" to MCP_SERVER),
    "DELETE /api/v1/mcp/{server}" to target("server" to MCP_SERVER),
    "POST /api/v1/mcp/{server}" to target(
        "server" to MCP_SERVER,
        body = """{"jsonrpc": "2.0", "id": 1, "method": "tools/list"}""",
        rpc = true,
    ),

    // Artifacts.
    "GET /api/v1/artifacts" to Case.Target(),
    "POST /api/v1/artifacts" to Case.Target(
        body = """{"title": "Taken", "access": "team", "files": [{"path": "index.html", "content": "x"}]}""",
    ),
    "POST /api/v1/artifacts/mcp" to Case.Target(body = mcpCall("read", "artifact"), rpc = true),
    // The organization's bucket and artifact settings: an owner or an admin, signed in; the policy, any member.
    "GET /api/v1/artifacts/storage" to Case.Target(admin = true),
    "PUT /api/v1/artifacts/storage" to Case.Target(body = "{}", admin = true),
    "DELETE /api/v1/artifacts/storage" to Case.Target(admin = true),
    "POST /api/v1/artifacts/storage/test" to Case.Target(admin = true),
    "GET /api/v1/artifacts/settings" to Case.Target(admin = true),
    "PUT /api/v1/artifacts/settings" to Case.Target(body = "{}", admin = true),
    "GET /api/v1/artifacts/policy" to Case.Target(),
    "GET /api/v1/artifacts/{id}" to target("id" to ARTIFACT),
    "PATCH /api/v1/artifacts/{id}" to target("id" to ARTIFACT, body = """{"title": "Taken"}"""),
    "DELETE /api/v1/artifacts/{id}" to target("id" to ARTIFACT),
    "GET /api/v1/artifacts/{id}/versions" to target("id" to ARTIFACT),
    "POST /api/v1/artifacts/{id}/versions" to
        target("id" to ARTIFACT, body = """{"files": [{"path": "index.html", "content": "taken"}]}"""),
    "GET /api/v1/artifacts/{id}/versions/{number}/file" to
        Case.Target(mapOf("id" to of(ARTIFACT), "number" to Param.Fixed("1")), query = "path=index.html"),
    "GET /api/v1/artifacts/{id}/members" to target("id" to ARTIFACT),
    "PUT /api/v1/artifacts/{id}/members/{login}" to target("id" to ARTIFACT, "login" to MEMBER_ID),
    "DELETE /api/v1/artifacts/{id}/members/{login}" to target("id" to ARTIFACT, "login" to MEMBER_ID),
    "POST /api/v1/artifacts/{id}/tickets" to target("id" to ARTIFACT, body = "{}"),
    "GET /a/{id}/{ticket}/{...}" to Case.Target(
        mapOf("id" to of(ARTIFACT), "ticket" to Param.Fresh, "..." to Param.Fixed("index.html")),
        host = CONTENT_HOST,
    ),
    "GET /l/{slug}/{...}" to Case.Exempt(LINK),

    // Automations and waits.
    "GET /api/v1/automations" to Case.Target(),
    "POST /api/v1/automations" to Case.Target(body = AUTOMATION_BODY),
    "GET /api/v1/automations/{id}" to target("id" to AUTOMATION),
    "PUT /api/v1/automations/{id}" to target("id" to AUTOMATION, body = AUTOMATION_BODY),
    "DELETE /api/v1/automations/{id}" to target("id" to AUTOMATION),
    "POST /api/v1/automations/{id}/enable" to target("id" to AUTOMATION),
    "POST /api/v1/automations/{id}/disable" to target("id" to AUTOMATION),
    "POST /api/v1/automations/{id}/run" to target("id" to AUTOMATION, body = """{"payload": {}}"""),
    "GET /api/v1/automations/{id}/runs" to target("id" to AUTOMATION),
    "POST /api/v1/automations/{id}/webhook/rotate-secret" to target("id" to AUTOMATION),
    "POST /api/v1/automations/{id}/trigger" to target("id" to AUTOMATION, body = """{"ticket": {"number": 1}}"""),
    "POST /api/v1/automations/{id}/v/{version}/trigger" to Case.Target(
        mapOf("id" to of(AUTOMATION), "version" to Param.Fixed("1")),
        body = """{"ticket": {"number": 1}}""",
    ),
    "POST /api/v1/automations/{id}/runs/{runId}/cancel" to
        Case.Target(mapOf("id" to of(AUTOMATION), "runId" to Param.Fresh)),
    "GET /api/v1/waits" to Case.Target(),
    "POST /api/v1/waits" to
        Case.Target(body = """{"question": {"title": "Taken"}, "assignee": "{{MEMBER_ID}}"}"""),
    "POST /api/v1/waits/mcp" to
        Case.Target(body = mcpCall("get_wait", "wait"), query = "session={{SESSION}}", rpc = true),
    "POST /api/v1/waits/wake" to Case.Target(body = """{"waitMillis": 0}""", query = "session={{SESSION}}"),
    "GET /api/v1/waits/{id}" to target("id" to WAIT),
    "POST /api/v1/waits/{id}/answer" to target("id" to WAIT, body = """{"action": "approve"}"""),
    "POST /api/v1/waits/{id}/cancel" to target("id" to WAIT),
    "POST /api/v1/waits/{id}/event" to target("id" to WAIT, body = """{"result": "success"}"""),
    "GET /api/v1/notifications" to Case.Target(),
    "POST /api/v1/notifications" to Case.Target(
        body = """{"sessionId": "{{SESSION}}", "digest": "{{PACKAGE}}", "nodeId": "n", "notifyId": "n"}""",
    ),

    // Agents: launches, sessions, gates, approvals, receipts.
    "GET /api/v1/policy/agents" to Case.Target(),
    "POST /api/v1/launches" to Case.Target(body = """{"repo": "acme/api", "agent": "claude-code"}"""),
    "DELETE /api/v1/launches/{launchId}" to target("launchId" to LAUNCH),
    "POST /api/v1/launches/{launchId}/hooks-missing" to
        target("launchId" to LAUNCH, body = """{"waitedSeconds": 30, "stopped": false}"""),
    "GET /api/v1/sessions" to Case.Target(),
    "POST /api/v1/sessions" to
        Case.Target(body = """{"id": "{{SESSION}}", "agent": "claude-code", "repo": "acme/api"}"""),
    "GET /api/v1/sessions/{id}" to target("id" to SESSION),
    "POST /api/v1/sessions/{id}/alive" to target("id" to SESSION),
    "POST /api/v1/sessions/{id}/end" to target("id" to SESSION),
    "POST /api/v1/sessions/{id}/reopen" to target("id" to SESSION),
    "POST /api/v1/sessions/{id}/seen" to target("id" to SESSION),
    "POST /api/v1/sessions/{id}/workflow-receipt" to target("id" to SESSION, body = "{}"),
    "GET /api/v1/repos" to Case.Target(),
    "POST /api/v1/checks" to Case.Target(
        body = """{"sessionId": "{{SESSION}}", "check": "tests", "status": "passed"}""",
    ),
    "GET /api/v1/checks" to Case.Target(),
    "POST /api/v1/gates" to Case.Target(body = GATE_BODY),
    "GET /api/v1/gates/{id}" to target("id" to GATE),
    "POST /api/v1/gates/{id}/reserve" to target("id" to GATE),
    "POST /api/v1/gates/{id}/consume" to target("id" to GATE),
    "POST /api/v1/gates/{id}/release" to target("id" to GATE),
    "POST /api/v1/gates/local-denials" to Case.Target(body = LOCAL_DENIAL_BODY),
    "GET /api/v1/gates/policy" to Case.Target(),
    "PUT /api/v1/gates/policy" to Case.Target(body = """{"selfApproval": "never"}""", admin = true),
    "GET /api/v1/gates/catalog" to Case.Target(),
    "GET /api/v1/gates/catalog/signed" to Case.Target(),
    "GET /api/v1/gates/approvers" to Case.Target(),
    "GET /api/v1/approvals" to Case.Target(),
    "GET /api/v1/approvals/queue" to Case.Target(),
    "GET /api/v1/approvals/{id}" to target("id" to APPROVAL),
    "GET /api/v1/approvals/{id}/view" to target("id" to APPROVAL),
    "POST /api/v1/approvals/{id}/answer" to
        target("id" to APPROVAL, body = """{"answer": "approve", "shownDigest": "x"}"""),
    "GET /api/v1/confirmations/{id}" to target("id" to CONFIRMATION),
    "POST /api/v1/confirmations/{id}/answer" to target("id" to CONFIRMATION, body = """{"answer": "confirm"}"""),
    "GET /api/v1/receipts" to Case.Target(),
    "GET /api/v1/receipts/keys" to Case.Target(),
    "GET /api/v1/receipts/{id}" to target("id" to RECEIPT),
    "GET /api/v1/receipts/{id}/verify" to target("id" to RECEIPT),
    "GET /api/v1/receipts/{id}/bundle" to target("id" to RECEIPT),

    // Rules, decisions, analysis.
    "GET /api/v1/rules" to Case.Target(),
    "POST /api/v1/rules" to Case.Target(body = """{"statement": "Taken", "group": "Taken"}""", admin = true),
    "GET /api/v1/rules/{id}" to target("id" to RULE),
    "PATCH /api/v1/rules/{id}" to target("id" to RULE, body = """{"statement": "Taken"}""", admin = true),
    "DELETE /api/v1/rules/{id}" to target("id" to RULE, admin = true),
    "POST /api/v1/rules/bulk" to
        Case.Target(body = """{"ids": ["{{RULE}}"], "status": "rejected"}""", admin = true),
    "GET /api/v1/decisions" to Case.Target(),
    "GET /api/v1/analysis/state" to Case.Target(),
    "POST /api/v1/analysis/start" to Case.Target(body = "{}", admin = true),
    "POST /api/v1/analysis/restart" to Case.Target(body = "{}", admin = true),
    "GET /api/v1/analysis/replay" to Case.Target(),
    "POST /api/v1/analysis/replay" to Case.Target(body = """{"ruleIds": ["{{RULE}}"]}""", exclusive = true),
    "POST /api/v1/import/teamcode" to Case.Target(body = "{}", admin = true),

    // Workflows and packages (compiler).
    "GET /api/v1/workflows" to Case.Target(),
    "POST /api/v1/workflows" to Case.Target(body = "{}"),
    "GET /api/v1/workflows/{id}" to target("id" to WORKFLOW),
    "PUT /api/v1/workflows/{id}" to target("id" to WORKFLOW, body = """{"workflow": {}}"""),
    "DELETE /api/v1/workflows/{id}" to target("id" to WORKFLOW),
    "GET /api/v1/workflows/{id}/runs" to target("id" to WORKFLOW),
    "POST /api/v1/workflows/{id}/runs" to target("id" to WORKFLOW, body = "{}"),
    "GET /api/v1/runs" to Case.Target(),
    "GET /api/v1/runs/{id}" to target("id" to RUN),
    "POST /api/v1/runs/{id}/cancel" to target("id" to RUN, body = "{}"),
    "POST /api/v1/runs/{id}/deliver" to target("id" to RUN, body = """{"output":"x"}"""),
    "POST /api/v1/workflows/session/deliver" to
        Case.Target(body = """{"sessionId": "{{CLOUD_SESSION}}", "output": "x"}"""),
    "GET /api/v1/workflows/session/pending" to Case.Target(query = "sessionId={{CLOUD_SESSION}}"),
    "POST /api/v1/workflows/mcp" to Case.Target(
        body = """{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "deliver", """ +
            """"arguments": {"output": "x"}}}""",
        query = "session={{CLOUD_SESSION}}",
        rpc = true,
    ),
    "GET /api/v1/packages" to Case.Target(),
    "GET /api/v1/packages/current" to Case.Target(),
    "GET /api/v1/packages/audit" to Case.Target(),
    "POST /api/v1/packages/preview" to Case.Target(body = "{}"),
    "GET /api/v1/packages/{digest}" to target("digest" to PACKAGE),
    "GET /api/v1/packages/{digest}/status" to target("digest" to PACKAGE),
    "POST /api/v1/packages/{digest}/revoke" to target("digest" to PACKAGE, admin = true),
    "POST /api/v1/packages/{digest}/restore" to target("digest" to PACKAGE, admin = true),

    // Runners and cloud sessions.
    "GET /api/v1/runners" to Case.Target(),
    "POST /api/v1/runners" to
        Case.Target(body = """{"name": "taken", "labels": ["linux"], "version": "1.2.0"}""", admin = true),
    "DELETE /api/v1/runners/{id}" to target("id" to RUNNER, admin = true),
    "POST /api/v1/runners/{id}/heartbeat" to target("id" to RUNNER, body = "{}"),
    "GET /api/v1/cloud/sessions" to Case.Target(),
    "POST /api/v1/cloud/sessions" to Case.Target(body = "{}"),
    "GET /api/v1/cloud/sessions/{id}" to target("id" to CLOUD_SESSION),
    "DELETE /api/v1/cloud/sessions/{id}" to target("id" to CLOUD_SESSION),
    "POST /api/v1/cloud/sessions/{id}/pause" to target("id" to CLOUD_SESSION),
    "POST /api/v1/cloud/sessions/{id}/resume" to target("id" to CLOUD_SESSION),
    "PUT /api/v1/cloud/sessions/{id}/github" to target("id" to CLOUD_SESSION, body = """{"access": "shared"}"""),
    "POST /api/v1/cloud/sessions/{id}/terminal/ticket" to target("id" to CLOUD_SESSION),
    "GET /api/v1/cloud/sessions/{id}/terminal" to Case.Exempt(WEBSOCKET),
    "POST /api/v1/cloud/runner/heartbeat" to Case.Target(body = """{"sessionId": "{{CLOUD_SESSION}}", "epoch": 1}"""),
    "POST /api/v1/cloud/runner/github" to Case.Target(body = """{"sessionId": "{{CLOUD_SESSION}}", "epoch": 1}"""),
    "POST /api/v1/cloud/runner/launch" to Case.Target(body = """{"sessionId": "{{CLOUD_SESSION}}", "epoch": 1}"""),
    "POST /api/v1/cloud/runner/checks" to Case.Target(body = """{"sessionId": "{{CLOUD_SESSION}}", "epoch": 1}"""),
    "GET /api/v1/cloud/runner/terminal" to Case.Exempt(WEBSOCKET),

    // GitHub App.
    "GET /api/v1/github/app" to Case.Target(),
    "GET /api/v1/github/app/install/start" to Case.Target(),
    // A session's todo (todos.v1): every route names B's session, in its query or its body; B's item is `plan`.
    "GET /api/v1/todos/session" to Case.Target(query = "sessionId={{SESSION}}"),
    "POST /api/v1/todos/session" to Case.Target(body = TODO_BODY),
    "POST /api/v1/todos/session/items" to Case.Target(
        body = """{"sessionId": "{{SESSION}}", "item": $TODO_ITEM, "reason": "Taken"}""",
    ),
    "POST /api/v1/todos/session/items/{item}/start" to todoItem("""{"sessionId": "{{SESSION}}"}"""),
    "POST /api/v1/todos/session/items/{item}/notes" to todoItem("""{"sessionId": "{{SESSION}}", "text": "Taken"}"""),
    "POST /api/v1/todos/session/items/{item}/proof" to todoItem(
        """{"sessionId": "{{SESSION}}", "checks": [{"index": 0, "passed": true, "observed": "Taken"}]}""",
    ),
    "POST /api/v1/todos/session/items/{item}/link" to todoItem(
        """{"sessionId": "{{SESSION}}", "kind": "pr", "ref": "https://github.com/acme/api/pull/1"}""",
    ),
    "POST /api/v1/todos/session/items/{item}/drop" to todoItem("""{"sessionId": "{{SESSION}}", "reason": "Taken"}"""),
    "GET /api/v1/todos/session/context" to Case.Target(query = "sessionId={{SESSION}}"),
    "POST /api/v1/todos/session/stop" to Case.Target(body = """{"sessionId": "{{SESSION}}", "phase": "begin"}"""),
    "POST /api/v1/todos/session/hook-error" to Case.Target(body = """{"sessionId": "{{SESSION}}", "text": "Taken"}"""),
    "POST /api/v1/todos/session/reset" to Case.Target(body = """{"sessionId": "{{SESSION}}"}"""),
    "GET /api/v1/todos/stop-hook" to Case.Target(),
    "PUT /api/v1/todos/stop-hook/members/{memberId}" to
        target("memberId" to MEMBER_ID, body = """{"enabled": false}""", admin = true),
    "GET /api/v1/github/app/install/callback" to Case.Exempt(CALLBACK),
    "GET /api/v1/github/app/manifest/start" to Case.Target(),
    "GET /api/v1/github/app/manifest/callback" to Case.Exempt(CALLBACK),
    // The members' GitHub links: the caller's own, or another member's for an owner or an admin.
    "GET /api/v1/github/link" to Case.Target(),
    "DELETE /api/v1/github/link" to Case.Target(),
    "POST /api/v1/github/link/device" to Case.Target(),
    "POST /api/v1/github/link/device/{id}" to Case.Target(mapOf("id" to Param.Fresh)),
    "GET /api/v1/github/link/start" to Case.Target(),
    "GET /api/v1/github/link/callback" to Case.Exempt(LINK_CALLBACK),
    "GET /api/v1/github/links" to Case.Target(),
    "GET /api/v1/github/links/{memberId}" to target("memberId" to MEMBER_ID),
    // The seeded member is the simple member too: unlinking oneself is theirs to do, unlinking another an admin's.
    "DELETE /api/v1/github/links/{memberId}" to target("memberId" to MEMBER_ID),
    "GET /api/v1/github/links/{memberId}/start" to target("memberId" to MEMBER_ID, admin = true),
    "POST /webhooks/github" to Case.Exempt(SIGNED),
)

private const val AUTOMATION_BODY = """{"name": "Taken", "trigger": {"kind": "webhook"},""" +
    """ "action": {"prompt": "Fix"}, "variables": [], "overlap": "allow"}"""

private const val GATE_BODY = """{"sessionId": "{{SESSION}}", "repo": "acme/api", "class": "push-main",""" +
    """ "tool": "Bash", "command": "deploy"}"""

// The hook's report of a refusal on the workstation (audit A6), naming B's session: answered as for no session.
private const val LOCAL_DENIAL_BODY = """{"sessionId": "{{SESSION}}", "repo": "acme/api", "class": "push-main",""" +
    """ "reason": "redirected", "command": "git push origin main"}"""

// An item of a todo: a complete description and one typed check.
private const val TODO_ITEM = """{"id": "plan", "name": "Plan", "description": "Write the plan of the change:""" +
    """ what, where, why and what is out of scope, for a new agent.", "doneWhen": {"text": "Merged",""" +
    """ "checks": [{"type": "pr_merged", "repo": "acme/api"}]}}"""

private const val TODO_BODY = """{"sessionId": "{{SESSION}}", "reference": "Taken", "items": [$TODO_ITEM]}"""

// A route of B's todo item `plan` (the item id is a slug of the list, not a secret), naming B's session in [body].
private fun todoItem(body: String): Case = Case.Target(mapOf("item" to Param.Fixed("plan")), body)

// A JSON-RPC call of the MCP tool [tool], its one argument [argument] naming B's seed of the same name.
private fun mcpCall(tool: String, argument: String): String {
    val seed = argument.uppercase()
    return """{"jsonrpc": "2.0", "id": 1, "method": "tools/call", """ +
        """"params": {"name": "$tool", "arguments": {"$argument": "{{$seed}}"}}}"""
}
