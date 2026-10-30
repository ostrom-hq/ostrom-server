// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.MapConfigSource
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractAnswersTest {
    private fun freshDirectory(): Path = Path(SystemTemporaryDirectory, "contract-answers-${Random.nextLong()}")

    private fun lines(directory: Path): List<JsonObject> =
        SystemFileSystem.list(directory).flatMap { file ->
            SystemFileSystem.source(file).buffered().use { it.readString() }.lines().filter(String::isNotBlank)
        }.map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).toString()

    @Test
    fun writesNothingWhenNoDirectoryIsSet() = testApplication {
        val directory = freshDirectory()
        application {
            recordContractAnswers(MapConfigSource(emptyMap()))
            routing { get("/x") { call.respondText("{}", ContentType.Application.Json) } }
        }
        client.get("/x")
        assertFalse(SystemFileSystem.exists(directory))
    }

    @Test
    fun recordsTheMethodPathStatusContentTypeAndBodyOfEveryAnswer() = testApplication {
        val directory = freshDirectory()
        application {
            recordContractAnswers(MapConfigSource(mapOf(CONTRACT_ANSWERS_DIR to directory.toString())))
            routing {
                get("/created") {
                    call.respondText("""{"id":"a"}""", ContentType.Application.Json, HttpStatusCode.Created)
                }
                get("/accepted") {
                    call.response.status(HttpStatusCode.Accepted)
                    call.respondText("""{"queued":true}""", ContentType.Application.Json)
                }
                get("/plain") { call.respondText("ok") }
                get("/stream") {
                    call.respondBytesWriter(ContentType.Text.EventStream) { writeStringUtf8("data: 1\n\n") }
                }
                get("/none") { call.respond(HttpStatusCode.NoContent) }
            }
        }
        for (path in listOf("/created", "/accepted", "/plain", "/stream", "/none")) client.get(path)

        val answers = lines(directory).associateBy { it.text("path") }
        assertEquals(5, answers.size)
        val created = answers.getValue("\"/created\"")
        assertEquals("\"GET\"", created.text("method"))
        assertEquals("201", created.text("status"))
        assertTrue(created.text("contentType").startsWith("\"application/json"))
        assertEquals("\"{\\\"id\\\":\\\"a\\\"}\"", created.text("body"))
        assertEquals("202", answers.getValue("\"/accepted\"").text("status"))
        assertEquals("200", answers.getValue("\"/plain\"").text("status"))
        val stream = answers.getValue("\"/stream\"")
        assertEquals("null", stream.text("body"), "A streamed body is not recorded")
        assertEquals("\"text/event-stream\"", stream.text("contentType"))
        val none = answers.getValue("\"/none\"")
        assertEquals("204", none.text("status"))
        assertEquals("null", none.text("contentType"))
        assertEquals("null", none.text("body"))
    }
}
