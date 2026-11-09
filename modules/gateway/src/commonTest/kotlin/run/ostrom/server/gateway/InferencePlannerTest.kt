// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.ClaudeMetadata
import run.ostrom.server.gateway.domain.GatewaySettings
import run.ostrom.server.gateway.domain.InferenceCall
import run.ostrom.server.gateway.domain.InferencePlan
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.InstanceProviderKeys
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.gateway.domain.RefusalKind
import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.UpstreamRequests
import run.ostrom.server.gateway.domain.WireProtocol
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Where a call goes and what is sent: model policy, budgets, shared accounts first, then keys. */
class InferencePlannerTest {
    private val clock = TestClock()
    private val settings = InMemorySettings()
    private val accounts = InMemoryAccounts()

    private fun call(protocol: WireProtocol, body: String, headers: Map<String, String> = emptyMap()) =
        InferenceCall(protocol, body, headers, "session-1")

    private suspend fun plan(
        protocol: WireProtocol,
        body: String,
        headers: Map<String, String> = emptyMap(),
        secrets: FakeSecrets = FakeSecrets(),
        keys: InstanceProviderKeys = InstanceProviderKeys(),
    ) = planner(settings, pool(accounts, clock), secrets, keys).plan(Gw.memberA, call(protocol, body, headers))

    private fun refusal(plan: InferencePlan) = assertIs<InferencePlan.Refused>(plan).refusal

    @Test
    fun aBodyThatIsNoObjectOrNamesNoModelIsInvalid() = runTest {
        val refused = assertIs<InferencePlan.Refused>(plan(WireProtocol.MESSAGES, "not json"))
        assertEquals(RefusalKind.INVALID, refused.refusal.kind)
        assertEquals("", refused.model)
        assertEquals(RefusalKind.INVALID, refusal(plan(WireProtocol.MESSAGES, "{}")).kind)
    }

    @Test
    fun theOrgPolicyAndTheMemberListDecideTheModel() = runTest {
        settings.policies[Gw.orgA] = ModelPolicy(
            allowed = listOf("claude-"),
            default = "claude-sonnet-5",
            byMember = mapOf(Gw.otherA.memberId.toString() to listOf("gpt-")),
        )
        val refused = refusal(plan(WireProtocol.CHAT, """{"model":"gpt-5"}"""))
        assertEquals(RefusalKind.MODEL_NOT_ALLOWED, refused.kind)
        assertTrue("gpt-5" in refused.message)
        val other = planner(settings, pool(accounts, clock), keys = InstanceProviderKeys(openai = "sk-op"))
            .plan(Gw.otherA, call(WireProtocol.CHAT, """{"model":"gpt-5"}"""))
        assertIs<InferencePlan.Ready>(other, "The member's own list replaces the org's")
        val defaulted = assertIs<InferencePlan.Ready>(
            plan(WireProtocol.MESSAGES, """{"max_tokens":1}""", keys = InstanceProviderKeys(anthropic = "sk-an")),
        )
        assertEquals("claude-sonnet-5", defaulted.model)
        assertEquals("""{"model":"claude-sonnet-5","max_tokens":1}""", defaulted.request.body)
    }

    @Test
    fun anExhaustedBudgetStopsTheCall() = runTest {
        val resets = clock.now() + 1.hours
        val spend = SpendGuard { _ -> SpendDecision.Exhausted("Monthly budget reached.", resets) }
        val refused = planner(settings, pool(accounts, clock), spend = spend)
            .plan(Gw.memberA, call(WireProtocol.MESSAGES, """{"model":"claude-opus-5"}"""))
        assertEquals(RefusalKind.BUDGET_EXHAUSTED, refusal(refused).kind)
        assertEquals(resets, refusal(refused).retryAt)
        assertEquals("claude-opus-5", refused.model)
    }

    @Test
    fun aSharedClaudeAccountServesMessagesWithItsTokenAndItsAccountId() = runTest {
        val account = accounts.share(AccountProvider.CLAUDE, "max", clock)
        val userId = """{\"device_id\":\"d\",\"account_uuid\":\"someone-else\",\"session_id\":\"s\"}"""
        val body = """{"model":"claude-opus-5","stream":true,"metadata":{"user_id":"$userId"},"messages":[]}"""
        val headers = mapOf(
            "anthropic-beta" to "a, ,b",
            "anthropic-version" to "2025-01-01",
            "accept" to "text/event-stream",
        )
        val ready = assertIs<InferencePlan.Ready>(plan(WireProtocol.MESSAGES, body, headers))
        assertEquals(InferenceProvider.CLAUDE_SHARED, ready.provider)
        assertEquals(account.id, ready.lease?.account?.id)
        assertTrue(ready.stream)
        assertEquals("https://anthropic.test/v1/messages", ready.request.url)
        assertEquals(
            listOf(
                "content-type" to "application/json",
                "accept-encoding" to "identity",
                "anthropic-version" to "2025-01-01",
                "anthropic-beta" to "a,b,${UpstreamRequests.OAUTH_BETA}",
                "accept" to "text/event-stream",
                "authorization" to "Bearer access-max",
            ),
            ready.request.headers,
        )
        assertTrue("acct-max" in ready.request.body && "someone-else" !in ready.request.body, ready.request.body)
        assertTrue(ready.request.body.endsWith(""","messages":[]}"""))
        assertEquals("UpstreamRequest(https://anthropic.test/v1/messages)", ready.request.toString())
        ready.lease?.release()

        val already = assertIs<InferencePlan.Ready>(
            plan(
                WireProtocol.MESSAGES,
                """{"model":"claude-opus-5"}""",
                mapOf(
                    "anthropic-beta" to UpstreamRequests.OAUTH_BETA,
                ),
            ),
        )
        assertEquals(
            listOf(UpstreamRequests.OAUTH_BETA),
            already.request.headers.filter { it.first == "anthropic-beta" }.map { it.second },
        )
        assertEquals(UpstreamRequests.DEFAULT_ANTHROPIC_VERSION, already.request.headers.toMap()["anthropic-version"])
        assertEquals("""{"model":"claude-opus-5"}""", already.request.body, "Nothing to rewrite without metadata")
        already.lease?.release()
        val plain = """{"model":"claude-opus-5","metadata":{"user_id":"plain"}}"""
        val kept = assertIs<InferencePlan.Ready>(plan(WireProtocol.MESSAGES, plain))
        assertEquals(plain, kept.request.body, "Metadata naming no account is left as it is")
    }

    @Test
    fun claudeMetadataNamesTheSharedAccount() {
        val legacy = """{"user_id":"user_abc_account_1234-ab_session_5678"}"""
        assertEquals("""{"user_id":"user_abc_account_acct_session_5678"}""", ClaudeMetadata.withAccount(legacy, "acct"))
        for (unchanged in listOf(
            """{"user_id":"{\"device_id\":\"d\"}"}""",
            """{"user_id":"plain"}""",
            """{"user_id":5}""",
            """{"other":1}""",
            "\"a string\"",
            "nul",
        )) {
            assertNull(ClaudeMetadata.withAccount(unchanged, "acct"), unchanged)
        }
    }

    @Test
    fun aSharedChatGptAccountServesResponsesWithNothingStored() = runTest {
        val account = accounts.share(AccountProvider.CODEX, "plus", clock)
        val ready = assertIs<InferencePlan.Ready>(
            plan(
                WireProtocol.RESPONSES,
                """{"model":"gpt-5-codex","input":[]}""",
                mapOf("openai-beta" to "responses=v1"),
            ),
        )
        assertEquals(InferenceProvider.CODEX_SHARED, ready.provider)
        assertEquals("https://codex.test/responses", ready.request.url)
        assertEquals("""{"store":false,"model":"gpt-5-codex","input":[]}""", ready.request.body)
        assertEquals(
            listOf(
                "content-type" to "application/json",
                "accept" to "text/event-stream",
                "accept-encoding" to "identity",
                "originator" to "codex_cli_rs",
                "chatgpt-account-id" to account.providerAccountId,
                "openai-beta" to "responses=v1",
                "authorization" to "Bearer access-plus",
            ),
            ready.request.headers,
        )
        ready.lease?.release()
        val stored = assertIs<InferencePlan.Ready>(plan(WireProtocol.RESPONSES, """{"model":"gpt-5","store":true}"""))
        assertEquals("""{"model":"gpt-5","store":false}""", stored.request.body)
        assertFalse(stored.request.headers.any { it.first == "openai-beta" })
    }

    @Test
    fun keysServeWhenNoSharedAccountDoes() = runTest {
        val anthropic = assertIs<InferencePlan.Ready>(
            plan(
                WireProtocol.MESSAGES,
                """{"model":"claude-opus-5"}""",
                mapOf("anthropic-beta" to ""),
                keys = InstanceProviderKeys(anthropic = "sk-an"),
            ),
        )
        assertEquals(InferenceProvider.ANTHROPIC, anthropic.provider)
        assertNull(anthropic.lease)
        assertEquals(
            listOf(
                "content-type" to "application/json",
                "accept-encoding" to "identity",
                "anthropic-version" to UpstreamRequests.DEFAULT_ANTHROPIC_VERSION,
                "x-api-key" to "sk-an",
            ),
            anthropic.request.headers,
        )
        settings.settings[Gw.orgA] = GatewaySettings(openaiKeySecret = "openai-key", openrouterKeySecret = "router-key")
        val secrets = FakeSecrets(mapOf("openai-key" to "sk-org-openai", "router-key" to "sk-or"))
        val responses =
            assertIs<InferencePlan.Ready>(plan(WireProtocol.RESPONSES, """{"model":"gpt-5"}""", secrets = secrets))
        assertEquals("https://openai.test/v1/responses", responses.request.url)
        assertEquals("Bearer sk-org-openai", responses.request.headers.toMap()["authorization"])
        val chat = assertIs<InferencePlan.Ready>(
            plan(
                WireProtocol.CHAT,
                """{"model":"gpt-5"}""",
                mapOf("accept" to "text/event-stream", "openai-beta" to "x"),
                secrets,
            ),
        )
        assertEquals("https://openai.test/v1/chat/completions", chat.request.url)
        assertEquals(
            listOf(
                "content-type" to "application/json",
                "accept-encoding" to "identity",
                "accept" to "text/event-stream",
                "openai-beta" to "x",
                "authorization" to "Bearer sk-org-openai",
            ),
            chat.request.headers,
        )
        val router =
            assertIs<InferencePlan.Ready>(plan(WireProtocol.CHAT, """{"model":"meta/llama"}""", secrets = secrets))
        assertEquals(InferenceProvider.OPENROUTER, router.provider)
        assertEquals("https://openrouter.test/api/v1/chat/completions", router.request.url)
        assertEquals("Bearer sk-or", router.request.headers.toMap()["authorization"])
    }

    @Test
    fun aStreamedChatCallAsksForItsUsageUnlessTheAgentSetItsOptions() = runTest {
        val keys = InstanceProviderKeys(openai = "sk-op")
        suspend fun body(protocol: WireProtocol, text: String) =
            assertIs<InferencePlan.Ready>(plan(protocol, text, keys = keys)).request.body
        assertEquals(
            """{"stream_options":{"include_usage":true},"model":"gpt-5","stream":true}""",
            body(WireProtocol.CHAT, """{"model":"gpt-5","stream":true}"""),
        )
        val own = """{"model":"gpt-5","stream":true,"stream_options":{"include_usage":false}}"""
        assertEquals(own, body(WireProtocol.CHAT, own), "The agent's options are left as they are")
        assertEquals("""{"model":"gpt-5"}""", body(WireProtocol.CHAT, """{"model":"gpt-5"}"""), "Not streamed")
        val responses = """{"model":"gpt-5","stream":true}"""
        assertEquals(responses, body(WireProtocol.RESPONSES, responses), "Responses streams always carry usage")
    }

    @Test
    fun theOrgCanTurnSharedAccountsOff() = runTest {
        accounts.share(AccountProvider.CLAUDE, "max", clock)
        settings.settings[Gw.orgA] = GatewaySettings(useSharedAccounts = false)
        val ready = assertIs<InferencePlan.Ready>(
            plan(
                WireProtocol.MESSAGES,
                """{"model":"claude-opus-5"}""",
                keys = InstanceProviderKeys(anthropic = "sk-an"),
            ),
        )
        assertEquals(InferenceProvider.ANTHROPIC, ready.provider)
    }

    @Test
    fun withoutAccountNorKeyTheCallIsRefused() = runTest {
        val none = refusal(plan(WireProtocol.MESSAGES, """{"model":"claude-opus-5"}"""))
        assertEquals(RefusalKind.NO_CREDENTIAL, none.kind)
        assertTrue("Anthropic" in none.message)
        settings.settings[Gw.orgA] = GatewaySettings(anthropicKeySecret = "gone")
        assertEquals(
            RefusalKind.NO_CREDENTIAL,
            refusal(plan(WireProtocol.MESSAGES, """{"model":"claude-opus-5"}""")).kind,
        )
        val unreadable = FakeSecrets(mapOf("gone" to "sk")).apply { this.unreadable = true }
        val refused = refusal(plan(WireProtocol.MESSAGES, """{"model":"claude-opus-5"}""", secrets = unreadable))
        assertEquals(RefusalKind.NO_CREDENTIAL, refused.kind)
    }

    @Test
    fun busySharedAccountsAnswerWhenToRetryOrFallBackToAKey() = runTest {
        accounts.share(AccountProvider.CLAUDE, "max", clock)
        accounts.update(accounts.accounts.single().copy(limits = AccountLimits(maxConcurrent = 1)))
        val shared = pool(accounts, clock)
        val busyPlanner = planner(settings, shared)
        val body = """{"model":"claude-opus-5"}"""
        val first = assertIs<InferencePlan.Ready>(busyPlanner.plan(Gw.memberA, call(WireProtocol.MESSAGES, body)))
        val busy = refusal(busyPlanner.plan(Gw.memberA, call(WireProtocol.MESSAGES, body)))
        assertEquals(RefusalKind.NO_CAPACITY, busy.kind)
        assertEquals(clock.now() + 5.seconds, busy.retryAt)
        val withKey =
            planner(settings, shared, keys = InstanceProviderKeys(anthropic = "sk-an"))
        assertEquals(
            InferenceProvider.ANTHROPIC,
            assertIs<InferencePlan.Ready>(withKey.plan(Gw.memberA, call(WireProtocol.MESSAGES, body))).provider,
        )
        first.lease?.release()
    }

    @Test
    fun keysAreChosenPerProviderAndNeverPrinted() {
        val keys = InstanceProviderKeys(anthropic = "a", openai = "o", openrouter = "r")
        val settings = GatewaySettings(anthropicKeySecret = "sa", openaiKeySecret = "so", openrouterKeySecret = "sr")
        val expected = mapOf(
            InferenceProvider.CLAUDE_SHARED to ("a" to "sa"),
            InferenceProvider.ANTHROPIC to ("a" to "sa"),
            InferenceProvider.CODEX_SHARED to ("o" to "so"),
            InferenceProvider.OPENAI to ("o" to "so"),
            InferenceProvider.OPENROUTER to ("r" to "sr"),
        )
        for ((provider, pair) in expected) assertEquals(pair, keys.of(provider) to settings.keySecret(provider))
        assertEquals("InstanceProviderKeys([REDACTED])", keys.toString())
        assertEquals(InferenceProvider.ANTHROPIC, WireProtocol.MESSAGES.keyProvider("x/y"))
        assertEquals(InferenceProvider.OPENAI, WireProtocol.RESPONSES.keyProvider("x/y"))
        assertEquals(InferenceProvider.OPENROUTER, WireProtocol.CHAT.keyProvider("x/y"))
        assertEquals(InferenceProvider.OPENAI, WireProtocol.CHAT.keyProvider("gpt-5"))
        assertEquals("InferenceCall(CHAT, 2 chars)", call(WireProtocol.CHAT, "{}").toString())
    }
}
