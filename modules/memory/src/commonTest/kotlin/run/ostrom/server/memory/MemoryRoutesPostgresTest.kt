// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemorySearchResult
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.Callers
import run.ostrom.server.memory.fakes.Tokens
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memory's routes on a real PostgreSQL: a person or a credential with `memory:use` reads the organization's
 * entries and their own, never another member's personal ones (404, as an unknown entry), and only a person writes or
 * changes the organization's.
 */
class MemoryRoutesPostgresTest {
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<CallerResolver> { Callers }
    }

    private fun memoryTest(block: suspend HttpClient.(TestServer) -> Unit) =
        serverTest(listOf(MemoryModule), overrides) { server -> server.client.block(server) }

    private fun HttpRequestBuilder.json(token: String?, body: String, agent: Boolean = false) {
        token?.let { bearerAuth(it) }
        if (agent) header("x-ostrom-origin", "agent")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.create(token: String?, body: String, agent: Boolean = false): HttpResponse =
        post(MEMORY) { json(token, body, agent) }

    private suspend fun HttpClient.created(token: String, body: String): MemoryEntry {
        val response = create(token, body)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.read(token: String?, path: String): HttpResponse =
        get(path) { token?.let { bearerAuth(it) } }

    private suspend fun HttpClient.ids(token: String, path: String): List<String> =
        read(token, path).body<MemoryIndexPage>().entries.map { it.id }

    private suspend fun HttpClient.found(token: String, path: String): List<String> =
        read(token, path).body<MemorySearchResult>().entries.map { it.id }

    private suspend fun HttpResponse.errorType(): String = body<ApiError>().error.type

    @Test
    fun anEntryIsWrittenListedSearchedAndReadOnDemand() = memoryTest {
        val team = created("ada", entry("Deploy order", "team", """["deploy", "core"]"""))
        val mine = created("ada", entry("My editor setup", "personal", """["editor"]"""))
        assertEquals("human", team.origin)
        assertEquals(listOf("core", "deploy"), team.tags)
        assertEquals(ADA, mine.authorId)

        val index = read("ada", "$MEMORY/index").body<MemoryIndexPage>()
        assertEquals(listOf(mine.id, team.id), index.entries.map { it.id })
        assertNull(index.next)
        assertTrue("Merge" !in read("ada", "$MEMORY/index").bodyAsText(), "the index never carries content")
        assertEquals(listOf(team.id), ids("ada", "$MEMORY/index?scope=team"))
        assertEquals(listOf(mine.id), ids("ada", "$MEMORY/index?tags=editor,x"))

        val found = read("ada", "$MEMORY/search?q=deploy%20ORDER").body<MemorySearchResult>()
        assertEquals(listOf(team.id), found.entries.map { it.id })
        assertEquals(listOf(team.id), found("ada", "$MEMORY/search?q=core"))
        assertTrue(read("ada", "$MEMORY/search?q=merge").body<MemorySearchResult>().entries.isEmpty(), "never content")

        val first = read("ada", "$MEMORY/${team.id}").body<MemoryEntry>()
        assertEquals("Merge core, repin, then merge the server.", first.content)
        assertEquals(1, first.readCount)
        assertEquals(2, read("ben", "$MEMORY/${team.id}").body<MemoryEntry>().readCount)
    }

    @Test
    fun aPersonalEntryIsItsOwnersAloneAdministratorsIncluded() = memoryTest {
        val mine = created("ada", entry("My editor setup", "personal", "[]"))
        for (other in listOf("ben", "root", "eve")) {
            assertEquals(HttpStatusCode.NotFound, read(other, "$MEMORY/${mine.id}").status, other)
            assertTrue(read(other, "$MEMORY/index").body<MemoryIndexPage>().entries.isEmpty(), other)
            assertTrue(read(other, "$MEMORY/search?q=editor").body<MemorySearchResult>().entries.isEmpty(), other)
            assertEquals(HttpStatusCode.NotFound, patch("$MEMORY/${mine.id}") { json(other, update(1)) }.status)
            assertEquals(HttpStatusCode.NotFound, delete("$MEMORY/${mine.id}") { bearerAuth(other) }.status)
        }
        // Another organization does not see A's team entries either.
        val team = created("ada", entry("Deploy order", "team", "[]"))
        assertEquals(HttpStatusCode.NotFound, read("eve", "$MEMORY/${team.id}").status)
        assertEquals(HttpStatusCode.OK, read("adakey", "$MEMORY/${mine.id}").status)
        assertEquals(HttpStatusCode.OK, read("agent", "$MEMORY/${mine.id}").status)
    }

    @Test
    fun onlyAPersonWritesOrChangesTheOrganizationsMemory() = memoryTest {
        for (token in listOf("agent", "adakey", "service")) {
            val refused = create(token, entry("Team note", "team", "[]"))
            assertEquals(HttpStatusCode.Forbidden, refused.status, token)
            assertEquals("memory_team_forbidden", refused.errorType())
            assertEquals("agent", created(token, entry("Mine", "personal", "[]")).origin)
        }
        val relayed = create("adacli", entry("Team note", "team", "[]"), agent = true)
        assertEquals("memory_team_forbidden", relayed.errorType(), "a call the relay forwards is an agent's")
        val mine = create("adacli", entry("Mine", "personal", "[]"), agent = true).body<MemoryEntry>()
        assertEquals("agent", mine.origin)
        assertEquals("human", created("adaapp", entry("From the phone", "team", "[]")).origin)

        val team = created("adacli", entry("Deploy order", "team", "[]"))
        assertEquals(HttpStatusCode.Forbidden, patch("$MEMORY/${team.id}") { json("ben", update(1)) }.status)
        assertEquals(HttpStatusCode.Forbidden, patch("$MEMORY/${team.id}") { json("rootkey", update(1)) }.status)
        assertEquals(HttpStatusCode.Forbidden, delete("$MEMORY/${team.id}") { bearerAuth("agent") }.status)
        val changed = patch("$MEMORY/${team.id}") { json("rootcli", update(1)) }
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        assertEquals(2, changed.body<MemoryEntry>().version)
        assertEquals(listOf("new"), changed.body<MemoryEntry>().tags)
        val stale = patch("$MEMORY/${team.id}") { json("ada", update(1)) }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("memory_conflict", stale.errorType())
        assertEquals(HttpStatusCode.NoContent, delete("$MEMORY/${team.id}") { bearerAuth("ada") }.status)
        assertEquals(HttpStatusCode.NotFound, read("ada", "$MEMORY/${team.id}").status)
    }

    @Test
    fun aCredentialWithoutTheMemoryScopeIsRefused() = memoryTest {
        assertEquals(HttpStatusCode.Unauthorized, read(null, "$MEMORY/index").status)
        assertEquals(HttpStatusCode.Unauthorized, create(null, entry("x", "personal", "[]")).status)
        for (token in listOf("adabare", "oldagent")) {
            val refused = read(token, "$MEMORY/index")
            assertEquals(HttpStatusCode.Forbidden, refused.status, token)
            assertEquals("credential_scope", refused.errorType())
        }
        assertEquals(HttpStatusCode.NotFound, read("nobody", "$MEMORY/index").status)
    }

    @Test
    fun aBadRequestNamesItsFieldAndASecretIsNeverStored() = memoryTest {
        val bad = listOf(
            "index?scope=repo", "index?limit=many", "index?after=nope", "index?after=x_y", "index?tags=Bad!", "search",
            "search?q=%3F",
        )
        for (path in bad) {
            val refused = read("ada", "$MEMORY/$path")
            assertEquals(HttpStatusCode.BadRequest, refused.status, path)
            assertEquals("invalid_memory", refused.errorType(), path)
        }
        assertEquals("invalid_memory", create("ada", entry("x", "repo", "[]")).errorType())
        assertEquals("invalid_memory", create("ada", entry("", "personal", "[]")).errorType())
        val badExpiry = create("ada", """{"title": "t", "summary": "s", "content": "c", "expiresAt": "soon"}""")
        assertEquals("invalid_memory", badExpiry.errorType())
        val secret = create("ada", """{"title": "t", "summary": "s", "content": "AKIAABCDEFGHIJKLMNOP"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, secret.status)
        assertEquals("memory_secret", secret.errorType())
        assertTrue(read("ada", "$MEMORY/index").body<MemoryIndexPage>().entries.isEmpty())
        assertEquals(HttpStatusCode.NotFound, read("ada", "$MEMORY/not-an-id").status)
    }

    @Test
    fun theIndexIsPagedWithItsCursor() = memoryTest {
        val ids = (1..5).map { created("ada", entry("Note $it", "personal", "[]")).id }
        val whole = read("ada", "$MEMORY/index").body<MemoryIndexPage>().entries.map { it.id }
        assertEquals(ids.toSet(), whole.toSet())
        val first = read("ada", "$MEMORY/index?limit=2").body<MemoryIndexPage>()
        val second = read("ada", "$MEMORY/index?limit=2&after=${first.next}").body<MemoryIndexPage>()
        val last = read("ada", "$MEMORY/index?limit=2&after=${second.next}").body<MemoryIndexPage>()
        assertEquals(whole, (first.entries + second.entries + last.entries).map { it.id }, "newest first, no repeat")
        assertEquals(listOf(2, 2, 1), listOf(first, second, last).map { it.entries.size })
        assertNull(last.next)
        assertEquals(2, read("ada", "$MEMORY/search?q=note&limit=2").body<MemorySearchResult>().entries.size)
    }

    @Test
    fun anExpiryInTheFutureIsKeptAndTheServerServesTheMemory() = memoryTest { server ->
        val later = created("ada", """{"title": "Freeze", "summary": "s", "content": "c", "expiresAt": "$FAR"}""")
        assertEquals(FAR, later.expiresAt)
        assertTrue(ServerFeatures.MEMORY in MemoryModule.features(server.koin))
    }

    private companion object {
        const val MEMORY = "/api/v1/memory"
        const val FAR = "2099-01-01T00:00:00Z"

        fun entry(title: String, scope: String, tags: String) =
            """{"title": "$title", "summary": "Steps to follow", "scope": "$scope", "tags": $tags,""" +
                """ "content": "Merge core, repin, then merge the server.", "refs": ["ostrom-core#192"]}"""

        fun update(version: Int) =
            """{"expectedVersion": $version, "title": "Deploy order", "summary": "Core first", "content": "New",""" +
                """ "tags": ["new"]}"""
    }
}
