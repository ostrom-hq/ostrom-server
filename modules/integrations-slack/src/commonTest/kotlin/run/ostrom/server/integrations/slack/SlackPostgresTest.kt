// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.JsonObject
import org.koin.core.qualifier.named
import org.koin.dsl.module
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.core.api.models.SlackLink
import run.ostrom.server.events.EventsModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GatePush
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.PushRef
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.usecases.AskGate
import run.ostrom.server.gates.domain.usecases.AskGateUseCase
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.integrations.slack.domain.LinkSlackUseCase
import run.ostrom.server.integrations.slack.domain.SlackApi
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.infrastructure.HmacSlackSigner
import run.ostrom.server.integrations.slack.infrastructure.HttpSlackApi
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventJournal
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.notifications.NotificationsModule
import run.ostrom.server.notifications.domain.DeliverNotificationsUseCase
import run.ostrom.server.notifications.domain.DeliveryRules
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Slack on the real identity, orgs, gates, events, notifications and Slack modules and PostgreSQL, with a fake Slack:
 * a member links the account of their proven address, a yes request reaches it through the router's queue with its
 * buttons, a signed Reject from the approver's Slack refuses the gate and closes the message, anyone else's click
 * changes nothing, and the link routes are a person's interactive login only.
 */
class SlackPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, GatesModule, EventsModule, NotificationsModule,
        SlackIntegrationModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val calls = mutableListOf<Pair<String, String>>()
    private val settings = SlackInstanceSettings()

    /** Slack: every address is a person named after it, every message is posted in their direct message. */
    private val slack = HttpClient(
        MockEngine { request ->
            val method = request.url.encodedPath.substringAfterLast('/')
            val body = request.body.toByteArray().decodeToString()
            calls += method to body
            val answer = when (method) {
                "users.lookupByEmail" -> {
                    val email = parseQueryString(body)["email"].orEmpty()
                    """{"ok":true,"user":{"id":"U-${email.substringBefore('@')}","deleted":false,"is_bot":false}}"""
                }
                "chat.postMessage" -> """{"ok":true,"channel":"D-1","ts":"1700000000.${calls.size}"}"""
                "auth.test" -> if (request.headers[HttpHeaders.Authorization] == "Bearer $BOT_TOKEN") {
                    """{"ok":true}"""
                } else {
                    """{"ok":false,"error":"invalid_auth"}"""
                }
                else -> """{"ok":true}"""
            }
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private val overrides = module {
        single<SecretCipher> { PlainCipher() }
        single<MailAvailability> { MailAvailability { false } }
        single<InstanceSettings> { settings }
        single<SlackApi> { HttpSlackApi(slack, "https://slack.test/api") }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Push to main", listOf("bob-s"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    private suspend fun TestServer.person(login: String): User = koin.get<ResolveUserUseCase>()(
        VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
    )

    private suspend fun TestServer.memberOf(org: Org?, user: User, slug: String): Pair<Org, Uuid> {
        val member = Member(Uuid.random(), org?.id ?: Uuid.random(), user.id, OrgRole.MEMBER, now)
        val orgs = koin.get<OrgsRepository>()
        val made = org ?: Org(member.orgId, slug, slug, null, null, now).also { orgs.create(it, member) { false } }
        if (org != null) {
            database.superuser.execute(
                "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES " +
                    "(CAST('${member.id}' AS uuid), CAST('${org.id}' AS uuid), CAST('${user.id}' AS uuid), " +
                    "'member', now())",
            ).getOrThrow()
        }
        return made to member.id
    }

    private suspend fun TestServer.askPushToMain(org: Uuid) = koin.get<AskGateUseCase>()(
        GateActor.person("ana", org, OrgRole.MEMBER, now),
        AskGate(
            sessionId = null, runId = null, repo = "acme/app", realRepo = null, actionClass = "push-main",
            tool = "Bash", command = "git push origin main", harnessVersion = null,
            push = GatePush("origin", listOf(PushRef("refs/heads/main", COMMIT)), false), pullRequest = null,
            diff = "+ok", via = AskedVia.HOOK,
        ),
    )

    private suspend fun TestServer.interaction(
        slackUser: String,
        value: String,
        signed: Boolean = true,
        action: String = "ostrom_reject",
    ): HttpResponse {
        val payload = """{"type":"block_actions","user":{"id":"$slackUser"},""" +
            """"actions":[{"action_id":"$action","value":"$value"}]}"""
        val body = "payload=${payload.encodeURLParameter()}"
        val stamp = Clock.System.now().epochSeconds.toString()
        val signature = "v0=" + HmacSlackSigner().sign(if (signed) SIGNING_SECRET else "another", "v0:$stamp:$body")
        return client.post("/webhooks/slack") {
            header("X-Slack-Signature", signature)
            header("X-Slack-Request-Timestamp", stamp)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(body)
        }
    }

    @Test
    fun aRejectFromTheApproversSlackRefusesTheGateAndClosesItsMessage() = serverTest(modules, overrides) { server ->
        val bob = server.person("bob-s")
        val eve = server.person("eve-s")
        val (org, bobMember) = server.memberOf(null, bob, "acme")
        val (_, eveMember) = server.memberOf(org, eve, "acme")
        val link = server.koin.get<LinkSlackUseCase>()
        assertEquals("U-bob-s", link(org.id, bobMember).slackUserId)
        assertEquals("U-eve-s", link(org.id, eveMember).slackUserId)
        val looked = calls.filter { it.first == "users.lookupByEmail" }.map { parseQueryString(it.second)["email"] }
        assertEquals(listOf("bob-s@example.com", "eve-s@example.com"), looked)
        // The router reads an organization from where it first sees it: it sees this one before the gate asks.
        server.koin.get<OrgEventPublisher>()
            .publish(org.id, listOf(OrgEventDraft(OrgEventType.WAIT_CREATED, "w-0", null, JsonObject(emptyMap()))))
        server.koin.get<OrgEventJournal>().behind(DeliveryRules.READER, 100)

        val asked = server.askPushToMain(org.id)
        val request = asked.requests.single()
        val deliver = server.koin.get<DeliverNotificationsUseCase>()
        repeat(5) { if (calls.none { it.first == "chat.postMessage" && "ostrom_reject" in it.second }) deliver() }
        val posted = calls.filter { it.first == "chat.postMessage" && "ostrom_reject" in it.second }
        assertEquals(1, posted.size, "One yes request, to Bob only: $calls")
        assertTrue("\"channel\":\"U-bob-s\"" in posted.single().second)
        assertTrue("${org.id}:${request.id}" in posted.single().second)
        assertTrue("/yes/${request.id}" in posted.single().second)

        val gates = server.koin.get<GatesRepository>()
        val forged = server.interaction("U-bob-s", "${org.id}:${request.id}", signed = false)
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertEquals(HttpStatusCode.OK, server.interaction("U-eve-s", "${org.id}:${request.id}").status)
        assertEquals(GateStatus.PENDING, gates.get(org.id, asked.gate.id)?.status, "Eve is not the approver")
        assertEquals(HttpStatusCode.OK, server.interaction("U-nobody", "${org.id}:${request.id}").status)
        assertEquals(GateStatus.PENDING, gates.get(org.id, asked.gate.id)?.status)
        val approve = server.interaction("U-bob-s", "${org.id}:${request.id}", action = "ostrom_approve")
        assertEquals(HttpStatusCode.OK, approve.status)
        assertEquals(GateStatus.PENDING, gates.get(org.id, asked.gate.id)?.status, "Approve is a link, never a yes")

        val rejected = server.interaction("U-bob-s", "${org.id}:${request.id}")
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        assertEquals("", rejected.bodyAsText())
        assertEquals(GateStatus.REJECTED, gates.get(org.id, asked.gate.id)?.status)
        assertEquals(AnswerChannel.SLACK, gates.requestsOf(org.id, asked.gate.id).single().channel)
        val update = calls.single { it.first == "chat.update" }.second
        assertTrue("\"channel\":\"D-1\"" in update && "Rejected by bob-s." in update, update)
        assertEquals(HttpStatusCode.OK, server.interaction("U-bob-s", "${org.id}:${request.id}").status, "Decided")
    }

    @Test
    fun aWebhookBodyIsReadOnlyOnceSignedAndNeedsAPayload() = serverTest(modules, overrides) { server ->
        val noPayload = server.client.post("/webhooks/slack") { setBody("payload=%zz") }
        assertEquals(HttpStatusCode.Unauthorized, noPayload.status, "Not signed: nothing read")
        val stamp = Clock.System.now().epochSeconds.toString()
        val empty = server.client.post("/webhooks/slack") {
            header("X-Slack-Signature", "v0=" + HmacSlackSigner().sign(SIGNING_SECRET, "v0:$stamp:"))
            header("X-Slack-Request-Timestamp", stamp)
            setBody("")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status, "Signed, without a payload")
        val large = server.client.post("/webhooks/slack") { setBody("payload=" + "x".repeat(64 * 1024)) }
        assertEquals(HttpStatusCode.BadRequest, large.status, "Too large: refused before its signature")

        val section = server.koin.get<SettingsSection>(named("slack"))
        assertEquals(null, section.field("botToken")?.refusal(BOT_TOKEN))
        assertEquals(
            "Slack refuses this bot token (invalid_auth).",
            section.field("botToken")?.refusal("xoxb-0000-0000-revokedtoken"),
        )

        val (org, bobMember) = server.memberOf(null, server.person("bob-s"), "acme")
        server.koin.get<LinkSlackUseCase>()(org.id, bobMember)
        val links = server.koin.get<SlackLinksRepository>()
        assertEquals(emptyList(), links.ofMembers(org.id, emptySet()))
        val listener = server.koin.get<OrgDeletionListener>(named("integrations-slack"))
        listener.deleted(org.id)
        assertEquals(null, links.of(org.id, bobMember))
        listener.purge(org.id)
    }

    private val principals = mutableMapOf<String, OrgPrincipal>()

    private val routeOverrides = module {
        includes(overrides)
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf("agent:session"))
                    in principals -> AuthenticatedPrincipal(
                        token, PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER,
                    )
                    else -> null
                }

                override suspend fun isLive(subjectId: String, credentialId: String): Boolean = true
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    principals[principal.subjectId]
            }
        }
    }

    @Test
    fun aMemberLinksAndUnlinksTheirOwnSlackFromTheConsoleAndOnlyThem() = serverTest(modules, routeOverrides) { server ->
        val bob = server.person("bob-s")
        val (org, member) = server.memberOf(null, bob, "acme")
        principals["bob"] = OrgPrincipal(org.id, member, OrgRole.MEMBER)
        suspend fun read(token: String) = server.client.get("/api/v1/slack/link") { bearerAuth(token) }

        assertEquals(SlackLink(member.toString(), true, false), read("bob").body())
        val linked = server.client.post("/api/v1/slack/link") { bearerAuth("bob") }
        assertEquals(HttpStatusCode.OK, linked.status, linked.bodyAsText())
        assertEquals("U-bob-s", linked.body<SlackLink>().slackUserId)
        assertEquals(true, read("bob").body<SlackLink>().linked)
        assertEquals(HttpStatusCode.Forbidden, read("agent").status)
        assertEquals(HttpStatusCode.Forbidden, server.client.post("/api/v1/slack/link") { bearerAuth("agent") }.status)
        assertEquals(HttpStatusCode.Unauthorized, read("nobody").status)
        assertTrue(ServerFeatures.SLACK in SlackIntegrationModule.features(server.koin))

        assertEquals(HttpStatusCode.NoContent, server.client.delete("/api/v1/slack/link") { bearerAuth("bob") }.status)
        assertEquals(false, read("bob").body<SlackLink>().linked)
        settings.configured = false
        assertEquals(SlackLink(member.toString(), false, false), read("bob").body())
        val refused = server.client.post("/api/v1/slack/link") { bearerAuth("bob") }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("slack_not_configured" in refused.bodyAsText())
        assertEquals(setOf(ServerFeatures.SLACK), SlackIntegrationModule.features(server.koin), "Served all the same")
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}

/** A sealed secret is its plaintext, under its context: the events module's cipher in a test. */
class PlainCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String = "$context:${plaintext.decodeToString()}"

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.removePrefix("$context:").encodeToByteArray()
}
