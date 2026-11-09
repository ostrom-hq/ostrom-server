// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.infrastructure.HttpGitHubContentsApi
import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryTree
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** [HttpGitHubContentsApi] on a mock of GitHub: read-only calls, empty repositories, 404s and the rate limits. */
class HttpGitHubContentsApiTest {
    private val clock = SettableClock()
    private val calls = mutableListOf<HttpRequestData>()
    private val asked = mutableListOf<Map<String, String>>()
    private val pauses = mutableListOf<Duration>()
    private val answers = mutableMapOf<String, ArrayDeque<Answer>>()
    private var token: String? = "ghs_contents"

    private class Answer(val status: HttpStatusCode, val body: String, val headers: Map<String, String>)

    // The answers of a path, in order; the last one stays. A path with none is a 404.
    private fun answer(
        path: String,
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Map<String, String> = emptyMap(),
    ) {
        answers.getOrPut("/repos/acme/api$path") { ArrayDeque() }.addLast(Answer(status, body, headers))
    }

    // `waits`: the API waits with its own default, on the test's virtual clock, rather than recording the pauses.
    private fun api(maxWait: Duration = 60.seconds, waits: Boolean = false): HttpGitHubContentsApi {
        val engine = MockEngine { request ->
            calls += request
            val queue = answers[request.url.encodedPath]
            val next = if (queue != null && queue.size > 1) queue.removeFirst() else queue?.firstOrNull()
            val headers = next?.headers.orEmpty() + (HttpHeaders.ContentType to "application/json")
            respond(
                next?.body ?: """{"message":"Not Found"}""",
                next?.status ?: HttpStatusCode.NotFound,
                headersOf(*headers.map { (name, value) -> name to listOf(value) }.toTypedArray()),
            )
        }
        val http = HttpClient(engine) { expectSuccess = false }
        val tokens: suspend (Long, Map<String, String>) -> String? = { id, wanted ->
            assertEquals(42L, id)
            asked += wanted
            token
        }
        val url = "https://api.github.test"
        return if (waits) {
            HttpGitHubContentsApi(http, url, clock, maxWait, tokens = tokens)
        } else {
            HttpGitHubContentsApi(http, url, clock, maxWait, { pauses += it }, tokens)
        }
    }

    private fun branch(sha: String = TREE, protected: Boolean = false, protection: String? = null) =
        """{"name":"main","commit":{"sha":"$SHA","commit":{"tree":{"sha":"$sha"}}},"protected":$protected""" +
            (protection?.let { ""","protection":$it""" } ?: "") + "}"

    private fun file(text: String, type: String = "file", encoding: String = "base64", size: Int = text.length) =
        """{"type":"$type","size":$size,"encoding":"$encoding","content":"$text"}"""

    @Test
    fun aTreeListsTheFilesOfTheBranchWithAReadOnlyToken() = runTest {
        answer("/branches/release%2F1", branch())
        val entries = """{"path":"src","type":"tree"},{"path":"src/App.kt","type":"blob"},""" +
            """{"path":".github/CODEOWNERS","type":"blob"}"""
        answer("/git/trees/$TREE", """{"sha":"$TREE","tree":[$entries],"truncated":true}""")

        val tree = api().tree(42, "acme/api", "release/1")

        assertEquals(RepositoryTree(listOf("src/App.kt", ".github/CODEOWNERS"), truncated = true), tree)
        assertEquals(listOf(mapOf("contents" to "read", "metadata" to "read")), asked.distinct())
        assertTrue(calls.all { it.method == HttpMethod.Get }, "Nothing is ever written")
        assertTrue(calls.all { it.headers[HttpHeaders.Authorization] == "Bearer ghs_contents" })
        assertEquals("recursive=1", calls.last().url.encodedQuery)
    }

    @Test
    fun anEmptyRepositoryOrAnUnknownBranchHasNoFiles() = runTest {
        val api = api()
        // An empty repository has no branch yet.
        assertEquals(RepositoryTree(emptyList(), false), api.tree(42, "acme/api", "main"))
        answer("/branches/main", branch())
        // The tree of an empty repository answers 409.
        answer("/git/trees/$TREE", """{"message":"Git Repository is empty."}""", HttpStatusCode.Conflict)
        assertEquals(RepositoryTree(emptyList(), false), api.tree(42, "acme/api", "main"))
        answers.remove("/repos/acme/api/git/trees/$TREE")
        assertEquals(RepositoryTree(emptyList(), false), api.tree(42, "acme/api", "main"))
    }

    @Test
    fun anUnexpectedAnswerOrARefusedTokenFails() = runTest {
        answer("/branches/main", branch(sha = "../../x"))
        assertEquals("GitHub answered an unexpected branch.", fails { api().tree(42, "acme/api", "main") })
        answers.clear()
        answer("/branches/main", "not json")
        assertEquals("GitHub answered an unexpected branch.", fails { api().tree(42, "acme/api", "main") })
        answers.clear()
        answer("/branches/main", """{"message":"boom"}""", HttpStatusCode.InternalServerError)
        assertEquals("GitHub refused the branch (500).", fails { api().tree(42, "acme/api", "main") })

        token = null
        val refused = fails { api().file(42, "acme/api", "main", "CODEOWNERS") }
        assertTrue("may not read the repositories' contents" in refused, refused)
    }

    @Test
    fun aFileIsReadOnlyAsSmallText() = runTest {
        val owners = "* @acme/platform\n"
        val encoded = Base64.encode(owners.encodeToByteArray()).chunked(4).joinToString("\\n")
        answer("/contents/docs/a%20b.md", file(encoded))
        val api = api()
        assertEquals(owners, api.file(42, "acme/api", "trunk", "docs/a b.md"))
        assertEquals("ref=trunk", calls.last().url.encodedQuery)
        assertNull(api.file(42, "acme/api", "trunk", "missing.md"), "404")
        assertNull(api.file(42, "acme/api", "trunk", "../secrets"), "Never outside the repository")
        assertNull(api.file(42, "acme/api", "trunk", "docs//x"))
        assertNull(api.file(42, "acme/api", "trunk", "./x"))
        assertEquals(2, calls.size, "A path out of the tree is never asked for")

        for (body in listOf(
            file("", type = "dir"),
            file("", encoding = "none", size = 2_000_000),
            file(encoded, size = 600_000),
            file("Q"),
            file(Base64.encode(byteArrayOf(0x50, 0x4B, 0x00, 0x03))),
        )) {
            answers.clear()
            answer("/contents/blob", body)
            assertNull(api.file(42, "acme/api", "trunk", "blob"), body)
        }
    }

    @Test
    fun theProtectionJoinsTheClassicProtectionAndTheRulesets() = runTest {
        val classic = """{"enabled":true,"required_status_checks":{"contexts":["build"]}}"""
        answer("/branches/main", branch(protected = true, protection = classic))
        val rules = listOf(
            """{"type":"pull_request","parameters":{"required_approving_review_count":2,""" +
                """"require_code_owner_review":true}}""",
            """{"type":"pull_request"}""",
            """{"type":"pull_request","parameters":{"required_approving_review_count":"two",""" +
                """"require_code_owner_review":false}}""",
            """{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"build"},""" +
                """{"context":"lint"},{"integration_id":1},"loose"]}}""",
            """{"type":"required_status_checks"}""",
            """{"type":"deletion"}""",
        )
        answer("/rules/branches/main", rules.joinToString(",", "[", "]"))

        val protection = api().protection(42, "acme/api", "main")

        assertEquals(BranchProtection(true, 2, true, listOf("build", "lint"), blocksForcePush = true), protection)
        assertEquals("per_page=100", calls.last().url.encodedQuery)
    }

    @Test
    fun anUnprotectedOrUnreadableBranchSaysSo() = runTest {
        val api = api()
        assertNull(api.protection(42, "acme/api", "main"), "A branch GitHub does not show")

        answer("/branches/main", branch())
        assertEquals(BranchProtection(false, 0, false, emptyList(), false), api.protection(42, "acme/api", "main"))

        answers.clear()
        answer("/branches/main", branch(protection = """{"enabled":false}"""))
        answer("/rules/branches/main", """[{"type":"non_fast_forward"}]""")
        assertEquals(BranchProtection(true, 0, false, emptyList(), true), api.protection(42, "acme/api", "main"))

        answers.clear()
        answer("/branches/main", branch(protection = """{"required_status_checks":null}"""))
        assertEquals(BranchProtection(false, 0, false, emptyList(), false), api.protection(42, "acme/api", "main"))

        answers.clear()
        answer("/branches/main", branch())
        answer("/rules/branches/main", """[{"type":"pull_request","parameters":{}}]""")
        assertEquals(BranchProtection(true, 0, false, emptyList(), false), api.protection(42, "acme/api", "main"))
    }

    @Test
    fun aRateLimitIsWaitedOutAsGitHubSays() = runTest {
        val reset = (clock.now + 30.seconds).epochSeconds.toString()
        val limited = """{"message":"API rate limit exceeded"}"""
        answer("/branches/main", limited, HttpStatusCode.Forbidden, RATE + ("x-ratelimit-reset" to reset))
        answer("/branches/main", limited, HttpStatusCode.TooManyRequests, mapOf("retry-after" to "5"))
        answer("/branches/main", limited, HttpStatusCode.TooManyRequests)
        answer("/branches/main", branch())
        assertEquals(RepositoryTree(emptyList(), false), api().tree(42, "acme/api", "main"))
        assertEquals(listOf(31.seconds, 5.seconds, 60.seconds), pauses)

        answers.clear()
        val past = (clock.now - 1.hours).epochSeconds.toString()
        answer("/branches/main", limited, HttpStatusCode.Forbidden, RATE + ("x-ratelimit-reset" to past))
        answer("/branches/main", branch())
        assertEquals(RepositoryTree(emptyList(), false), api().tree(42, "acme/api", "main"))
        assertEquals(1.seconds, pauses.last(), "A reset already past waits a second")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun byDefaultTheWaitIsADelayOfTheCoroutine() = runTest {
        answer("/branches/main", "{}", HttpStatusCode.TooManyRequests, mapOf("retry-after" to "5"))
        answer("/branches/main", branch())
        val start = testScheduler.currentTime
        assertEquals(RepositoryTree(emptyList(), false), api(waits = true).tree(42, "acme/api", "main"))
        assertEquals(5_000L, testScheduler.currentTime - start, "Five seconds, on the test's clock")
        assertEquals(emptyList(), pauses)
    }

    @Test
    fun aRateLimitTooLongOrTooRepeatedFailsTheReadAndAPermissionRefusalWaitsForNothing() = runTest {
        val limited = """{"message":"API rate limit exceeded"}"""
        val reset = (clock.now + 1.hours).epochSeconds.toString()
        answer("/branches/main", limited, HttpStatusCode.Forbidden, RATE + ("x-ratelimit-reset" to reset))
        val tooLong = fails { api().tree(42, "acme/api", "main") }
        assertTrue("start the analysis again in 61 minutes" in tooLong, tooLong)
        assertEquals(emptyList(), pauses)

        answers.clear()
        answer("/branches/main", limited, HttpStatusCode.TooManyRequests, mapOf("retry-after" to "1"))
        assertTrue("rate limit" in fails { api().tree(42, "acme/api", "main") })
        assertEquals(3, pauses.size, "Three waits, then the read fails")

        for (headers in listOf(
            emptyMap(),
            mapOf("x-ratelimit-remaining" to "12", "x-ratelimit-reset" to reset),
            RATE + ("x-ratelimit-reset" to "soon"),
            mapOf("retry-after" to "later"),
        )) {
            answers.clear()
            pauses.clear()
            answer("/branches/main", """{"message":"Resource not accessible"}""", HttpStatusCode.Forbidden, headers)
            assertEquals("GitHub refused the branch (403).", fails { api().tree(42, "acme/api", "main") })
            assertNull(api().protection(42, "acme/api", "main"))
            assertEquals(emptyList(), pauses, "$headers")
        }
    }

    private suspend fun fails(block: suspend () -> Unit): String =
        assertFailsWith<GitHubCallException> { block() }.message.orEmpty()

    private companion object {
        const val TREE = "fedcba9876543210fedcba9876543210fedcba98"
        val RATE = mapOf("x-ratelimit-remaining" to "0")
    }
}
