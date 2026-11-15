// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.http.docs.API_OPENAPI_PATH
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.ApiDocs
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fails unless [module]'s catalogue ([ServerModule.apiOperations]) is the one this server mounted and every catalogue
 * on it describes each route of its module, once, and nothing its module does not mount, as the app's whole-server
 * document test holds them (ostrom-core docs/decisions/0001). The gaps are the server's: a test that mounts other
 * catalogued modules holds them too, each gap named by its `METHOD /path`. Fetching the document runs every entry, so
 * a catalogue is measured by its module's tests.
 *
 * @param module The module under test, mounted on this server.
 */
suspend fun TestServer.assertApiCatalogue(module: ServerModule) {
    val operations = assertNotNull(module.apiOperations, "${module.name} has no catalogue (apiOperations)")
    val response = client.get(API_OPENAPI_PATH)
    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.OK, response.status, body)
    val paths = Json.parseToJsonElement(body).jsonObject.getValue("paths").jsonObject
    val served = paths.flatMap { (path, item) -> item.jsonObject.keys.map { method -> "${method.uppercase()} $path" } }
    val missing = ApiCatalog().apply(operations).operations.map { it.key }.filter { it !in served }
    assertEquals(emptyList(), missing, "Each entry of ${module.name} is in the served document")
    val coverage = koin.get<ApiDocs>().coverage()
    assertTrue(module.name !in coverage.uncatalogued, "${module.name} is mounted with its catalogue")
    assertEquals(emptyList(), coverage.undescribed, "Each route of a catalogued module has its entry")
    assertEquals(emptyList(), coverage.unrouted, "Each entry names a route its module mounts")
    assertEquals(emptyList(), coverage.duplicated, "Each route has one entry")
}
