package dev.ostrom.server.sharing

import dev.ostrom.server.sharing.domain.AccountCredentials
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountTokenCipher
import dev.ostrom.server.sharing.domain.RefreshResult
import dev.ostrom.server.sharing.infrastructure.HttpOAuthRefresher
import dev.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SharingInfrastructureTest {
    private val clock = TestClock()
    private val old = AccountCredentials("old-access", "old-refresh", clock.now())
    private val seen = mutableListOf<Pair<String, String>>()

    private fun refresher(status: HttpStatusCode, body: String, hang: Boolean = false) = HttpOAuthRefresher(
        HttpClient(
            MockEngine { request ->
                seen += request.url.toString() to (request.body as TextContent).text
                if (hang) awaitCancellation()
                respond(body, status)
            },
        ),
        clock,
    )

    @Test
    fun aRefreshUsesThePublicClientOfEachProvider() = runTest {
        val renewed = refresher(HttpStatusCode.OK, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":28800}""")
            .refresh(AccountProvider.CLAUDE, old)
        val credentials = assertIs<RefreshResult.Renewed>(renewed).credentials
        assertEquals("new-access", credentials.accessToken)
        assertEquals("new-refresh", credentials.refreshToken)
        assertEquals(clock.now() + 8.hours, credentials.expiresAt)
        val (url, body) = seen.single()
        assertEquals("https://platform.claude.com/v1/oauth/token", url)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("old-refresh", json["refresh_token"]!!.jsonPrimitive.content)
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", json["client_id"]!!.jsonPrimitive.content)

        seen.clear()
        val codex = refresher(HttpStatusCode.OK, """{"access_token":"c","expires_in":60}""").refresh(AccountProvider.CODEX, old)
        assertEquals("old-refresh", assertIs<RefreshResult.Renewed>(codex).credentials.refreshToken, "A missing refresh token keeps the old one")
        assertEquals("https://auth.openai.com/oauth/token", seen.single().first)
        assertTrue("app_EMoamEEZ73f0CkXaXp7hrann" in seen.single().second)
    }

    @Test
    fun refusalsAndFailuresAreClassified() = runTest {
        assertEquals(RefreshResult.Revoked, refresher(HttpStatusCode.BadRequest, "{}").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Revoked, refresher(HttpStatusCode.Unauthorized, "{}").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Unavailable, refresher(HttpStatusCode.ServiceUnavailable, "{}").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Unavailable, refresher(HttpStatusCode.OK, "not json").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Unavailable, refresher(HttpStatusCode.OK, "[1]").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Unavailable, refresher(HttpStatusCode.OK, """{"access_token":"a"}""").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Unavailable, refresher(HttpStatusCode.OK, """{"access_token":"","expires_in":5}""").refresh(AccountProvider.CLAUDE, old))
        assertEquals(RefreshResult.Unavailable, refresher(HttpStatusCode.OK, "{}", hang = true).refresh(AccountProvider.CLAUDE, old))
        val down = HttpOAuthRefresher(HttpClient(MockEngine { error("connection refused") }), clock)
        assertEquals(RefreshResult.Unavailable, down.refresh(AccountProvider.CODEX, old))
    }

    @Test
    fun claudeHeadersBecomeWindows() {
        val now = clock.now()
        val reset = now.epochSeconds + 600
        val windows = ProviderQuotaHeaderParser().parse(
            AccountProvider.CLAUDE,
            mapOf(
                "anthropic-ratelimit-unified-5h-utilization" to "0.3",
                "anthropic-ratelimit-unified-5h-reset" to "$reset",
                "anthropic-ratelimit-unified-7d-status" to "rejected",
                "anthropic-ratelimit-unified-7d_oi-utilization" to "1.7",
                "anthropic-ratelimit-unified-7d_oi-reset" to "${now.epochSeconds - 1}",
            ),
            now,
        )
        assertEquals(listOf("five_hour" to 0.3, "seven_day" to 1.0), windows.map { it.window to it.utilization })
        assertEquals(Instant.fromEpochSeconds(reset), windows.first().resetsAt)
        assertNull(windows.last().resetsAt)
        assertEquals(emptyList(), ProviderQuotaHeaderParser().parse(AccountProvider.CLAUDE, mapOf("anthropic-ratelimit-unified-5h-utilization" to "NaN"), now))
    }

    @Test
    fun codexHeadersAreClassifiedByWindowLength() {
        val now = clock.now()
        val windows = ProviderQuotaHeaderParser().parse(
            AccountProvider.CODEX,
            mapOf(
                "x-codex-primary-used-percent" to "25",
                "x-codex-primary-window-minutes" to "300",
                "x-codex-primary-reset-at" to "${now.epochSeconds + 100}",
                "x-codex-secondary-used-percent" to "50",
                "x-codex-secondary-window-minutes" to "10080",
                "x-codex-gpt-5-primary-used-percent" to "90",
                "x-codex-gpt-5-primary-window-minutes" to "10080",
                "x-codex-odd-primary-used-percent" to "1",
                "x-codex-odd-primary-window-minutes" to "60",
                "x-codex-nolength-secondary-used-percent" to "1",
                "x-other" to "1",
            ),
            now,
        )
        assertEquals(
            setOf("five_hour" to 0.25, "seven_day" to 0.5, "model:gpt-5:seven_day" to 0.9),
            windows.map { it.window to it.utilization }.toSet(),
        )
    }

    @Test
    fun sealedTokensOpenOnlyForTheirAccount() = runTest {
        val cipher = AccountTokenCipher(ContextCipher())
        val account = Uuid.random()
        val sealed = cipher.seal(old, Sharing.orgA, account)
        assertEquals("old-access", cipher.open(sealed, Sharing.orgA, account)!!.accessToken)
        assertNull(cipher.open(sealed, Sharing.orgA, Uuid.random()))
        assertNull(cipher.open(sealed, Sharing.orgB, account))
        val notJson = ContextCipher().seal("nope".encodeToByteArray(), "shared-account:${Sharing.orgA}:$account")
        assertNull(cipher.open(notJson, Sharing.orgA, account))
        assertEquals("AccountCredentials([REDACTED])", old.toString())
    }
}
