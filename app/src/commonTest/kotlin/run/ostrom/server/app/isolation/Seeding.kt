// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app.isolation

import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.testing.TestServer
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRecorder
import io.ktor.client.request.bearerAuth
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Seeds [party] with one resource of every kind, as its people and tokens make them, every name carrying its marker.
 *
 * @param spends Whether the organization spends ([Party.SPENT_MICROS]): B does, A does not, so a total of A that
 *   counts B shows B's figure.
 */
internal suspend fun TestServer.seed(party: Party, spends: Boolean) {
    val seeder = Seeder(this, party)
    seeder.agents(party)
    seeder.sharing(party)
    seeder.content(party)
    seeder.governance(party)
    seeder.gate(party)
    seeder.administration(party)
    cloudSession(party)
    if (spends) spend(party)
}

private fun Party.cli() = Credential("cli token") { bearerAuth(tokens.getValue(TokenKind.CLI)) }

// The launch, its agent token and the agent's session, then what the agent reports.
private suspend fun Seeder.agents(party: Party) {
    val body = """{"repo": "acme/api", "agent": "claude-code"}"""
    val launch = send("launch", "POST /api/v1/launches", party.cli(), body)
    keep(Seed.LAUNCH, launch, "launchId")
    party.agentToken = launch?.at("agentToken")
    val agent = party.credentials.firstOrNull { it.name == "agent token" } ?: return problem("agent", "no agent token")
    val session = "${party.marker}-s1"
    val opened = send(
        "session",
        "POST /api/v1/sessions",
        agent,
        """{"id": "$session", "agent": "claude-code", "repo": "acme/api"}""",
        expected = OK,
    )
    keep(Seed.SESSION, session, opened)
    val todo = """{"sessionId": "$session", "reference": "${party.marker} plan", "items": [{"id": "plan",""" +
        """ "name": "${party.marker} plan", "description": "Write the plan of ${party.marker}: what, where, why""" +
        """ and what is out of scope, for a new agent.", "doneWhen": {"text": "Merged",""" +
        """ "checks": [{"type": "pr_merged", "repo": "acme/api"}]}}]}"""
    send("todo", "POST /api/v1/todos/session", agent, todo)
    val event = """{"kind": "skill", "name": "${party.marker}-skill", "sessionId": "$session", "durationMillis": 1}"""
    send("usage event", "POST /api/v1/usage/events", agent, """{"events": [$event]}""", expected = ACCEPTED)
}

// A secret, the account it enrolls, the group lending it, the MCP server and what restricts it.
private suspend fun Seeder.sharing(party: Party) {
    val owner = party.browser
    val name = "${party.marker}-secret"
    val secret = send(
        "secret",
        "POST /api/v1/secrets",
        owner,
        """{"name": "$name", "value": "sk-ant-oat01-${party.marker}", "scope": "org"}""",
    )
    keep(Seed.SECRET, name, secret)
    val account = send(
        "account",
        "POST /api/v1/accounts",
        owner,
        """{"provider": "anthropic", "label": "${party.marker} claude", "credentialSecret": "$name",""" +
            """ "sharedWith": ["${party.member.user.login}"]}""",
    )
    val accountId = keep(Seed.ACCOUNT, account, "id")
    val group = send(
        "group",
        "POST /api/v1/groups",
        owner,
        """{"name": "${party.marker} group", "members": ["${party.member.user.login}"],""" +
            """ "accounts": [${accountId?.let { "\"$it\"" }.orEmpty()}]}""",
    )
    keep(Seed.GROUP, group, "id")
    val server = "${party.marker}-mcp"
    val relay = send(
        "MCP server",
        "PUT /api/v1/mcp/servers/$server",
        owner,
        """{"name": "$server", "url": "https://mcp.example.com/x", "transport": "http"}""",
        expected = OK,
    )
    keep(Seed.MCP_SERVER, server, relay)
    send(
        "MCP assignment",
        "PUT /api/v1/mcp/servers/$server/assignments/${party.member.memberId}",
        owner,
        """{"tools": ["send"]}""",
        expected = OK,
    )
    val policy = send(
        "MCP policy",
        "POST /api/v1/mcp/servers/$server/policies",
        owner,
        """{"tool": "query", "conditions": [], "effect": "deny", "message": "${party.marker} says no."}""",
    )
    keep(Seed.MCP_POLICY, policy, "id")
}

// A team artifact shared with the member, a link artifact, an automation and a wait.
private suspend fun Seeder.content(party: Party) {
    val owner = party.browser
    val team = send("team artifact", "POST /api/v1/artifacts", owner, artifact(party, "team"))
    val id = keep(Seed.ARTIFACT, team, "id")
    if (id != null) {
        val share = "PUT /api/v1/artifacts/$id/members/${party.member.memberId}"
        send("artifact share", share, owner, expected = NO_CONTENT)
    }
    val link = send("link artifact", "POST /api/v1/artifacts", owner, artifact(party, "link"))
    val url = link?.at("publicUrl")
    if (link != null) {
        url?.substringAfter("/l/")?.substringBefore('/')?.let { party.values[Seed.LINK_SLUG] = it }
            ?: problem("link artifact", "no publicUrl in $link")
    }
    val automation = send(
        "automation",
        "POST /api/v1/automations",
        owner,
        """{"name": "${party.marker} hook", "trigger": {"kind": "webhook"}, "action": {"prompt": "Fix {{ticket}}"},""" +
            """ "variables": [{"name": "ticket", "path": "$.ticket.number"}], "overlap": "allow"}""",
    )
    keep(Seed.AUTOMATION, automation, "id")
    val wait = send("wait", "POST /api/v1/waits", owner, """{"question": {"title": "${party.marker} ship it?"}}""")
    keep(Seed.WAIT, wait, "id")
}

private fun artifact(party: Party, access: String) =
    """{"title": "${party.marker} $access doc", "access": "$access",""" +
        """ "files": [{"path": "index.html", "content": "<h1>${party.marker}</h1>"}]}"""

// A rule, a rule held for a confirmation, the accepted workflow, a run of it and its package.
private suspend fun Seeder.governance(party: Party) {
    val owner = party.browser
    val rule = """{"statement": "${party.marker} ships on Fridays", "group": "${party.marker}"}"""
    keep(Seed.RULE, send("rule", "POST /api/v1/rules", owner, rule), "id")
    keep(Seed.CONFIRMATION, send("confirmation", "POST /api/v1/rules", party.cli(), rule, ACCEPTED), "confirmationId")
    val workflow = "${party.marker}-wf"
    val posted = send(
        "workflow",
        "POST /api/v1/workflows",
        owner,
        """{"id": "$workflow", "name": "${party.marker} workflow", "domain": "delivery", "status": "accepted",""" +
            """ "foundAt": "2026-11-02T17:00:00Z", "edges": [], "nodes": [{"id": "push-main", "kind": "gate",""" +
            """ "label": "Push to main", "approvers": ["${party.owner.user.login}"], "actions": [{"class":""" +
            """ "push-main", "label": "Push to the main branch", "patterns": [], "repos": ["acme/api"]}]}]}""",
    )
    keep(Seed.WORKFLOW, workflow, posted)
    // A run of it, cancelled at once: it stays as it is between the snapshots of B's reads (the engine plays no
    // cancelled run), and the routes that name a run have one of B's to aim at.
    val run = keep(Seed.RUN, send("run", "POST /api/v1/workflows/$workflow/runs", owner, "{}"), "id")
    if (run != null) send("run cancelled", "POST /api/v1/runs/$run/cancel", owner, "{}", expected = OK)
    keep(Seed.PACKAGE, send("package", "GET /api/v1/packages/current?repo=acme/api", owner, expected = OK), "digest")
}

// The agent asks a gate the workflow guards, the owner reads the approval and says no: a signed receipt.
private suspend fun Seeder.gate(party: Party) {
    val agent = party.credentials.firstOrNull { it.name == "agent token" }
    val session = party.values[Seed.SESSION]
    if (agent == null || session == null) return
    val asked = send(
        "gate",
        "POST /api/v1/gates",
        agent,
        """{"sessionId": "$session", "repo": "acme/api", "class": "push-main", "tool": "Bash",""" +
            """ "command": "deploy"}""",
        expected = OK,
    )
    keep(Seed.GATE, asked, "gate.id")
    val inbox = send("approval", "GET /api/v1/approvals?mine=true&status=pending", party.browser, expected = OK)
    val approval = keep(Seed.APPROVAL, inbox, "items.0.id")
    if (approval != null) {
        val answered = send(
            "receipt",
            "POST /api/v1/approvals/$approval/answer",
            party.browser,
            """{"answer": "reject", "shownDigest": "${inbox?.at("items.0.shownDigest")}"}""",
            expected = OK,
        )
        keep(Seed.RECEIPT, answered, "receipt.id")
    }
}

// A runner, an invitation link, a service key, a personal token and a phone of the owner.
private suspend fun Seeder.administration(party: Party) {
    val owner = party.browser
    val runner = """{"name": "${party.marker}-runner", "labels": ["linux"], "version": "1.0.0"}"""
    keep(Seed.RUNNER, send("runner", "POST /api/v1/runners", owner, runner), "id")
    val invitation = send("invitation", "POST /api/v1/orgs/${party.org.slug}/invitations/links", owner, "{}")
    keep(Seed.INVITATION, invitation, "invitation.id")
    val key = """{"name": "${party.marker} CI", "scopes": ["sessions:write"], "expiresInDays": 30}"""
    keep(Seed.SERVICE_KEY, send("service key", "POST /api/v1/service-keys", owner, key), "id")
    val token = """{"name": "${party.marker} script", "scopes": ["profile:read"], "expiresInDays": 1}"""
    keep(Seed.TOKEN, send("personal token", "POST /api/v1/tokens", owner, token), "token.id")
    val phone = Credential("mobile token") { bearerAuth(party.tokens.getValue(TokenKind.MOBILE)) }
    val device = send(
        "push device",
        "POST /api/v1/push-devices",
        phone,
        """{"pushToken": "ExponentPushToken[${party.marker}]", "provider": "expo", "platform": "ios",""" +
            """ "locale": "fr"}""",
    )
    keep(Seed.PUSH_DEVICE, device, "id")
}

// A cloud session, written as the runners tests write it: the API keeps them closed without a runtime.
private suspend fun TestServer.cloudSession(party: Party) {
    val now = Clock.System.now()
    val session = CloudSession(
        id = Uuid.random(), orgId = party.org.id, ownerId = "${party.owner.memberId}", name = "${party.marker}-task",
        agent = AgentKind.CLAUDE, repository = null, state = SessionState.CREATING,
        desiredState = DesiredState.RUNNING, phase = null, epoch = 0, generation = 0, failure = null,
        limits = CloudPlan.FREE.sessionLimits(), createdAt = now, updatedAt = now,
    )
    if (koin.get<CloudSessionsRepository>().createWithinCap(session, CLOUD_CAP)) {
        party.values[Seed.CLOUD_SESSION] = "${session.id}"
    } else {
        party.problems += "seed ${party.marker} cloud session: over the cap"
    }
}

// What the owner spends on a model, written as the gateway records it: client events always cost nothing.
private suspend fun TestServer.spend(party: Party) {
    val event = UsageEvent(
        Uuid.random(), party.org.id, party.owner.memberId, UsageKind.MODEL, "${party.marker}-model",
        costMicros = Party.SPENT_MICROS, at = Clock.System.now(),
    )
    koin.get<UsageRecorder>().record(event)
}

private const val OK = 200
private const val ACCEPTED = 202
private const val NO_CONTENT = 204
private const val CLOUD_CAP = 5

/**
 * A second launch of [party]'s organization, its session and a gate it asks: what the first launch's agent token must
 * not reach (an agent token acts on its own launch only, CONVENTIONS.md section 16).
 *
 * @return The second launch's values, by kind; empty when it could not be made (a problem says why).
 */
internal suspend fun TestServer.anotherLaunch(party: Party): Map<Seed, String> {
    val seeder = Seeder(this, party)
    val body = """{"repo": "acme/api", "agent": "claude-code"}"""
    val launch = seeder.send("second launch", "POST /api/v1/launches", party.cli(), body)
    val agent = launch?.at("agentToken")?.let { token -> Credential("second agent token") { bearerAuth(token) } }
    val session = "${party.marker}-s2"
    val opening = """{"id": "$session", "agent": "claude-code", "repo": "acme/api"}"""
    val opened = agent?.let { seeder.send("second session", "POST /api/v1/sessions", it, opening, OK) }
    val gate = """{"sessionId": "$session", "repo": "acme/api", "class": "push-main", "tool": "Bash", "command": "x"}"""
    val asked = agent?.takeIf { opened != null }?.let { seeder.send("second gate", "POST /api/v1/gates", it, gate, OK) }
    return listOf(
        Seed.LAUNCH to launch?.at("launchId"),
        Seed.SESSION to session.takeIf { opened != null },
        Seed.GATE to asked?.at("gate.id"),
    ).mapNotNull { (seed, value) -> value?.let { seed to it } }.toMap()
}
