// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.ApiDocs
import run.ostrom.server.kernel.http.docs.ok
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
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [ContractObserver] checks every answer of a server against its contract, the server's document ([Contract.of]). */
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
            get("/api/v1/things/export") { export(call) }
            delete("/api/v1/things/{id}") { call.respondText("""{"id": "a"}""", json) }
            get("/api/v1/bare") { call.respondText("""{"x": 1}""", json) }
            get("/health") { call.respondText("""{"x": 1}""", json) }
        }
    }

    // The export: a CSV, or a 404 in plain text or in JSON, which the contract both declares.
    private suspend fun export(call: RoutingCall) = when (call.request.queryParameters["as"]) {
        "missing" -> call.respondText("Not found", status = HttpStatusCode.NotFound)
        "missing-json" -> call.respondText("""{"error": {}}""", json, HttpStatusCode.NotFound)
        else -> call.respondBytesWriter(ContentType.Text.CSV) { writeStringUtf8("a") }
    }

    @Test
    fun eachDriftFails() = testApplication {
        val observer = ContractObserver(ContractFixtures.contract)
        things(observer)
        client.get("/api/v1/things")
        val things = listOf("a", "drift", "teapot", "crash", "crash-bare", "text", "broken", "empty", "accepted")
        things.forEach { client.get("/api/v1/things/$it") }
        listOf("", "?as=json", "?as=empty", "?as=error").forEach { client.post("/api/v1/things$it") }
        client.get("/api/v1/things/export")
        client.delete("/api/v1/things/a")
        client.get("/api/v1/bare")
        client.get("/health")

        val failure = assertFailsWith<AssertionError> { observer.finish() }.message.orEmpty()
        val expected = listOf(
            "getThing 200 $.id MISSING",
            "getThing 200 $.name UNDECLARED",
            "getThing 418 $ STATUS",
            "getThing 500 $.error MISSING",
            "getThing 200 $ BODY",
            "getThing 202 $ STATUS",
            "createThing 204 $ BODY",
        )
        assertEquals(expected, failure.lines().drop(1).map { it.substringBefore("  (") }, failure)
        assertTrue("no JSON body where the contract declares one" in failure, failure)
        assertFalse("a body that is not JSON" in failure, "One line per drift: the first answer's")
    }

    @Test
    fun answersThatFollowTheContractPass() = testApplication {
        val observer = ContractObserver(ContractFixtures.contract)
        things(observer)
        client.get("/api/v1/things")
        client.delete("/api/v1/things/a")
        // A 404 that declares plain text and JSON: either is one of its forms.
        listOf("missing", "missing-json").forEach { client.get("/api/v1/things/export?as=$it") }
        observer.finish()
    }

    @Test
    fun withoutAContractNothingIsWatched() = testApplication {
        val observer = ContractObserver(null)
        things(observer)
        client.get("/api/v1/things/drift")
        observer.finish()
    }

    @Test
    fun theContractIsTheServersGeneratedDocumentBuiltOncePerSetOfRoutes() = testApplication {
        val docs = ApiDocs()
        val observer = ContractObserver { Contract.of(docs) }
        application {
            install(observer.plugin)
            routing {
                docs.section(this, "probes", PROBES) {
                    get("/api/v1/probes/{id}") {
                        val answer = if (call.parameters["id"] == "bad") """{"other": 1}""" else """{"name": "a"}"""
                        call.respondText(answer, json)
                    }
                }
            }
        }
        client.get("/api/v1/probes/good")
        observer.finish()
        client.get("/api/v1/probes/bad")
        val failure = assertFailsWith<AssertionError> { observer.finish() }.message.orEmpty()
        assertEquals(
            listOf("getProbe 200 $.name MISSING", "getProbe 200 $.other UNDECLARED"),
            failure.lines().drop(1).map { it.substringBefore("  (") },
            failure,
        )
        assertSame(Contract.of(docs), Contract.of(docs), "Built once")
        assertEquals("getProbe", Contract.of(docs).operations.match("GET", "/api/v1/probes/x")?.id)
    }

    /** What a probe answers. */
    @Serializable
    data class Probe(val name: String)

    private companion object {
        val PROBES: ApiCatalog.() -> Unit = {
            get("/api/v1/probes/{id}") {
                operationId = "getProbe"
                responses { ok<Probe>("The probe.") }
            }
        }
    }
}
