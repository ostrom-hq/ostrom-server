// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.http.docs.API_OPENAPI_PATH
import run.ostrom.server.kernel.http.docs.ApiDocs
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fails unless [module]'s catalogue ([ServerModule.apiOperations]) describes each route it mounts on this server,
 * once, and nothing it does not mount, as the app's whole-server document test holds it (ostrom-core
 * docs/decisions/0001). Fetching the document runs every entry, so a catalogue is measured by its module's tests.
 *
 * @param module The module under test.
 */
suspend fun TestServer.assertApiCatalogue(module: ServerModule) {
    val response = client.get(API_OPENAPI_PATH)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    val coverage = koin.get<ApiDocs>().coverage()
    assertTrue(module.name !in coverage.uncatalogued, "${module.name} has no catalogue (apiOperations)")
    assertEquals(emptyList(), coverage.undescribed, "Each route of ${module.name} has its entry in its apiOperations")
    assertEquals(emptyList(), coverage.unrouted, "Each entry of ${module.name} names a route it mounts")
    assertEquals(emptyList(), coverage.duplicated, "Each route of ${module.name} has one entry")
}
