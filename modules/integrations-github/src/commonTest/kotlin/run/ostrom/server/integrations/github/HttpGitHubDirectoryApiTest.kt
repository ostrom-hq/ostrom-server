// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubTeam
import run.ostrom.server.integrations.github.infrastructure.AppJwt
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubApi
import run.ostrom.server.integrations.github.infrastructure.instantOrNull
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The directory and the manifest conversion of [HttpGitHubApi], on a mock of GitHub. */
class HttpGitHubDirectoryApiTest {
    private val clock = SettableClock()
    private val calls = mutableListOf<HttpRequestData>()
    private var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("unset") }

    // The permissions GitHub refuses to the installation (422).
    private var refused = emptySet<String>()

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun api(apiUrl: String = "https://api.github.test"): HttpGitHubApi {
        val pem = pem()
        val engine = MockEngine { request ->
            calls += request
            if (request.url.encodedPath.endsWith("/access_tokens")) {
                val asked = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    .getValue("permissions").jsonObject.keys
                if (asked.any { it in refused }) {
                    json("""{"message":"permissions not granted"}""", HttpStatusCode.UnprocessableEntity)
                } else {
                    val expires = clock.now + 60.minutes
                    val token = "ghs_" + asked.joinToString("_")
                    json("""{"token":"$token","expires_at":"$expires"}""", HttpStatusCode.Created)
                }
            } else {
                handler(request)
            }
        }
        val http = HttpClient(engine) { expectSuccess = false }
        val jwt = AppJwt("123", pem, clock)
        return HttpGitHubApi(http, apiUrl, { jwt }, clock, "https://github.test") { null }
    }

    private suspend fun pem(): String = CryptographyProvider.Default.get(RSA.PKCS1)
        .keyPairGenerator(keySize = 2048.bits, digest = SHA256).generateKey().privateKey
        .encodeToByteArray(RSA.PrivateKey.Format.PEM.PKCS1).decodeToString()

    private fun repoJson(name: String) =
        """{"name":"$name","description":null,"language":"Go","default_branch":"trunk","archived":false,""" +
            """"pushed_at":"2026-10-29T12:00:00Z","private":true}"""

    private fun teamsJson(slugs: List<String>, next: String?) = """{"data":{"organization":{"teams":{""" +
        """"pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" } ?: "null"}},"nodes":[""" +
        slugs.joinToString(",") {
            """{"slug":"$it","name":"${it.uppercase()}","members":{"nodes":[{"login":"dev"}]},""" +
                """"repositories":{"nodes":[{"name":"api"}]}}"""
        } + "]}}}}"

    @Test
    fun theRepositoriesAreReadPageByPageWithAMetadataOnlyToken() = runTest {
        handler = { request ->
            val page = request.url.parameters["page"]?.toInt() ?: 0
            val count = if (page == 1) 100 else 2
            val names = (1..count).map { "r$page-$it" }
            json("""{"total_count":102,"repositories":[${names.joinToString(",") { repoJson(it) }}]}""")
        }
        val repos = api().repositories(42)
        assertEquals(102, repos.size)
        val first = repos.first()
        assertEquals("r1-1" to "trunk", first.name to first.defaultBranch)
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), first.pushedAt)
        assertNull(first.description)
        val token = calls.first()
        val permissions = Json.parseToJsonElement((token.body as TextContent).text).jsonObject.getValue("permissions")
        assertEquals("""{"metadata":"read"}""", permissions.toString())
        assertEquals("Bearer ghs_metadata", calls[1].headers[HttpHeaders.Authorization])
        assertEquals("per_page=100&page=2", calls.last().url.encodedQuery)
    }

    @Test
    fun aVeryLargeInstallationStopsAtThirtyPages() = runTest {
        handler = { json("""{"repositories":[${(1..100).joinToString(",") { repoJson("r$it") }}]}""") }
        assertEquals(3_000, api().repositories(42).size)
    }

    @Test
    fun theTeamsAreReadByGraphQlWithTheirMembersAndRepositories() = runTest {
        handler = { request ->
            assertEquals("/graphql", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val after = body.getValue("variables").jsonObject["after"]?.jsonPrimitive?.contentOrNull
            json(if (after == null) teamsJson(listOf("a", "b"), "c1") else teamsJson(listOf("c"), null))
        }
        val teams = api().teams(42, "acme")
        assertEquals(listOf("a", "b", "c"), teams.map { it.slug })
        assertEquals(GitHubTeam("a", "A", listOf("dev"), listOf("api")), teams.first())
        val query = Json.parseToJsonElement((calls[1].body as TextContent).text).jsonObject
        assertEquals("acme", query.getValue("variables").jsonObject.getValue("login").jsonPrimitive.content)
        assertTrue("teams(first: 100" in query.getValue("query").jsonPrimitive.content)
        assertEquals("Bearer ghs_members_metadata", calls[1].headers[HttpHeaders.Authorization])

        handler = { json(teamsJson(listOf("t"), "again")) }
        assertEquals(30, api().teams(42, "acme").size, "Thirty pages at most")
    }

    @Test
    fun aUserAccountOrAnAppWithoutTheMembersHasNoTeams() = runTest {
        handler = { json("""{"data":{"organization":null},"errors":[{"type":"NOT_FOUND"}]}""") }
        assertEquals(emptyList(), api().teams(42, "dev"))
        handler = { json("""{"errors":[{"type":"NOT_FOUND"}]}""") }
        assertEquals(emptyList(), api().teams(42, "dev"))
        refused = setOf("members")
        calls.clear()
        assertEquals(emptyList(), api().teams(42, "acme"))
        assertEquals(1, calls.size, "No GraphQL call without a token")
    }

    @Test
    fun aRefusedTokenFailsTheRepositoriesAndTheChecks() = runTest {
        refused = setOf("metadata", "checks")
        val repos = assertFailsWith<GitHubCallException> { api().repositories(42) }
        assertEquals("GitHub refused the installation token (422).", repos.message)
        assertFailsWith<GitHubCallException> { api().pullRequest(42, "acme/api", 6) }
    }

    @Test
    fun gitHubEnterpriseServerAnswersGraphQlNextToItsRestApi() = runTest {
        handler = { json(teamsJson(emptyList(), null)) }
        api("https://ghe.acme.dev/api/v3").teams(42, "acme")
        assertEquals("https://ghe.acme.dev/api/graphql", calls.last().url.toString())
        api("https://api.github.test").teams(42, "acme")
        assertEquals("https://api.github.test/graphql", calls.last().url.toString())
    }

    @Test
    fun aManifestCodeIsExchangedOnceWithoutACredential() = runTest {
        handler = { request ->
            when (request.url.encodedPath) {
                "/app-manifests/good/conversions" -> json(
                    """{"id":77,"slug":"ostrom-acme","owner":{"login":"acme"},"pem":"PEM","webhook_secret":"hook",""" +
                        """"client_id":"Iv1.x","client_secret":"cs","name":"Ostrom"}""",
                    HttpStatusCode.Created,
                )
                "/app-manifests/bare/conversions" -> json(
                    """{"id":78,"slug":"o","pem":"PEM","client_id":"Iv1.y","client_secret":"cs"}""",
                    HttpStatusCode.Created,
                )
                "/app-manifests/used/conversions" -> json("{}", HttpStatusCode.UnprocessableEntity)
                "/app-manifests/down/conversions" -> json("{}", HttpStatusCode.InternalServerError)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val github = api()
        val app = github.convertManifest("good")
        assertEquals(77L to "acme", app?.id to app?.owner)
        assertEquals("hook" to "cs", app?.webhookSecret to app?.clientSecret)
        assertNull(calls.last().headers[HttpHeaders.Authorization], "The code is the proof")
        val bare = github.convertManifest("bare")
        assertEquals(null to null, bare?.owner to bare?.webhookSecret)
        assertNull(github.convertManifest("unknown"))
        assertNull(github.convertManifest("used"))
        assertFailsWith<GitHubCallException> { github.convertManifest("down") }
    }

    @Test
    fun aMalformedTimestampIsDroppedNotFatal() {
        assertNull(instantOrNull(null))
        assertNull(instantOrNull("yesterday"))
        assertEquals(Instant.parse("2026-10-29T12:00:00Z"), instantOrNull("2026-10-29T12:00:00Z"))
    }
}
