// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.MapConfigSource
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ContractObserver] checks every answer of a server against the contract, and [Contract.from] finds the contract. */
class ContractObserverTest {
    private val json = ContentType.Application.Json

    // A server whose answers follow the small contract of ContractFixtures, or not, one way per route.
    private fun ApplicationTestBuilder.things(observer: ContractObserver) = application {
        install(observer.plugin)
        routing {
            get("/api/v1/things") {
                call.response.status(HttpStatusCode.OK)
                call.respondText("""{"items": [{"id": "a"}]}""", json)
            }
            get("/api/v1/things/{id}") {
                when (call.parameters["id"]) {
                    "a" -> call.respondText("""{"id": "a"}""", json)
                    "drift" -> call.respondText("""{"name": 1}""", json)
                    "teapot" -> call.respondText("{}", json, HttpStatusCode(418, "Teapot"))
                    "crash" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.InternalServerError)
                    "crash-bare" -> call.respondText("{}", json, HttpStatusCode.InternalServerError)
                    "accepted" -> call.respondText("{}", json, HttpStatusCode.Accepted)
                    "text" -> call.respondText("a")
                    "broken" -> call.respondText("{not json", json)
                    else -> call.respondText("", json)
                }
            }
            post("/api/v1/things") {
                when (call.request.queryParameters["as"]) {
                    "json" -> call.respondText("{}", json, HttpStatusCode.NoContent)
                    "empty" -> call.respond(TextContent("", json, HttpStatusCode.NoContent))
                    "error" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.BadRequest)
                    else -> call.respond(HttpStatusCode.NoContent)
                }
            }
            get("/api/v1/things/export") {
                when (call.request.queryParameters["as"]) {
                    "missing" -> call.respondText("Not found", status = HttpStatusCode.NotFound)
                    "missing-json" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.NotFound)
                    else -> call.respondBytesWriter(ContentType.Text.CSV) { writeStringUtf8("a") }
                }
            }
            delete("/api/v1/things/{id}") { call.respondText("""{"x": 1}""", json) }
            get("/health") { call.respondText("""{"x": 1}""", json) }
        }
    }

    private fun report(directory: Path): String = SystemFileSystem.list(directory).single().let { file ->
        SystemFileSystem.source(file).buffered().use { it.readString() }
    }

    @Test
    fun eachDriftFailsUnlessKnownAndWhatWasSeenIsWritten() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(ContractFixtures.contract, setOf("getThing 418 $ STATUS"), reports.toString())
        things(observer)
        client.get("/api/v1/things")
        val things = listOf("a", "drift", "teapot", "crash", "crash-bare", "text", "broken", "empty", "accepted")
        things.forEach { client.get("/api/v1/things/$it") }
        listOf("", "?as=json", "?as=empty", "?as=error").forEach { client.post("/api/v1/things$it") }
        client.get("/api/v1/things/export")
        client.delete("/api/v1/things/a")
        client.get("/health")

        val failure = assertFailsWith<AssertionError> { observer.finish() }.message.orEmpty()
        val expected = listOf(
            "getThing 200 $.id MISSING",
            "getThing 200 $.name UNDECLARED",
            "getThing 500 $.error MISSING",
            "getThing 200 $ BODY",
            "getThing 202 $ STATUS",
            "createThing 204 $ BODY",
        )
        assertEquals(expected, failure.lines().drop(1).map { it.substringBefore("  (") }, failure)
        val written = report(reports)
        assertFalse("a body that is not JSON" in failure, "One line per drift: the first answer's")
        assertTrue("a body that is not JSON" in written, "Every answer's drift is written")
        val lines = written.lines()
        assertTrue("seen listThings" in lines && "seen getThing" in lines, written)
        assertFalse(lines.any { it.startsWith("seen createThing") }, "A 204 is no JSON success")
        assertTrue(lines.any { it.startsWith("drift getThing 418 $ STATUS | ") }, "A known drift is written too")
        assertTrue(lines.any { "no JSON body where the contract declares one" in it })
        assertTrue(lines.any { "JSON where the contract declares none" in it })
    }

    @Test
    fun answersThatFollowTheContractPass() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(ContractFixtures.contract, emptySet(), reports.toString())
        things(observer)
        client.get("/api/v1/things")
        // A 404 that declares plain text and JSON: either is one of its forms.
        listOf("missing", "missing-json").forEach { client.get("/api/v1/things/export?as=$it") }
        observer.finish()
        assertEquals("seen listThings\n", report(reports))
    }

    @Test
    fun withoutAContractNothingIsWatchedNorWritten() = testApplication {
        val reports = ContractFixtures.directory()
        val observer = ContractObserver(null, emptySet(), reports.toString())
        things(observer)
        client.get("/api/v1/things/drift")
        observer.finish()
        assertTrue(SystemFileSystem.list(reports).isEmpty())
        ContractObserver(null).finish()
    }

    @Test
    fun theContractComesFromTheEnvironmentReadOncePerFile() {
        assertNull(Contract.from(MapConfigSource(emptyMap())), "Skipped without a contract")
        val error = assertFailsWith<IllegalStateException> {
            Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_REQUIRED" to "true")))
        }
        assertEquals("OSTROM_CONTRACT_REQUIRED is true but OSTROM_CONTRACT_OPENAPI is not set.", error.message)
        val file = ContractFixtures.file()
        val first = Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_OPENAPI" to file)))
        assertEquals(ContractFixtures.contract.operations.all, first?.operations?.all)
        assertSame(first, Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_OPENAPI" to file))), "Read once")
        val other = Contract.from(MapConfigSource(mapOf("OSTROM_CONTRACT_OPENAPI" to ContractFixtures.file())))
        assertTrue(other !== first, "Another file is read")
    }
}
