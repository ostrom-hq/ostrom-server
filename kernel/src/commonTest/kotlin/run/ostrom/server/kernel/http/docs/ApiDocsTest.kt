// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.openapi.jsonSchema
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiDocsTest {
    /** A model the catalogue types its bodies and answers with. */
    @Serializable
    data class Item(val name: String, val note: String? = null)

    /** Nobody signs in: the document is built from the routing tree alone. */
    private object NoAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null

        override suspend fun authenticateSession(
            sessionToken: String,
            csrfToken: String?,
            requireCsrf: Boolean,
            origin: String?,
        ): AuthenticatedPrincipal? = null
    }

    /** A module with a catalogue: one route without its entry, one entry without its route. */
    private object ItemsModule : ServerModule {
        override val name: String = "items"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.authenticated {
                get("/api/v1/items/{id}") { call.respond(Item("a")) }
                post("/api/v1/items") { call.respond(HttpStatusCode.Created, Item("a")) }
                put("/api/v1/items/{id}") { call.respond(HttpStatusCode.NoContent) }
                patch("/api/v1/items/{id}") { call.respond(HttpStatusCode.OK) }
                delete("/api/v1/items/{id}") { call.respond(HttpStatusCode.NoContent) }
                get("/api/v1/items/extra") { call.respond(HttpStatusCode.OK) }
                get("/api/v1/items/plain") { call.respond(HttpStatusCode.OK) }
                route("/api/v1/items/any") { handle { call.respond(HttpStatusCode.OK) } }
            }
            route.post("/webhooks/items") { call.respond(HttpStatusCode.Accepted) }
            route.get("/items/page") { call.respond(HttpStatusCode.OK) }
        }

        override val apiOperations: ApiCatalog.() -> Unit = {
            tag(ITEMS, "The items.")
            get("/api/v1/items/{id}") {
                operationId = "getItem"
                tag(ITEMS)
                summary = "Read an item"
                description = "Reads one item."
                parameters {
                    pathId("id", "The item.")
                    queryText("view", "How much to show.")
                    queryNumber("limit", "At most this many notes.")
                    queryFlag("all", "Every note.")
                    organizationHeader()
                    headerText("Idempotency-Key", "One key per attempt.", required = true)
                }
                responses {
                    ok<Item>("The item.")
                    notFound("No such item (`not_found`).")
                    rateLimited()
                }
            }
            post("/api/v1/items") {
                operationId = "createItem"
                body<Item>("The item.")
                responses {
                    ok<Item>("Created.", HttpStatusCode.Created)
                    badRequest("A blank name (`invalid_item`).")
                    error(HttpStatusCode.Forbidden, "Not an administrator (`forbidden`).")
                    error(HttpStatusCode.InternalServerError, "The store failed.")
                }
            }
            put("/api/v1/items/{id}") {
                operationId = "replaceItem"
                description = "  "
                body<Item>("The item.", required = false)
                responses {
                    empty("Replaced.")
                    html("A page saying so.")
                    conflict("A newer version exists (`conflict`).")
                    error(HttpStatusCode.Unauthorized, "")
                }
            }
            patch("/api/v1/items/{id}") {
                operationId = "updateItem"
                responses { anyJson(HttpStatusCode.Forbidden, "Refused, in a provider's shape.") }
            }
            delete("/api/v1/items/{id}") {
                operationId = "deleteItem"
                responses { empty("Deleted.") }
            }
            post("/webhooks/items") {
                operationId = "receiveItem"
                body<Item>("The item.")
                responses { empty("Received.", HttpStatusCode.Accepted) }
            }
            get("/api/v1/items/ghost") { operationId = "ghost" }
            get("/api/v1/items/plain") { operationId = "plainItems" }
            conditionally { get("/api/v1/items/sometimes") { operationId = "sometimes" } }
            delete("/api/v1/items/{id}") { operationId = "deleteItemAgain" }
        }
    }

    /** A module whose routes are not described yet. */
    private object PendingModule : ServerModule {
        override val name: String = "pending"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.get("/api/v1/pending") { call.respond(HttpStatusCode.OK) }.describe {
                parameters { query("q") { schema = jsonSchema<String>() } }
            }
        }
    }

    /** A module without a catalogue that mounts nothing documented. */
    private object QuietModule : ServerModule {
        override val name: String = "quiet"
        override val koinModule: Module = module {}

        override fun routes(route: Route, koin: Koin) {
            route.get("/quiet") { call.respond(HttpStatusCode.OK) }
        }
    }

    /** A module with an empty catalogue and no route. */
    private object EmptyModule : ServerModule {
        override val name: String = "empty"
        override val koinModule: Module = module {}
        override val apiOperations: ApiCatalog.() -> Unit = {}
    }

    private val modules = listOf(ItemsModule, PendingModule, QuietModule, EmptyModule)

    private fun docsServer(docs: ApiDocs?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val koin = koinApplication {
            modules(
                module {
                    single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                    single<TokenAuthenticator> { NoAuthenticator }
                    docs?.let { value -> single { value } }
                },
            )
        }.koin
        application { configureHttp(koin, modules) }
        block()
    }

    private suspend fun ApplicationTestBuilder.document(): JsonObject {
        val response = client.get(API_OPENAPI_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.operation(path: String, method: String): JsonObject =
        this["paths"]!!.jsonObject[path]!!.jsonObject[method]!!.jsonObject

    private fun JsonObject.response(status: String): String =
        this["responses"]!!.jsonObject[status]!!.jsonObject["description"]!!.jsonPrimitive.content

    // A public operation needs nothing and answers no 401.
    private fun assertPublicOperations(document: JsonObject) {
        val webhook = document.operation("/webhooks/items", "post")
        assertEquals(JsonArray(emptyList()), webhook["security"], "A public operation says it needs nothing")
        assertFalse("401" in webhook["responses"]!!.jsonObject)
        assertTrue(webhook["description"]!!.jsonPrimitive.content.endsWith(ApiAccess(true, signed = true).text))
        val health = document.operation("/api/v1/health", "get")["description"]!!.jsonPrimitive.content
        assertTrue(health.endsWith("No authentication."), health)
    }

    // The patch keeps its own 403 body beside the shared one; of two delete entries, the first is used.
    private fun assertUpdateAndRemove(document: JsonObject) {
        val update = document.operation("/api/v1/items/{id}", "patch")
        assertEquals("**Authentication:** ${ApiAccess(false).text}", update["description"]!!.jsonPrimitive.content)
        val refused = update["responses"]!!.jsonObject["403"]!!.jsonObject
        assertTrue(refused["description"]!!.jsonPrimitive.content.startsWith("Refused, in a provider's shape. Also: "))
        val schema = refused["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content, "Its own body, not the error envelope")
        assertTrue("additionalProperties" in schema, schema.toString())
        val remove = document.operation("/api/v1/items/{id}", "delete")
        assertEquals("deleteItem", remove["operationId"]!!.jsonPrimitive.content, "The first of two entries is used")
        assertEquals("Deleted.", remove.response("204"))
    }

    @Test
    fun theDocumentDescribesEachCataloguedRouteWithItsAuthenticationAndSharedErrors() = docsServer(null) {
        val document = document()
        assertEquals("Ostrom API", document["info"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("1", document["info"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val tags = document["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf(META_TAG, ITEMS), tags)
        val schemes = document["components"]!!.jsonObject["securitySchemes"]!!.jsonObject
        assertEquals("cookie", schemes[SESSION_SCHEME]!!.jsonObject["in"]!!.jsonPrimitive.content)

        val read = document.operation("/api/v1/items/{id}", "get")
        assertEquals("getItem", read["operationId"]!!.jsonPrimitive.content)
        assertEquals(
            "Reads one item.\n\n**Authentication:** ${ApiAccess(false).text}",
            read["description"]!!.jsonPrimitive.content,
        )
        assertEquals(
            Json.parseToJsonElement("""[{"$BEARER_SCHEME":[]},{"$SESSION_SCHEME":[]}]"""),
            read["security"],
        )
        val parameters = read["parameters"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("id", "view", "limit", "all", "Ostrom-Org", "Idempotency-Key"), parameters)
        val headers = read["parameters"]!!.jsonArray.map { it.jsonObject }
            .filter { it["in"]!!.jsonPrimitive.content == "header" }
            .associate { it["name"]!!.jsonPrimitive.content to it["required"]?.jsonPrimitive?.content }
        assertEquals(mapOf("Ostrom-Org" to null, "Idempotency-Key" to "true"), headers, "A required header says so")
        assertEquals(setOf("200", "404", "429", "401", "403", "500", "default"), read["responses"]!!.jsonObject.keys)
        assertEquals("No such item (`not_found`).", read.response("404"))

        val create = document.operation("/api/v1/items", "post")
        assertEquals(
            "A blank name (`invalid_item`). Also: the body is not the expected JSON object (`invalid_request`).",
            create.response("400"),
        )
        assertTrue(create.response("403").startsWith("Not an administrator (`forbidden`). Also: "))
        assertEquals("The store failed.", create.response("500"), "A 500 the operation describes keeps its text")

        val replace = document.operation("/api/v1/items/{id}", "put")
        assertEquals("**Authentication:** ${ApiAccess(false).text}", replace["description"]!!.jsonPrimitive.content)
        assertEquals("No credential, or an unknown, expired or revoked one (`unauthorized`).", replace.response("401"))
        val page = replace["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject
        assertEquals(setOf("text/html"), page.keys, "A page, not JSON")
        assertUpdateAndRemove(document)

        assertPublicOperations(document)

        val paths = document["paths"]!!.jsonObject
        assertNull(document.operation("/api/v1/items/extra", "get")["operationId"], "Kept, undescribed")
        val plain = document.operation("/api/v1/items/plain", "get")["responses"]!!.jsonObject
        assertTrue("401" in plain, "An entry without responses still gets the shared ones: $plain")
        val pending = document.operation("/api/v1/pending", "get")["parameters"]!!.jsonArray
        assertEquals("q", pending.single().jsonObject["name"]!!.jsonPrimitive.content, "Its query parameter is kept")
        assertTrue("/api/v1/pending" in paths, "A module without a catalogue still appears")
        assertEquals("health", document.operation("/api/v1/health", "get")["operationId"]!!.jsonPrimitive.content)
        assertTrue("/api/v1/capabilities" in paths)
        for (hidden in listOf("/health", "/ready", "/items/page", "/quiet", API_OPENAPI_PATH, API_DOCS_PATH)) {
            assertFalse(hidden in paths, "$hidden is not part of the API")
        }
    }

    @Test
    fun theDocumentIsBuiltOnceAndTheCoverageNamesWhatIsLeftUndescribed() {
        val docs = ApiDocs()
        docsServer(docs) {
            val first = client.get(API_OPENAPI_PATH).bodyAsText()
            assertEquals(first, client.get(API_OPENAPI_PATH).bodyAsText())
            assertEquals(first, docs.document(), "The HTTP layer used the Koin's document")
        }
        assertEquals(
            ApiDocsCoverage(
                undescribed = listOf("GET /api/v1/items/extra"),
                unrouted = listOf("GET /api/v1/items/ghost"),
                uncatalogued = mapOf("pending" to listOf("GET /api/v1/pending")),
                duplicated = listOf("DELETE /api/v1/items/{id}"),
            ),
            docs.coverage(),
        )
        val signature = docs.signature().lines()
        assertTrue("pending catalogue=none entries= routes=GET /api/v1/pending" in signature, "Without a catalogue")
        val empty = signature.single { it.startsWith("empty ") }
        assertTrue(empty.endsWith(" entries= routes="), "A catalogue without entries nor routes: $empty")
        assertFalse("catalogue=none" in empty, empty)
        val items = signature.single { it.startsWith("items ") }
        assertTrue("GET /api/v1/items/ghost" in items.substringAfter("entries=").substringBefore(" routes="), items)
    }

    @Test
    fun twoCataloguesOfTheSameRoutesHaveTwoSignatures() {
        fun signatureOf(operations: ApiCatalog.() -> Unit) = ApiDocs().apply {
            testApplication {
                application { routing { section(this, "probes", operations) { get("/api/v1/probes") {} } } }
                startApplication()
            }
        }.signature()
        val one: ApiCatalog.() -> Unit = { get("/api/v1/probes") { operationId = "listProbes" } }
        val other: ApiCatalog.() -> Unit = { get("/api/v1/probes") { operationId = "readProbes" } }
        assertEquals(signatureOf(one), signatureOf(one), "The same catalogue, the same signature")
        assertNotEquals(signatureOf(one), signatureOf(other), "Same keys and routes, another catalogue")
    }

    @Test
    fun aDocumentWithoutRoutesCannotBeBuilt() {
        assertFailsWith<IllegalStateException> { ApiDocs().document() }
    }

    @Test
    fun swaggerUiReadsTheDocumentUnderAStrictContentSecurityPolicy() = docsServer(null) {
        val response = client.get(API_DOCS_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        val policy = response.headers["Content-Security-Policy"].orEmpty()
        assertTrue("frame-ancestors 'none'" in policy && "script-src https://unpkg.com 'sha256-" in policy, policy)
        assertTrue("url: \"$API_OPENAPI_PATH\"" in response.bodyAsText())
    }

    @Test
    fun theApiIsTheVersionedRoutesAndTheIncomingWebhooks() {
        assertTrue(isApiRoute("/api/v1/x"))
        assertTrue(isApiRoute("/webhooks/stripe"))
        assertFalse(isApiRoute("/api/openapi.json"))
        assertFalse(isApiRoute("/health"))
    }

    private companion object {
        const val ITEMS = "items"
    }
}
