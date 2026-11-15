// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.koin.core.qualifier.named
import org.koin.dsl.module
import run.ostrom.core.api.models.DiscordLink
import run.ostrom.core.api.models.DiscordLinkStart
import run.ostrom.core.api.models.ServerFeatures
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
import run.ostrom.server.integrations.discord.domain.CompleteDiscordLinkUseCaseImpl
import run.ostrom.server.integrations.discord.domain.DiscordApi
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessage
import run.ostrom.server.integrations.discord.domain.DiscordApprovalMessagesRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttempt
import run.ostrom.server.integrations.discord.domain.DiscordLinkAttemptsRepository
import run.ostrom.server.integrations.discord.domain.DiscordLinksRepository
import run.ostrom.server.integrations.discord.domain.DiscordPosted
import run.ostrom.server.integrations.discord.domain.DiscordUserTakenException
import run.ostrom.server.integrations.discord.domain.StoredDiscordLink
import run.ostrom.server.integrations.discord.domain.sha256Hex
import run.ostrom.server.integrations.discord.infrastructure.HttpDiscordApi
import run.ostrom.server.integrations.discord.infrastructure.attemptOf
import run.ostrom.server.integrations.discord.infrastructure.discordLinksOf
import run.ostrom.server.integrations.discord.infrastructure.discordMessageOf
import run.ostrom.server.integrations.discord.infrastructure.memberOfDiscordUser
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.JdbcDatabase
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
import run.ostrom.server.testing.assertApiCatalogue
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Discord on the real identity, orgs, gates, events, notifications and Discord modules and PostgreSQL, with Discord's
 * API on a mock engine: a yes request reaches the approver's Discord through the router's queue with its buttons, a
 * signed Reject from the approver's account refuses the gate and updates the clicked message, anyone else's click
 * changes nothing, a member links their account by Discord's sign-in in the browser that started it, and the link
 * routes are a person's interactive login only.
 */
class DiscordPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, GatesModule, EventsModule, NotificationsModule,
        DiscordIntegrationModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val calls = mutableListOf<Pair<String, String>>()
    private val settings = DiscordInstanceSettings()
    private val guildMembers = mutableSetOf("300000000000000001", "300000000000000002")

    /**
     * Discord: a code `code-<id>` signs in the account `<id>`, the server holds [guildMembers], every direct message
     * channel is `DM-<id>`, and every message gets the next id.
     */
    private val discord = HttpClient(
        MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/api")
            val body = request.body.toByteArray().decodeToString()
            calls += "${request.method.value} $path" to body
            val auth = request.headers[HttpHeaders.Authorization].orEmpty()
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                path == "/users/@me" && auth == "Bot $BOT_TOKEN" ->
                    respond("""{"id":"100000000000000009","username":"ostrom","bot":true}""", headers = json)
                path == "/users/@me" && auth.startsWith("Bearer token-") -> {
                    val id = auth.removePrefix("Bearer token-")
                    respond("""{"id":"$id","username":"user-$id"}""", headers = json)
                }
                path == "/users/@me" ->
                    respond("""{"message":"401: Unauthorized","code":0}""", HttpStatusCode.Unauthorized, json)
                path == "/oauth2/token" -> {
                    val code = parseQueryString(body)["code"].orEmpty()
                    respond("""{"access_token":"token-${code.removePrefix("code-")}"}""", headers = json)
                }
                path.startsWith("/guilds/$GUILD_ID/members/") ->
                    if (path.substringAfterLast('/') in guildMembers) {
                        respond("""{"user":{"id":"${path.substringAfterLast('/')}"}}""", headers = json)
                    } else {
                        respond("""{"message":"Unknown Member","code":10007}""", HttpStatusCode.NotFound, json)
                    }
                path == "/users/@me/channels" -> {
                    val recipient = Json.parseToJsonElement(body).let { (it as JsonObject)["recipient_id"] }
                    respond("""{"id":"DM-${recipient.toString().trim('"')}"}""", headers = json)
                }
                else -> respond("""{"id":"${calls.size}"}""", headers = json)
            }
        },
    )

    private val overrides = module {
        single<SecretCipher> { PlainCipher() }
        single<MailAvailability> { MailAvailability { false } }
        single<InstanceSettings> { settings }
        single<DiscordApi> { HttpDiscordApi(discord, "https://discord.test/api") }
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

    private suspend fun TestServer.interaction(keys: DiscordKeys, body: String): HttpResponse {
        val stamp = Clock.System.now().epochSeconds.toString()
        return client.post("/webhooks/discord") {
            header("X-Signature-Ed25519", keys.sign(stamp, body))
            header("X-Signature-Timestamp", stamp)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private fun click(user: String, customId: String) =
        """{"type":3,"user":{"id":"$user"},"data":{"custom_id":"$customId"},"message":{"content":"**Push**"}}"""

    @Test
    fun theCatalogueDescribesEachRouteOnce() = serverTest(modules, overrides) { server ->
        server.assertApiCatalogue(DiscordIntegrationModule)
    }

    @Test
    fun aRejectFromTheApproversDiscordRefusesTheGateAndUpdatesItsMessage() = serverTest(modules, overrides) { server ->
        val keys = DiscordKeys.generate()
        settings.publicKey = keys.publicKey
        val bob = server.person("bob-s")
        val eve = server.person("eve-s")
        val (org, bobMember) = server.memberOf(null, bob, "acme")
        val (_, eveMember) = server.memberOf(org, eve, "acme")
        val links = server.koin.get<DiscordLinksRepository>()
        links.save(org.id, StoredDiscordLink(bobMember, BOB_ID, "bob", now))
        links.save(org.id, StoredDiscordLink(eveMember, EVE_ID, "eve", now))
        // The router reads an organization from where it first sees it: it sees this one before the gate asks.
        server.koin.get<OrgEventPublisher>()
            .publish(org.id, listOf(OrgEventDraft(OrgEventType.WAIT_CREATED, "w-0", null, JsonObject(emptyMap()))))
        server.koin.get<OrgEventJournal>().behind(DeliveryRules.READER, 100)

        val asked = server.askPushToMain(org.id)
        val request = asked.requests.single()
        val deliver = server.koin.get<DeliverNotificationsUseCase>()
        fun posts() = calls.filter { it.first.startsWith("POST /channels/") && "ostrom_reject" in it.second }
        repeat(5) { if (posts().isEmpty()) deliver() }
        assertEquals(1, posts().size, "One yes request, to Bob only: $calls")
        assertEquals("POST /channels/DM-$BOB_ID/messages", posts().single().first)
        assertTrue("ostrom_reject:${org.id}:${request.id}" in posts().single().second)
        assertTrue("/yes/${request.id}" in posts().single().second)
        assertEquals("DM-$BOB_ID", links.of(org.id, bobMember)?.dmChannelId, "The channel is kept with the link")

        val gates = server.koin.get<GatesRepository>()
        val reject = click(BOB_ID, "ostrom_reject:${org.id}:${request.id}")
        val forged = server.interaction(DiscordKeys.generate(), reject)
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        val pong = server.interaction(keys, """{"type":1}""")
        assertEquals("""{"type":1}""", pong.bodyAsText())
        val eves = server.interaction(keys, click(EVE_ID, "ostrom_reject:${org.id}:${request.id}"))
        assertEquals(HttpStatusCode.OK, eves.status)
        assertTrue(""""flags":64""" in eves.bodyAsText(), eves.bodyAsText())
        assertEquals(GateStatus.PENDING, gates.get(org.id, asked.gate.id)?.status, "Eve is not the approver")
        val nobody = server.interaction(keys, click("300000000000000099", "ostrom_reject:${org.id}:${request.id}"))
        assertTrue(""""type":4""" in nobody.bodyAsText())
        assertEquals(GateStatus.PENDING, gates.get(org.id, asked.gate.id)?.status)
        val other = server.interaction(keys, click(BOB_ID, "something_else"))
        assertEquals("""{"type":6}""", other.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, server.interaction(keys, """{"type":2}""").status)

        val rejected = server.interaction(keys, reject)
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        assertEquals(ContentType.Application.Json, rejected.contentType()?.withoutParameters())
        assertTrue(""""type":7""" in rejected.bodyAsText() && "Rejected by bob\\\\-s." in rejected.bodyAsText())
        assertEquals(GateStatus.REJECTED, gates.get(org.id, asked.gate.id)?.status)
        assertEquals(AnswerChannel.DISCORD, gates.requestsOf(org.id, asked.gate.id).single().channel)
        repeat(5) { if (calls.none { it.first.startsWith("PATCH") }) deliver() }
        val edit = calls.single { it.first.startsWith("PATCH") }
        assertTrue(edit.first.startsWith("PATCH /channels/DM-$BOB_ID/messages/"), edit.first)
        assertTrue("Rejected by bob" in edit.second && """"components":[]""" in edit.second, edit.second)
        val decided = server.interaction(keys, reject)
        assertTrue(""""type":4""" in decided.bodyAsText(), "Decided")
    }

    @Test
    fun twoMembersLinkingTheSameDiscordAccountAtOnceGetTheTakenRefusalNotAnError() =
        serverTest(modules, overrides) { server ->
        val (org, bobMember) = server.memberOf(null, server.person("bob-s"), "acme")
        val (_, eveMember) = server.memberOf(org, server.person("eve-s"), "acme")
        val links = server.koin.get<DiscordLinksRepository>()
        links.save(org.id, StoredDiscordLink(bobMember, BOB_ID, "bob", now))

        // The second member's use case read the links before the first one wrote: the unique key answers.
        val eves = StoredDiscordLink(eveMember, BOB_ID, "x", now)
        assertFailsWith<DiscordUserTakenException> { links.save(org.id, eves) }
        assertEquals(bobMember, links.memberOf(org.id, BOB_ID))
        assertEquals(null, links.of(org.id, eveMember))
        links.save(org.id, StoredDiscordLink(bobMember, BOB_ID, "bob", now, "DM-1"))
        assertEquals("DM-1", links.of(org.id, bobMember)?.dmChannelId, "Replaced, with its channel")
        // A channel opened with an account the member no longer links is not kept.
        links.saveDmChannel(org.id, bobMember, EVE_ID, "DM-2")
        assertEquals("DM-1", links.of(org.id, bobMember)?.dmChannelId)
        links.saveDmChannel(org.id, bobMember, BOB_ID, "DM-3")
        assertEquals("DM-3", links.of(org.id, bobMember)?.dmChannelId)
        // Any other database failure is not mistaken for a taken account (text PostgreSQL cannot hold).
        val nul = StoredDiscordLink(bobMember, "U\u0000", "x", now)
        assertFailsWith<ExposedSQLException> { links.save(org.id, nul) }

        val raced = object : DiscordLinksRepository by links {
            override suspend fun memberOf(orgId: Uuid, discordUserId: String): Uuid? = null
        }
        val attempts = server.koin.get<DiscordLinkAttemptsRepository>()
        val state = "${org.id}.${"a".repeat(43)}"
        val stateHash = sha256Hex(state)
        val binding = sha256Hex("session")
        attempts.start(org.id, DiscordLinkAttempt(stateHash, eveMember, binding, now + 10.minutes), now)
        val complete = CompleteDiscordLinkUseCaseImpl(
            server.koin.get(), server.koin.get(), attempts, server.koin.get(), raced, server.koin.get(),
            "https://o.test/cb", Clock.System,
        )
        assertFailsWith<DiscordUserTakenException> { complete(state, "code-$BOB_ID", null, "session") }
        assertEquals(null, links.of(org.id, eveMember))
    }

    @Test
    fun aWebhookBodyIsReadOnlyOnceSignedAndAtMost64KiB() = serverTest(modules, overrides) { server ->
        val unsigned = server.client.post("/webhooks/discord") { setBody("""{"type":1}""") }
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status, "Not signed: nothing read")
        val keys = DiscordKeys.generate()
        settings.publicKey = keys.publicKey
        val garbled = server.interaction(keys, "not json")
        assertEquals(HttpStatusCode.BadRequest, garbled.status, "Signed, not an interaction")
        val large = server.client.post("/webhooks/discord") { setBody("x".repeat(64 * 1024 + 1)) }
        assertEquals(HttpStatusCode.BadRequest, large.status, "Too large: refused before its signature")
        val padded = """{"type":1,"pad":"${"x".repeat(64 * 1024 - 19)}"}"""
        assertEquals(64 * 1024, padded.length)
        assertEquals(HttpStatusCode.OK, server.interaction(keys, padded).status, "64 KiB exactly is read")

        val section = server.koin.get<SettingsSection>(named("discord"))
        assertEquals(null, section.field("botToken")?.refusal(BOT_TOKEN))
        assertEquals(
            "Discord refuses this bot token (discord_http_401).",
            section.field("botToken")?.refusal(BOT_TOKEN.reversed()),
        )

        val (org, bobMember) = server.memberOf(null, server.person("bob-s"), "acme")
        val links = server.koin.get<DiscordLinksRepository>()
        links.save(org.id, StoredDiscordLink(bobMember, BOB_ID, "bob", now))
        val attempts = server.koin.get<DiscordLinkAttemptsRepository>()
        attempts.start(org.id, DiscordLinkAttempt("h1", bobMember, "b", now + 10.minutes), now)
        val messages = server.koin.get<DiscordApprovalMessagesRepository>()
        val approval = Uuid.random()
        messages.save(org.id, DiscordApprovalMessage(approval, Uuid.random(), DiscordPosted("DM-1", "1")))
        messages.save(org.id, DiscordApprovalMessage(approval, Uuid.random(), DiscordPosted("DM-1", "2")))
        assertEquals(DiscordPosted("DM-1", "2"), messages.of(org.id, approval)?.posted, "Replaced")
        assertEquals(emptyList(), links.ofMembers(org.id, emptySet()))
        val listener = server.koin.get<OrgDeletionListener>(named("integrations-discord"))
        listener.deleted(org.id)
        assertEquals(null, links.of(org.id, bobMember))
        assertEquals(null, attempts.of(org.id, "h1"))
        assertEquals(null, messages.of(org.id, approval))
        listener.purge(org.id)
    }

    @Test
    fun anAttemptIsSingleUseAndAMembersNewOneReplacesTheirOldOneAndTheExpiredOnes() =
        serverTest(modules, overrides) { server ->
        val (org, bob) = server.memberOf(null, server.person("bob-s"), "acme")
        val (_, eve) = server.memberOf(org, server.person("eve-s"), "acme")
        val attempts = server.koin.get<DiscordLinkAttemptsRepository>()
        attempts.start(org.id, DiscordLinkAttempt("old", bob, "b", now + 10.minutes), now)
        attempts.start(org.id, DiscordLinkAttempt("expired", eve, "b", now - 1.minutes), now - 20.minutes)
        attempts.start(org.id, DiscordLinkAttempt("new", bob, "b", now + 10.minutes), now)
        assertEquals(null, attempts.of(org.id, "old"), "One per member")
        assertEquals(null, attempts.of(org.id, "expired"), "The expired ones go")
        assertEquals(DiscordLinkAttempt("new", bob, "b", now + 10.minutes), attempts.of(org.id, "new"))
        assertTrue(attempts.consume(org.id, "new"))
        assertFalse(attempts.consume(org.id, "new"), "Used up")
    }

    private val principals = mutableMapOf<String, OrgPrincipal>()

    private val routeOverrides = module {
        includes(overrides)
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf("agent:session"))
                    "cli" -> AuthenticatedPrincipal("bob", PrincipalKind.USER, emptySet(), signedIn = true)
                    "bob-tab" -> AuthenticatedPrincipal(
                        "bob", PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER,
                    )
                    in principals -> AuthenticatedPrincipal(
                        token, PrincipalKind.USER, setOf("user:*"), credentialId = "session-$token",
                        channel = PrincipalChannel.BROWSER,
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
    fun aMemberLinksTheirOwnDiscordInTheBrowserThatStartedItAndOnlyThem() = serverTest(modules, routeOverrides) {
        server ->
        val bob = server.person("bob-s")
        val (org, member) = server.memberOf(null, bob, "acme")
        principals["bob"] = OrgPrincipal(org.id, member, OrgRole.MEMBER)
        val browser = server.client.config { followRedirects = false }
        suspend fun read(token: String) = server.client.get("/api/v1/discord/link") { bearerAuth(token) }
        suspend fun start(token: String) = server.client.post("/api/v1/discord/link") { bearerAuth(token) }
        suspend fun back(state: String?, code: String?, token: String?, error: String? = null): String {
            val query = listOfNotNull(
                state?.let { "state=${it.encodeURLParameter()}" },
                code?.let { "code=$it" },
                error?.let { "error=$it" },
            ).joinToString("&")
            val response = browser.get("/api/v1/discord/link/callback?$query") { token?.let { bearerAuth(it) } }
            assertEquals(HttpStatusCode.Found, response.status, response.bodyAsText())
            val location = response.headers[HttpHeaders.Location].orEmpty()
            assertTrue(location.substringBefore('?').endsWith("/me/notifications"), location)
            return Url(location).parameters["discord"].orEmpty()
        }
        suspend fun started(token: String = "bob"): String {
            val answer = start(token)
            assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
            return Url(answer.body<DiscordLinkStart>().authorizeUrl).parameters["state"]!!
        }

        assertEquals(DiscordLink(member.toString(), true, false), read("bob").body())
        assertEquals(HttpStatusCode.Forbidden, start("agent").status)
        val cli = start("cli")
        assertEquals(HttpStatusCode.Forbidden, cli.status, "A token is no browser: Discord's sign-in needs one")
        assertEquals(HttpStatusCode.Forbidden, read("agent").status)
        assertEquals(HttpStatusCode.Unauthorized, read("nobody").status)
        assertEquals(HttpStatusCode.Unauthorized, start("nobody").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.delete("/api/v1/discord/link").status)

        val authorize = Url(start("bob").body<DiscordLinkStart>().authorizeUrl)
        assertEquals("discord.com", authorize.host)
        val redirect = authorize.parameters["redirect_uri"].orEmpty()
        assertTrue(redirect.endsWith("/api/v1/discord/link/callback"), "The callback under OSTROM_PUBLIC_URL")
        assertEquals("discord_link_not_found", back(null, "code-$BOB_ID", "bob"))
        assertEquals("discord_link_not_found", back("${org.id}.${"a".repeat(43)}", "code-$BOB_ID", "bob"))
        val state = started()
        assertEquals("discord_link_browser_mismatch", back(state, "code-$BOB_ID", null), "No session")
        assertEquals("discord_link_browser_mismatch", back(state, "code-$BOB_ID", "bob-tab"), "Another session")
        assertEquals("discord_link_denied", back(started(), null, "bob", error = "access_denied"))
        guildMembers.remove(BOB_ID)
        assertEquals("discord_not_in_guild", back(started(), "code-$BOB_ID", "bob"))
        guildMembers += BOB_ID
        val linked = started()
        assertEquals("linked", back(linked, "code-$BOB_ID", "bob"))
        assertEquals("discord_link_not_found", back(linked, "code-$BOB_ID", "bob"), "Single-use")
        assertTrue(calls.any { it.first == "POST /channels/DM-$BOB_ID/messages" }, "The account is told: $calls")

        val link = read("bob").body<DiscordLink>()
        assertEquals(DiscordLink(member.toString(), true, true, BOB_ID, "user-$BOB_ID", link.linkedAt), link)
        assertTrue(ServerFeatures.DISCORD in DiscordIntegrationModule.features(server.koin))
        val removed = server.client.delete("/api/v1/discord/link") { bearerAuth("bob") }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertEquals(false, read("bob").body<DiscordLink>().linked)
        val agentDelete = server.client.delete("/api/v1/discord/link") { bearerAuth("agent") }
        assertEquals(HttpStatusCode.Forbidden, agentDelete.status)

        val late = started()
        settings.configured = false
        assertEquals("discord_not_configured", back(late, "code-$BOB_ID", "bob"))
        assertEquals(DiscordLink(member.toString(), false, false), read("bob").body())
        val refused = start("bob")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("discord_not_configured" in refused.bodyAsText())
        val features = DiscordIntegrationModule.features(server.koin)
        assertEquals(setOf(ServerFeatures.DISCORD), features, "Served all the same")
    }

    @Test
    fun theReadsOfTheLinksAttemptsAndMessagesLookUpTheirIndexes() = serverTest(modules, overrides) { server ->
        // Fixtures only: other organizations' links, attempts and messages, and the statistics.
        val fixtures = server.database.superuser
        fixtures.execute(
            "INSERT INTO discord_links (org_id, member_id, discord_user_id, discord_username, linked_at) " +
                "SELECT gen_random_uuid(), gen_random_uuid(), n::text, 'u', now() FROM generate_series(1, 5000) n",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO discord_link_attempts (org_id, state_hash, member_id, binding, expires_at) " +
                "SELECT gen_random_uuid(), md5(n::text), gen_random_uuid(), 'b', now() " +
                "FROM generate_series(1, 5000) n",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO discord_approval_messages (org_id, approval_id, gate_id, channel_id, message_id) " +
                "SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'C', n::text " +
                "FROM generate_series(1, 5000) n",
        ).getOrThrow()
        listOf("discord_links", "discord_link_attempts", "discord_approval_messages").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }
        val database = server.koin.get<JdbcDatabase>()
        val org = Uuid.random()
        val reads = listOf(
            "the links of members" to discordLinksOf(org, setOf(Uuid.random(), Uuid.random())),
            "the member of a Discord user" to memberOfDiscordUser(org, "42"),
            "the attempt of a state" to attemptOf(org, "hash"),
            "the message of a request" to discordMessageOf(org, Uuid.random()),
        )
        for ((name, read) in reads) {
            val plan = database.inOrg(org) { plan { read } }
            assertFalse("Seq Scan" in plan, "The read of $name scans its table:\n$plan")
        }
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val BOB_ID = "300000000000000001"
        const val EVE_ID = "300000000000000002"
    }
}

/** A sealed secret is its plaintext, under its context: the events module's cipher in a test. */
class PlainCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String = "$context:${plaintext.decodeToString()}"

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.removePrefix("$context:").encodeToByteArray()
}
