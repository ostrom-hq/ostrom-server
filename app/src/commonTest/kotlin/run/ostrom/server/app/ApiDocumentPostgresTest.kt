// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.kernel.http.docs.API_OPENAPI_PATH
import run.ostrom.server.kernel.http.docs.ApiDocs
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The OpenAPI document of the whole server (every module, as production mounts them), generated from its routing tree
 * (kernel `http/docs/ApiDocs.kt`), the contract `@ostrom-hq/openapi` publishes. CI writes it where
 * `OSTROM_OPENAPI_OUT` says and keeps it as the `openapi-generated` artifact.
 */
class ApiDocumentPostgresTest {
    @Test
    fun everyCataloguedModuleDescribesEachOfItsRoutesAndNothingElse() = serverTest(serverModules()) { server ->
        val response = server.client.get(API_OPENAPI_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        System.getenv("OSTROM_OPENAPI_OUT")?.let { path ->
            File(path).apply { parentFile?.mkdirs() }.writeText(response.bodyAsText())
        }
        val coverage = server.koin.get<ApiDocs>().coverage()
        assertEquals(
            emptyList(),
            coverage.undescribed,
            "Each route of a module with a catalogue has its entry in the module's apiOperations",
        )
        assertEquals(emptyList(), coverage.unrouted, "Each catalogue entry names a route its module mounts")
        // Until every module has its catalogue (ostrom-core docs/decisions/0001, Delivery), the rest is listed.
        val pending = coverage.uncatalogued.entries.joinToString("\n") { (module, routes) -> "$module: ${routes.size}" }
        println("Routes without a catalogue yet, by module:\n$pending")
    }
}
