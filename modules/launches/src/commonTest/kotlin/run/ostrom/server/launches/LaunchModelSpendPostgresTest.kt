// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches

import run.ostrom.server.budgets.BudgetsModule
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.presentation.MyBudgetResponse
import run.ostrom.server.gateway.GatewayModule
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.launches.presentation.LaunchResponse
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.reports.ReportsModule
import run.ostrom.server.reports.presentation.SpendingDto
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sessions.presentation.SessionResponse
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A model call of `ostrom claude` costs what the buyer sees: an agent token's call through the gateway is a MODEL event
 * of the ledger with its cost and launch, the organization's spending and the person's budget count it, and the
 * session's signed receipt carries it, on the real modules and PostgreSQL with a fake Anthropic behind the gateway.
 */
class LaunchModelSpendPostgresTest {
    private val modules = launchModules(
        SecretsModule, UsageModule, GatewayModule, BudgetsModule, SharingModule, ReportsModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** Anthropic's stream: 1 200 input tokens, 300 read from the cache, 42 output tokens. */
    private val events = """
        event: message_start
        data: {"type":"message_start","message":{"usage":{"input_tokens":1200,"cache_read_input_tokens":300,"output_tokens":1}}}

        event: message_delta
        data: {"type":"message_delta","usage":{"output_tokens":42}}

    """.trimIndent()

    private val anthropic = HttpClient(
        MockEngine {
            respond(ByteReadChannel(events), HttpStatusCode.OK, headersOf("Content-Type", "text/event-stream"))
        },
    ) {
        expectSuccess = false
    }

    /** The instance's Anthropic key, the environment for the rest (the test database). */
    private val config = object : ConfigSource {
        private val environment = EnvironmentConfigSource()
        private val gateway = mapOf(
            "OSTROM_GATEWAY_ANTHROPIC_URL" to "https://anthropic.test",
            "OSTROM_GATEWAY_ANTHROPIC_API_KEY" to "sk-ant-api03-instance",
        )

        override fun get(key: String): String? = gateway[key] ?: environment[key]
    }

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<ConfigSource> { config }
        single(GatewayModule.HTTP_CLIENT) { anthropic }
    }

    /** The owner of a new organization: their CLI token, and a console token that reads the costs. */
    private class Owner(val orgId: Uuid, val memberId: Uuid, val cliToken: String, val readerToken: String)

    private suspend fun TestServer.owner(login: String): Owner {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val tokens = koin.get<ApiTokensRepository>()
        val hasher = koin.get<SecretHasher>()
        suspend fun token(kind: TokenKind, scopes: Set<String>): String {
            val secret = "${kind.prefix}_" + Uuid.random().toHexString()
            val token = ApiToken(
                Uuid.random(), user.id, org.id, kind.name, kind, scopes, "hint", null, now, now + 1.hours, null, null,
            )
            tokens.create(token, hasher.hash(secret))
            return secret
        }
        return Owner(
            org.id,
            member.id,
            token(TokenKind.CLI, CLI_SCOPES),
            token(TokenKind.PERSONAL, setOf("usage:read", "budgets:read", "receipts:read")),
        )
    }

    private suspend fun TestServer.send(token: String, path: String, body: String): HttpResponse = client.post(path) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun anAgentsModelCallIsInTheLedgerTheSpendingTheBudgetAndTheReceipt() = serverTest(modules, overrides) { server ->
        with(server) {
            val ada = owner("ada")
            val created = send(ada.cliToken, "/api/v1/launches", """{"repo":"acme/api","agent":"claude-code"}""")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val launch = created.body<LaunchResponse>()
            val session = """{"id":"s-1","agent":"claude-code","repo":"acme/api","launchId":"${launch.launchId}"}"""
            val started = send(launch.agentToken, "/api/v1/sessions", session)
            assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())

            val answer = client.post("/api/v1/inference/v1/messages") {
                header("x-api-key", launch.agentToken)
                setBody(TextContent("""{"model":"claude-opus-5","stream":true}""", ContentType.Application.Json))
            }
            assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
            assertEquals(events, answer.bodyAsText())

            // Anthropic's price of claude-opus-5 per million tokens: $5 in, $0.50 cache read, $25 out.
            val micros = 1_200L * 5 + 300L / 2 + 42L * 25
            val event = koin.get<UsageRepository>().list(ada.orgId, null, null, 10).single()
            assertEquals(UsageKind.MODEL to "claude-opus-5", event.kind to event.name)
            assertEquals(micros, event.costMicros, "The gateway's cost reaches the ledger")
            assertEquals(launch.launchId to ada.memberId, event.launchId to event.memberId)
            assertEquals(
                listOf(1_200L, 42L, 300L),
                listOf(event.inputTokens, event.outputTokens, event.cacheReadTokens),
            )

            val usd = micros / 1_000_000.0
            val spending = client.get("/api/v1/spending") { bearerAuth(ada.readerToken) }
            assertEquals(HttpStatusCode.OK, spending.status, spending.bodyAsText())
            val report = spending.body<SpendingDto>()
            assertEquals(usd, report.totalUsd, "The organization's spending counts the call")
            assertEquals(listOf(ada.memberId.toString() to usd), report.byPerson.map { it.key to it.usd })
            // The gateway names the launch, not the session: found by the launch's session all the same (server#138).
            val byAgent = report.byAgent.map { Triple(it.key, it.usd, it.sessions) }
            assertEquals(listOf(Triple("claude-code", usd, 1L)), byAgent, "Spent by the launch's agent")
            val byRepo = report.byRepo.map { Triple(it.key, it.usd, it.sessions) }
            assertEquals(listOf(Triple("acme/api", usd, 1L)), byRepo, "Spent in the launch's repository")

            val budget = client.get("/api/v1/budgets/me") { bearerAuth(ada.readerToken) }
            assertEquals(HttpStatusCode.OK, budget.status, budget.bodyAsText())
            assertEquals(micros, budget.body<MyBudgetResponse>().spentMicros, "The person's budget goes down")

            val ended = send(launch.agentToken, "/api/v1/sessions/s-1/end", """{"exitCode":0,"costUsd":99.0}""")
            assertEquals(HttpStatusCode.OK, ended.status, ended.bodyAsText())
            val closed = ended.body<SessionResponse>().session
            assertEquals(usd, closed.costUsd, "The session costs what its launch spent, never the agent's figure")
            val receipt = client.get("/api/v1/receipts/${closed.receiptId}") { bearerAuth(ada.readerToken) }
            assertEquals(HttpStatusCode.OK, receipt.status, receipt.bodyAsText())
            val signed = Json.parseToJsonElement(receipt.bodyAsText()).jsonObject
            assertEquals(usd.toString(), signed.getValue("costUsd").jsonPrimitive.content, "Signed into the receipt")
        }
    }

    /** One model call of the launch's agent, as Claude Code sends it. */
    private suspend fun TestServer.infer(agentToken: String, maxTokens: Int): HttpResponse =
        client.post("/api/v1/inference/v1/messages") {
            header("x-api-key", agentToken)
            val body = """{"model":"claude-opus-5","stream":true,"max_tokens":$maxTokens}"""
            setBody(TextContent(body, ContentType.Application.Json))
        }

    private suspend fun errorOf(response: HttpResponse): Pair<String, String> {
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    @Test
    fun pastItsBudgetAnAgentsModelCallIsRefusedWithA429ItCanRead() = serverTest(modules, overrides) { server ->
        with(server) {
            val ada = owner("ada")
            // Each call of the fake Anthropic costs 7 200 µ$ (1 200 in, 300 read from the cache, 42 out).
            koin.get<BudgetsRepository>().replaceLimits(ada.orgId, BudgetTarget.Org, listOf(BudgetLimit(8_000)), now)
            val created = send(ada.cliToken, "/api/v1/launches", """{"repo":"acme/api","agent":"claude-code"}""")
            val launch = created.body<LaunchResponse>()
            val agent = launch.agentToken
            val session = """{"id":"s-2","agent":"claude-code","repo":"acme/api","launchId":"${launch.launchId}"}"""
            assertEquals(HttpStatusCode.OK, send(agent, "/api/v1/sessions", session).status)

            // 100 output tokens hold 2 500 µ$ and the 56 characters of the body 182 more: 2 682, room under 8 000.
            val first = infer(agent, maxTokens = 100)
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            assertEquals(events, first.bodyAsText())

            val tooLarge = infer(agent, maxTokens = 100)
            assertEquals(HttpStatusCode.TooManyRequests, tooLarge.status, tooLarge.bodyAsText())
            val (type, message) = errorOf(tooLarge)
            assertEquals("rate_limit_error", type, "Anthropic's shape, which Claude Code reads")
            assertEquals(
                "Budget left too low for this call: \$0.0008 is left of your organization's \$0.008 monthly limit " +
                    "and this call may cost up to \$0.002682. Lower its max_tokens, or retry once the calls in " +
                    "flight finish.",
                message,
            )
            assertEquals(null, tooLarge.headers["retry-after"], "A smaller call may go now")

            // One output token holds 25 µ$, and the body 175: 200 µ$, it goes. The provider then bills 7 200, past the
            // limit: the gap the reserve does not cover (an input larger than its estimate), which the PR states.
            assertEquals(HttpStatusCode.OK, infer(agent, maxTokens = 1).status)
            val reached = infer(agent, maxTokens = 1)
            assertEquals(HttpStatusCode.TooManyRequests, reached.status, reached.bodyAsText())
            assertTrue(errorOf(reached).second.startsWith("Monthly budget reached: "), errorOf(reached).second)
            assertTrue(reached.headers["retry-after"]!!.toLong() > 0, "Retry when the month resets")

            val events = koin.get<UsageRepository>().list(ada.orgId, null, null, 10)
            assertEquals(
                listOf("budget_exhausted", "budget_insufficient", "ok", "ok"),
                events.map { it.outcome }.sorted(),
                "Every call is in the ledger, the refused ones at no cost",
            )
            assertEquals(14_400, events.sumOf { it.costMicros })
            val holds = database.superuser.fetchAll("SELECT COUNT(*) AS n FROM usage_holds").getOrThrow()
            assertEquals("0", holds.first().get("n").asString(), "Every hold is settled by its call's record")
        }
    }

    @Test
    fun theSpendTheProviderBilledBlocksTheNextCall() = serverTest(modules, overrides) { server ->
        with(server) {
            val bob = owner("bob")
            // A personal limit of 5 000 µ$: one call of the fake Anthropic bills 7 200.
            koin.get<BudgetsRepository>().replaceLimits(
                bob.orgId, BudgetTarget.Member(bob.memberId), listOf(BudgetLimit(5_000)), now,
            )
            val launch = send(bob.cliToken, "/api/v1/launches", """{"repo":"acme/api","agent":"claude-code"}""")
                .body<LaunchResponse>()
            val session = """{"id":"s-3","agent":"claude-code","repo":"acme/api","launchId":"${launch.launchId}"}"""
            assertEquals(HttpStatusCode.OK, send(launch.agentToken, "/api/v1/sessions", session).status)

            // Admitted on its reserve (200 µ$), billed 7 200 by the provider: the real spend passes the limit.
            val billed = infer(launch.agentToken, maxTokens = 1)
            assertEquals(HttpStatusCode.OK, billed.status, billed.bodyAsText())
            val budget = client.get("/api/v1/budgets/me") { bearerAuth(bob.readerToken) }.body<MyBudgetResponse>()
            assertEquals(7_200L to 5_000L, budget.spentMicros to budget.limitMicros, "The budget reads the real spend")
            assertEquals(0L, budget.remainingMicros)

            // The next call is refused on what was billed, not on what was held.
            val refused = infer(launch.agentToken, maxTokens = 1)
            assertEquals(HttpStatusCode.TooManyRequests, refused.status, refused.bodyAsText())
            val (type, message) = errorOf(refused)
            assertEquals("rate_limit_error", type)
            val reached = "Monthly budget reached: your limit of \$0.005 is spent; it resets on "
            assertTrue(message.startsWith(reached), message)
            assertTrue(refused.headers["retry-after"]!!.toLong() > 0, "Retry when the month resets")
            val ledger = koin.get<UsageRepository>().list(bob.orgId, null, null, 10)
            assertEquals(
                listOf("budget_exhausted" to 0L, "ok" to 7_200L),
                ledger.map { it.outcome to it.costMicros }.sortedBy { it.first },
                "The refused call reached no provider and costs nothing",
            )
        }
    }
}
