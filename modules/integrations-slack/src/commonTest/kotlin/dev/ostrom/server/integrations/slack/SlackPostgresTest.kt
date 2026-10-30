// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.presentation.ApprovalsPage
import dev.ostrom.server.gates.presentation.GateAskRequest
import dev.ostrom.server.gates.presentation.GatePush
import dev.ostrom.server.gates.presentation.GatePushRef
import dev.ostrom.server.gates.presentation.GateResponse
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.integrations.slack.domain.SlackApi
import dev.ostrom.server.integrations.slack.domain.SlackMessageRef
import dev.ostrom.server.integrations.slack.domain.SlackMessages
import dev.ostrom.server.integrations.slack.domain.SlackRepository
import dev.ostrom.server.integrations.slack.infrastructure.SlackSettings
import dev.ostrom.server.integrations.slack.presentation.SlackLinkDto
import dev.ostrom.server.integrations.slack.presentation.SlackLinkRequest
import dev.ostrom.server.integrations.slack.presentation.SlackLinksPage
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Slack against a real PostgreSQL and real HTTP: links, a yes from a button, S2-31, and the isolation of orgs. */
class SlackPostgresTest {
    private val secret = "slack-signing-test"
    private val slack = FakeSlackApi()

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:owner` (a browser session), `member:a:dev`, `reader:a:ops`,
    // `agent:a:<session>` (an agent session asking for gates).
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user", "member" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.ALL_USER))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(GateScopes.AGENT_SESSION))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(GateScopes.READ))
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    return if (kind == "agent") {
                        GateActor("agent", orgOf(org), ActorKind.AGENT_SESSION, launchId = id, personId = "lyes")
                    } else {
                        GateActor(id, orgOf(org), ActorKind.USER, personId = id)
                    }
                }
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
                    val (kind, org, _) = principal.subjectId.split(":")
                    val role = if (kind == "user") OrgRole.OWNER else OrgRole.MEMBER
                    return OrgPrincipal(orgOf(org), Uuid.random(), role).takeIf { orgId == null || it.orgId == orgId }
                }
            }
        }
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) = Unit
            }
        }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("lyes", "rev-b", "rev-c"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
        single { SlackSettings("xoxb-test", secret, webOrigin = "https://console.example") }
        single<SlackApi> { slack }
    }

    private fun orgOf(letter: String) = if (letter == "a") ORG else OTHER_ORG

    private fun slackTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule, SlackIntegrationModule), overrides) { server -> block(server) }

    private suspend fun TestServer.links(token: String?, person: String? = null, body: SlackLinkRequest? = null) =
        client.put("/api/v1/slack/links/${person.orEmpty()}") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body ?: SlackLinkRequest("T0ACME", "U0REVB"))
        }

    private suspend fun TestServer.list(token: String?): HttpResponse = client.get("/api/v1/slack/links") {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun TestServer.unlink(token: String, person: String): HttpResponse =
        client.delete("/api/v1/slack/links/$person") { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun TestServer.ask(diff: String, force: Boolean = false): GateResponse =
        client.post("/api/v1/gates") {
            header(HttpHeaders.Authorization, "Bearer agent:a:claude-5e5")
            contentType(ContentType.Application.Json)
            setBody(
                GateAskRequest(
                    sessionId = "claude-5e5",
                    repo = "acme/api",
                    cls = "push-main",
                    command = "git push origin main",
                    dev = "lyes",
                    push = GatePush("git@github.com:acme/api.git", listOf(GatePushRef("refs/heads/main", SHA)), force),
                    diff = diff,
                ),
            )
        }.body()

    private suspend fun TestServer.gate(id: String): GateResponse =
        client.get("/api/v1/gates/$id") { header(HttpHeaders.Authorization, "Bearer user:a:rev-b") }.body()

    // A click on a button, signed as Slack signs it unless [signature] says otherwise.
    private suspend fun TestServer.click(
        value: String,
        action: String = SlackMessages.YES,
        user: String = "U0REVB",
        signature: String? = null,
    ): HttpResponse {
        val payload = """{"type":"block_actions","team":{"id":"T0ACME"},"user":{"id":"$user"},""" +
            """"actions":[{"action_id":"$action","value":"$value"}],""" +
            """"container":{"channel_id":"D-U0REVB","message_ts":"ts-1"}}"""
        val body = "payload=" + payload.encodeURLParameter()
        val ts = Clock.System.now().epochSeconds.toString()
        return client.post("/webhooks/slack") {
            header("X-Slack-Request-Timestamp", ts)
            header("X-Slack-Signature", signature ?: slackSign(secret, ts, body.encodeToByteArray()))
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
    }

    // The value of the last request's yes button: `<request id>:<shown digest>`, or null without buttons.
    private fun lastButton(): String? = slack.posted.last().second.blocks.map { it.jsonObject }
        .firstOrNull { it["type"]!!.jsonPrimitive.content == "actions" }
        ?.let { it["elements"]!!.jsonArray.first().jsonObject["value"]!!.jsonPrimitive.content }

    @Test
    fun aSmallPushIsApprovedFromSlackByTheLinkedApprover() = slackTest { server ->
        assertEquals(HttpStatusCode.OK, server.links("user:a:owner", "rev-b").status)
        val opened = server.ask("+ one line\n- another")
        assertEquals(listOf("D-U0REVB"), slack.posted.map { it.first }, "only the linked approver hears of it")
        val value = assertNotNullValue(lastButton())

        val forged = server.click(value, signature = "v0=00")
        assertEquals(HttpStatusCode.Unauthorized, forged.status, "an unsigned click answers nothing")
        assertEquals("pending", server.gate(opened.gate.id).gate.status)

        assertEquals(HttpStatusCode.OK, server.click(value, user = "U0NOBODY").status)
        assertEquals("pending", server.gate(opened.gate.id).gate.status, "a stranger's click answers nothing")

        assertEquals(HttpStatusCode.OK, server.click(value).status)
        assertEquals("approved", server.gate(opened.gate.id).gate.status)
        assertTrue(slack.updated.any { it.third.text == "Merge into main: approved by rev-b" })

        assertEquals(HttpStatusCode.OK, server.click(value, action = SlackMessages.NO).status, "clicked twice")
        assertEquals("approved", server.gate(opened.gate.id).gate.status, "a yes is consumed once")
        assertTrue(slack.updated.last().third.text.startsWith("Ostrom did not take this answer (conflict)."))
        assertEquals(HttpStatusCode.OK, server.click("not-a-request").status, "any other interaction is ignored")
    }

    @Test
    fun s231ALargeOrForcedPushIsNeverApprovedFromSlackEvenWithAForgedButton() = slackTest { server ->
        server.links("user:a:owner", "rev-b")
        for (gate in listOf(server.ask("+x\n".repeat(16)), server.ask("+x", force = true))) {
            assertEquals(null, lastButton(), "no button is offered")
            val request = server.client.get("/api/v1/approvals?limit=100") {
                header(HttpHeaders.Authorization, "Bearer user:a:rev-b")
            }.body<ApprovalsPage>().items.first { it.group == gate.gate.id && it.requestedFrom == "rev-b" }
            assertEquals(HttpStatusCode.OK, server.click("${request.id}:${request.shownDigest}").status)
            assertEquals("pending", server.gate(gate.gate.id).gate.status, "answered in Ostrom only")
            assertTrue(slack.updated.last().third.text.startsWith("This gate is answered in Ostrom"))
        }
    }

    @Test
    fun onlyAnOwnerOrAnAdminLinksMembersFromABrowserSession() = slackTest { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.list(null).status)
        assertEquals(HttpStatusCode.Unauthorized, server.links(null, "rev-b").status)
        assertEquals(HttpStatusCode.Forbidden, server.list("member:a:dev").status, "a member")
        assertEquals(HttpStatusCode.Forbidden, server.links("member:a:dev", "rev-b").status, "a member")
        assertEquals(HttpStatusCode.Forbidden, server.list("reader:a:ops").status, "no browser session")
        assertEquals(HttpStatusCode.Forbidden, server.list("agent:a:claude").status, "an agent")
        assertEquals(HttpStatusCode.Forbidden, server.list("user:a").status, "no organization")

        val saved = server.links("user:a:owner", "rev-b").body<SlackLinkDto>()
        val shown = listOf(saved.personId, saved.teamId, saved.userId, saved.linkedBy)
        assertEquals(listOf("rev-b", "T0ACME", "U0REVB", "owner"), shown)
        val bad = server.links("user:a:owner", "rev-c", SlackLinkRequest("acme", "U0REVC"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val twice = server.links("user:a:owner", "rev-c")
        assertEquals(HttpStatusCode.Conflict, twice.status, "one Slack account, one member")
        val moved = server.links("user:a:owner", "rev-b", SlackLinkRequest("T0ACME", "U0NEW"))
        assertEquals(HttpStatusCode.OK, moved.status)
        assertEquals(listOf("U0NEW"), server.list("user:a:owner").body<SlackLinksPage>().items.map { it.userId })
        assertEquals(HttpStatusCode.NoContent, server.unlink("user:a:owner", "rev-b").status)
        assertEquals(HttpStatusCode.NotFound, server.unlink("user:a:owner", "rev-b").status)
        assertEquals(HttpStatusCode.Forbidden, server.unlink("member:a:dev", "rev-b").status)
    }

    @Test
    fun anotherOrganizationNeitherSeesNorChangesTheLinks() = slackTest { server ->
        server.links("user:a:owner", "rev-b")
        assertEquals(emptyList<SlackLinkDto>(), server.list("user:b:owner").body<SlackLinksPage>().items)
        assertEquals(HttpStatusCode.NotFound, server.unlink("user:b:owner", "rev-b").status)
        val taken = server.links("user:b:owner", "rev-x")
        assertEquals(HttpStatusCode.Conflict, taken.status, "a Slack account is one member across organizations")
        assertEquals(1, server.list("user:a:owner").body<SlackLinksPage>().items.size)
        val repository = server.koin.get<SlackRepository>()
        assertEquals(emptyList<SlackMessageRef>(), repository.messages(OTHER_ORG, Uuid.random()))
        assertEquals(null, repository.link(OTHER_ORG, "rev-b"))
    }

    @Test
    fun theDatabaseItselfKeepsOrganizationsApart() = slackTest { server ->
        server.links("user:a:owner", "rev-b")
        server.ask("+x")
        server.driver.execute(
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
        ).getOrThrow()
        server.driver.execute("GRANT SELECT, INSERT ON slack_links, slack_messages TO ostrom_rls_probe").getOrThrow()
        val seen = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            val none = counts()
            setting("ostrom.org_id", OTHER_ORG.toString())
            val other = counts()
            setting("ostrom.org_id", "")
            setting("ostrom.slack_member", "T0ACME:U0OTHER")
            val stranger = counts()
            setting("ostrom.slack_member", "T0ACME:U0REVB")
            val member = counts()
            setting("ostrom.org_id", ORG.toString())
            listOf(none, other, stranger, member, counts())
        }
        // Without the organization, from another one, or as another Slack member: nothing. A click's member reads
        // its own link only, never the organization's messages.
        assertEquals(listOf(0 to 0, 0 to 0, 0 to 0, 1 to 0, 1 to 1), seen)
        val write = writeAs(server) {
            setting("ostrom.org_id", OTHER_ORG.toString())
            execute(
                Statement.create(
                    "INSERT INTO slack_messages (org_id, request_id, gate_id, channel, ts) " +
                        "VALUES (:org, :id, :id, 'D', '1')",
                ).bind("org", ORG).bind("id", Uuid.random()),
            ).getOrThrow()
        }
        assertTrue(write.isFailure, "a row of another organization cannot be written")
    }

    private suspend fun QueryExecutor.setting(name: String, value: String) {
        fetchAll(Statement.create("SELECT set_config(:name, :value, TRUE)").bind("name", name).bind("value", value))
            .getOrThrow()
    }

    private suspend fun QueryExecutor.counts(): Pair<Int, Int> {
        val links = fetchAll("SELECT COUNT(*) AS n FROM slack_links").getOrThrow().first().get("n").asString()
        val messages = fetchAll("SELECT COUNT(*) AS n FROM slack_messages").getOrThrow().first().get("n").asString()
        return links.toInt() to messages.toInt()
    }

    private suspend fun writeAs(server: TestServer, block: suspend QueryExecutor.() -> Unit): Result<Unit> = try {
        server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            block()
        }
        Result.success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (expected: Exception) {
        Result.failure(expected)
    }

    private fun assertNotNullValue(value: String?): String {
        assertTrue(value != null, "the request offers its buttons")
        return value
    }
}
