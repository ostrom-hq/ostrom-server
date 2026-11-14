// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.infrastructure.AWAITING_SIGN_IN
import run.ostrom.server.sharing.infrastructure.HttpOAuthRefresher
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
        // Real time: a short timeout only where the provider hangs, a cold JVM's first call can take longer.
        timeout = if (hang) 300.milliseconds else 30.seconds,
    )

    @Test
    fun aStaleWriteKeepsExactlyTheStatusesOnlySharingAgainLeaves() {
        assertEquals(AccountStatus.entries.filter { it.awaitsSignIn }.joinToString { "'${it.name}'" }, AWAITING_SIGN_IN)
    }

    @Test
    fun aRefreshUsesThePublicClientOfEachProvider() = realTime {
        val renewed = refresher(
            HttpStatusCode.OK,
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":28800}""",
        )
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
        val codex = refresher(
            HttpStatusCode.OK,
            """{"access_token":"c","expires_in":60}""",
        ).refresh(AccountProvider.CODEX, old)
        assertEquals(
            "old-refresh",
            assertIs<RefreshResult.Renewed>(codex).credentials.refreshToken,
            "A missing refresh token keeps the old one",
        )
        assertEquals("https://auth.openai.com/oauth/token", seen.single().first)
        assertEquals(AccountProvider.entries.toSet(), HttpOAuthRefresher.ENDPOINTS.keys)
        assertTrue("app_EMoamEEZ73f0CkXaXp7hrann" in seen.single().second)
    }

    @Test
    fun aRefreshSaysWhoseTokensTheyAre() = realTime {
        suspend fun accountOf(provider: AccountProvider, extra: String, access: String = "a"): String? {
            val body = """{"access_token":"$access","expires_in":60$extra}"""
            val renewed = refresher(HttpStatusCode.OK, body).refresh(provider, old)
            return assertIs<RefreshResult.Renewed>(renewed).accountId
        }
        val claude = ""","account":{"uuid":"claude-uuid","email_address":"lyes@example.com"}"""
        assertEquals("claude-uuid", accountOf(AccountProvider.CLAUDE, claude))
        for (extra in listOf("", ""","account":"claude-uuid"""", ""","account":{}""", ""","account":{"uuid":" "}""")) {
            assertNull(accountOf(AccountProvider.CLAUDE, extra), "Claude's answer names no account: $extra")
        }
        val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        fun jwt(claims: String) = "header.${urlSafe.encode(claims.encodeToByteArray())}.signature"
        val auth = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"chatgpt-1"}}""")
        assertEquals("chatgpt-1", accountOf(AccountProvider.CODEX, ""","id_token":"$auth""""))
        assertEquals("chatgpt-1", accountOf(AccountProvider.CODEX, "", access = auth), "The access token is a JWT too")
        assertEquals(
            "chatgpt-1",
            accountOf(AccountProvider.CODEX, ""","id_token":"${jwt("{}")}"""", access = auth),
            "An id_token without the claim falls back on the access token",
        )
        for (idToken in listOf(
            "not-a-jwt",
            "header.!!!.signature",
            jwt("{"),
            jwt("[1]"),
            jwt("{}"),
            jwt("""{"https://api.openai.com/auth":"chatgpt-1"}"""),
            jwt("""{"https://api.openai.com/auth":{}}"""),
        )) {
            assertNull(accountOf(AccountProvider.CODEX, ""","id_token":"$idToken""""), "No account in $idToken")
        }
        assertNull(accountOf(AccountProvider.CODEX, ""), "No id_token, an opaque access token")
    }

    @Test
    fun theInstanceSettingsPointTheTokenEndpointsElsewhere() {
        assertEquals(HttpOAuthRefresher.ENDPOINTS, SharingSettings.tokenEndpoints(SettingsValues.EMPTY))
        val claude = "claudeTokenUrl"
        val field = checkNotNull(SharingSettings.SECTION.field(claude))
        fun claudeAt(url: String): String {
            assertNull(field.check(url.trim()), url)
            return SharingSettings.tokenEndpoints(SettingsValues(mapOf(claude to url))).getValue(AccountProvider.CLAUDE)
        }
        val accepted = listOf(
            "https://proxy.example/oauth/token",
            "HTTPS://Proxy.Example/oauth/token",
            "http://localhost:8080/oauth/token",
            "http://127.0.0.1/oauth/token",
            "http://provider.localhost/oauth/token",
            "http://provider.localtest.me:8080/v1/oauth/token",
            "http://LOCALHOST:65535/oauth/token",
            "https://proxy.example?tenant=a",
            "https://proxy.example",
        )
        for (url in accepted) assertEquals(url, claudeAt(" $url "), "Accepted, trimmed: $url")
        val refused = listOf(
            "https://",
            "https://host with space/oauth/token",
            "http://proxy.internal/oauth/token",
            "http://localhost@evil.example/oauth/token",
            "ftp://x",
            // The host is the parsed one, never a suffix of the string: a fragment, credentials or a backslash
            // can never make a remote host pass for a local one.
            "http://evil.example#@localhost/oauth/token",
            "http://evil.example#.localhost/oauth/token",
            "http://evil.example/#@localhost",
            "http://evil.example?@localhost/oauth/token",
            "http://evil.example\\@localhost/oauth/token",
            "http://evil.example\\.localhost/oauth/token",
            "http://user:secret@localhost/oauth/token",
            "https://user:secret@proxy.example/oauth/token",
            "http://localhost:secret@evil.example/oauth/token",
            "http://localhost.evil.example/oauth/token",
            "http://evil.example/.localhost",
            "http://evil.example:8080/localhost",
            "http://[::1]/oauth/token",
            "http://localhost:/oauth/token",
            "http://localhost:99999/oauth/token",
            "http://localhost:80x/oauth/token",
            "https:///oauth/token",
            "https://./oauth/token",
            "https://proxy..example/oauth/token",
            "https://proxy.example./oauth/token",
            "https://token_proxy/oauth/token",
            "//localhost/oauth/token",
        )
        for (url in refused) {
            assertEquals(
                "$claude must be an https URL, or http to a local host (localhost, 127.0.0.1, *.localhost, " +
                    "*.localtest.me), with a plain host name or IPv4 address, a port up to 65535, and no " +
                    "credentials (@), fragment (#) or backslash.",
                field.check(url),
                url,
            )
        }
    }

    @Test
    fun theSharingSectionImportsBothTokenEndpointsAndChecksEach() {
        assertEquals(
            listOf(
                "claudeTokenUrl" to "OSTROM_SHARING_CLAUDE_TOKEN_URL",
                "codexTokenUrl" to "OSTROM_SHARING_CODEX_TOKEN_URL",
            ),
            SharingSettings.SECTION.fields.map { it.name to it.legacyVariable },
        )
        assertEquals("sharing", SharingSettings.SECTION.name)
        assertNotNull(checkNotNull(SharingSettings.SECTION.field("codexTokenUrl")).check("http://proxy.internal/t"))
    }

    @Test
    fun theModuleReadsTheTokenEndpointsTheConsoleStoresAtEachRefresh() = realTime {
        val urls = mutableListOf<String>()
        var stored = "https://proxy.example/oauth/token"
        val app = koinApplication {
            modules(
                SharingModule.koinModule,
                module {
                    single<Clock> { clock }
                    single<InstanceSettings> {
                        InstanceSettings { section ->
                            assertEquals("sharing", section)
                            SettingsValues(mapOf("codexTokenUrl" to stored))
                        }
                    }
                    single(SharingModule.HTTP_CLIENT) {
                        HttpClient(MockEngine { request -> urls += request.url.toString(); respond("{}") })
                    }
                },
            )
        }
        val refresher = app.koin.get<OAuthRefresher>()
        refresher.refresh(AccountProvider.CODEX, old)
        stored = "https://other.example/oauth/token"
        refresher.refresh(AccountProvider.CODEX, old)
        assertEquals(listOf("https://proxy.example/oauth/token", "https://other.example/oauth/token"), urls)
        assertEquals(listOf("sharing"), app.koin.getAll<SettingsSection>().map { it.name })
        app.close()
    }

    @Test
    fun onlyAnInvalidGrantRetiresTheRefreshToken() = realTime {
        val dead = """{"error":"invalid_grant","error_description":"Refresh token revoked"}"""
        assertEquals(
            RefreshResult.Revoked,
            refresher(HttpStatusCode.BadRequest, dead).refresh(AccountProvider.CLAUDE, old),
        )
        assertEquals(
            RefreshResult.Revoked,
            refresher(HttpStatusCode.Unauthorized, dead).refresh(AccountProvider.CLAUDE, old),
        )
        // Any other refusal is not the owner's token dying: the account must not be retired for it.
        for (body in listOf(
            "{}",
            """{"error":"invalid_request"}""",
            """{"error":"invalid_client"}""",
            "nope",
            "[1]",
            "{",
        )) {
            assertEquals(
                RefreshResult.Unavailable,
                refresher(HttpStatusCode.BadRequest, body).refresh(AccountProvider.CLAUDE, old),
                body,
            )
        }
    }

    @Test
    fun refusalsAndFailuresAreClassified() = realTime {
        assertEquals(
            RefreshResult.Unavailable,
            refresher(HttpStatusCode.ServiceUnavailable, "{}").refresh(AccountProvider.CLAUDE, old),
        )
        assertEquals(
            RefreshResult.Unavailable,
            refresher(HttpStatusCode.OK, "not json").refresh(AccountProvider.CLAUDE, old),
        )
        assertEquals(
            RefreshResult.Unavailable,
            refresher(HttpStatusCode.OK, "[1]").refresh(AccountProvider.CLAUDE, old),
        )
        assertEquals(
            RefreshResult.Unavailable,
            refresher(HttpStatusCode.OK, """{"access_token":"a"}""").refresh(AccountProvider.CLAUDE, old),
        )
        assertEquals(
            RefreshResult.Unavailable,
            refresher(HttpStatusCode.OK, """{"access_token":"","expires_in":5}""").refresh(AccountProvider.CLAUDE, old),
        )
        for (body in listOf(
            """{"access_token":5,"expires_in":5}""",
            """{"expires_in":5}""",
            """{"access_token":"a","expires_in":"soon"}""",
            """{"access_token":"a","expires_in":0}""",
            """{"access_token":"a","expires_in":40000000}""",
        )) {
            assertEquals(
                RefreshResult.Unavailable,
                refresher(HttpStatusCode.OK, body).refresh(AccountProvider.CLAUDE, old),
                body,
            )
        }
        assertEquals(
            RefreshResult.Unavailable,
            refresher(HttpStatusCode.OK, "{}", hang = true).refresh(AccountProvider.CLAUDE, old),
        )
        val down =
            HttpOAuthRefresher(
                HttpClient(
                    MockEngine {
                        error("connection refused")
                    },
                ),
                clock,
                timeout = 300.milliseconds,
            )
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
        assertEquals(
            emptyList(),
            ProviderQuotaHeaderParser().parse(
                AccountProvider.CLAUDE,
                mapOf(
                    "anthropic-ratelimit-unified-5h-utilization" to "NaN",
                ),
                now,
            ),
        )
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
                "x-codex-bad-primary-used-percent" to "abc",
                "x-codex-bad-primary-window-minutes" to "300",
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

    @Test
    fun unreadableClaudeValuesAreSkipped() {
        val now = clock.now()
        val parser = ProviderQuotaHeaderParser()
        val unreadable = parser.parse(
            AccountProvider.CLAUDE,
            mapOf(
                "anthropic-ratelimit-unified-5h-utilization" to "-0.1",
                "anthropic-ratelimit-unified-7d-utilization" to "1e999",
                "anthropic-ratelimit-unified-7d_oi-utilization" to "abc",
            ),
            now,
        )
        assertEquals(emptyList(), unreadable)
        val resets = parser.parse(
            AccountProvider.CLAUDE,
            mapOf(
                "anthropic-ratelimit-unified-5h-utilization" to "0.1",
                "anthropic-ratelimit-unified-5h-reset" to "soon",
                "anthropic-ratelimit-unified-7d-utilization" to "0.2",
                "anthropic-ratelimit-unified-7d-reset" to "0",
                "anthropic-ratelimit-unified-7d_oi-utilization" to "0.3",
                "anthropic-ratelimit-unified-7d_oi-reset" to "${Long.MAX_VALUE}",
            ),
            now,
        )
        assertEquals(listOf<Instant?>(null, null, null), resets.map { it.resetsAt }, "An unreadable reset is no reset")
    }
}

/** Runs a test on real time: the refresher bounds each request, and `runTest`'s virtual time would fire it early. */
fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }
