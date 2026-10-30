// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts

import dev.ostrom.server.artifacts.fakes.testIdentities
import dev.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The artifacts MCP server, through its route, as an agent's MCP client calls it. */
class ArtifactsMcpTest {
    private suspend fun HttpClient.rpc(body: String, token: String = "alice"): HttpResponse =
        post("/api/v1/artifacts/mcp") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.call(tool: String, arguments: String, token: String = "alice"): JsonObject {
        val body = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
        return Json.parseToJsonElement(rpc(body, token).bodyAsText()).jsonObject
    }

    private fun JsonObject.text(): String =
        getValue("result").jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content

    private fun JsonObject.isError(): Boolean = getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean

    private fun JsonObject.errorCode(): Int = getValue("error").jsonObject.getValue("code").jsonPrimitive.int

    @Test
    fun theProtocolIsNegotiatedAndTheToolsListed() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        val client = server.client
        val init = Json.parseToJsonElement(
            client.rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")
                .bodyAsText(),
        ).jsonObject.getValue("result").jsonObject
        assertEquals("2025-03-26", init.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("ostrom-artifacts", init.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        val future = Json.parseToJsonElement(
            client.rpc("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")
                .bodyAsText(),
        ).jsonObject.getValue("result").jsonObject
        assertEquals("2025-06-18", future.getValue("protocolVersion").jsonPrimitive.content)
        val bare = client.rpc("""{"jsonrpc":"2.0","id":3,"method":"initialize"}""").bodyAsText()
        assertTrue("2025-06-18" in bare)

        val notification = client.rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(HttpStatusCode.Accepted, notification.status)
        assertTrue("\"result\":{}" in client.rpc("""{"jsonrpc":"2.0","id":4,"method":"ping"}""").bodyAsText())
        val tools = Json.parseToJsonElement(client.rpc("""{"jsonrpc":"2.0","id":5,"method":"tools/list"}""").bodyAsText())
            .jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
        assertEquals(listOf("publish", "list", "read", "share"), tools.map { it.jsonObject.getValue("name").jsonPrimitive.content })

        assertEquals(-32700, Json.parseToJsonElement(client.rpc("{nope").bodyAsText()).jsonObject.errorCode())
        assertEquals(-32600, Json.parseToJsonElement(client.rpc("[1]").bodyAsText()).jsonObject.errorCode())
        assertEquals(-32600, Json.parseToJsonElement(client.rpc("""{"id":9}""").bodyAsText()).jsonObject.errorCode())
        val unknown = client.rpc("""{"jsonrpc":"2.0","id":6,"method":"resources/list"}""").bodyAsText()
        assertEquals(-32601, Json.parseToJsonElement(unknown).jsonObject.errorCode())
        val noTool = client.rpc("""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{}}""").bodyAsText()
        assertEquals(-32602, Json.parseToJsonElement(noTool).jsonObject.errorCode())
        assertEquals(-32602, client.call("delete", "{}").errorCode())
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/artifacts/mcp").status)
    }

    @Test
    fun anAgentPublishesListsReadsAndShares() = serverTest(listOf(ArtifactsModule), testIdentities) { server ->
        val client = server.client
        val files = """[{"path":"index.html","content":"<h1>Plan</h1>"},{"path":"a.png","content":"AQID","encoding":"base64"}]"""
        val published = client.call("publish", """{"title":"Plan","files":$files}""")
        val text = published.text()
        assertTrue(text.startsWith("Published \"Plan\" (version 1, personal)"), text)
        val id = text.substringAfter("Artifact id: ").trim()

        val again = client.call("publish", """{"artifact":"$id","files":[{"path":"index.html","content":"<h1>v2</h1>"}]}""")
        assertTrue(again.text().startsWith("Published version 2 of $id"), again.text())

        assertTrue(id in client.call("list", "{}").text())
        assertEquals("No artifacts yet.", client.call("list", "{}", token = "carol").text())
        assertEquals("<h1>v2</h1>", client.call("read", """{"artifact":"$id"}""").text())
        assertEquals("A binary file of 3 bytes.", client.call("read", """{"artifact":"$id","version":1,"path":"a.png"}""").text())
        val zero = client.call("publish", """{"title":"Bin","files":[{"path":"d.dat","content":"AAE=","encoding":"base64"}]}""")
        val binary = zero.text().substringAfter("Artifact id: ").trim()
        assertEquals("A binary file of 2 bytes.", client.call("read", """{"artifact":"$binary","path":"d.dat"}""").text())
        val font = client.call("publish", """{"title":"Font","files":[{"path":"f.woff2","content":"abc"}]}""")
        val fontId = font.text().substringAfter("Artifact id: ").trim()
        assertEquals("A binary file of 3 bytes.", client.call("read", """{"artifact":"$fontId","path":"f.woff2"}""").text())

        val shared = client.call("share", """{"artifact":"$id","access":"link"}""")
        assertTrue("Public link: http://artifacts.test/l/" in shared.text(), shared.text())
        val member = client.call("share", """{"artifact":"$id","member":"00000000-0000-0000-0000-000000000b0b"}""")
        assertTrue(member.text().startsWith("\"Plan\" is link"))
        val removed = client.call("share", """{"artifact":"$id","access":"team","member":"00000000-0000-0000-0000-000000000b0b","remove":true}""")
        assertTrue(removed.text().startsWith("\"Plan\" is team"))
        assertTrue("Public link" !in removed.text())

        val refused = client.call("publish", """{"artifact":"$id","files":[{"path":"index.html","content":"x"}]}""", "bob")
        assertTrue(refused.isError())
        assertEquals("Only the artifact's owner changes it.", refused.text())
        val readOnly = client.call("publish", """{"title":"x","files":[{"path":"a.txt","content":"x"}]}""", "reader")
        assertTrue(readOnly.isError())
        assertEquals(-32602, client.call("publish", """{"title":"x"}""").errorCode())
        assertEquals(-32602, client.call("read", """{"artifact":"$id","version":"one"}""").errorCode())
        assertTrue(client.call("publish", """{"files":[{"path":"a.txt","content":"%","encoding":"base64"}]}""").isError())
        assertTrue(client.call("publish", """{"files":[{"path":"a.txt","content":"x"}]}""").isError())
        val team = client.call("publish", """{"title":"T","access":"team","files":[{"path":"a.txt","content":"x"}]}""")
        assertTrue(team.text().startsWith("Published \"T\" (version 1, team)"), team.text())
        assertTrue(client.call("read", """{"artifact":"not-an-id"}""").isError())
    }
}
