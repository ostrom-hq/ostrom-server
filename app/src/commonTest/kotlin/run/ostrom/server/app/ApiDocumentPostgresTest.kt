// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.kernel.http.docs.API_OPENAPI_PATH
import run.ostrom.server.kernel.http.docs.ApiDocs
import run.ostrom.server.testing.OpenApiStructure
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The OpenAPI document of the whole server (every module, as production mounts them), generated from its routing tree
 * (kernel `http/docs/ApiDocs.kt`): the API contract, which `@ostrom-hq/openapi` publishes (ostrom-core
 * docs/decisions/0001). As TeamCode's ApiDocsTest, it fails for any API route (`/api/v1` and the incoming webhooks)
 * without its catalogue entry, for an entry without its route, and for a document a generator would refuse
 * ([OpenApiStructure]: no dangling `$ref`, every path parameter declared). CI writes it where `OSTROM_OPENAPI_OUT`
 * says and keeps it as the `openapi-generated` artifact, which the `api-package` job packs. [ApiLimits] checks that it
 * shows every limit the server enforces.
 */
class ApiDocumentPostgresTest {
    @Test
    fun everyApiRouteIsDescribedAndTheDocumentIsSound() = serverTest(serverModules()) { server ->
        val response = server.client.get(API_OPENAPI_PATH)
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        System.getenv("OSTROM_OPENAPI_OUT")?.let { path -> File(path).apply { parentFile?.mkdirs() }.writeText(text) }
        val coverage = server.koin.get<ApiDocs>().coverage()
        assertEquals(emptyMap(), coverage.uncatalogued, "Each module that mounts API routes has its apiOperations")
        assertEquals(emptyList(), coverage.undescribed, "Each API route has its entry in its module's apiOperations")
        assertEquals(emptyList(), coverage.unrouted, "Each catalogue entry names a route its module mounts")
        assertEquals(emptyList(), coverage.duplicated, "Each route has one entry")
        val problems = OpenApiStructure.problems(Json.parseToJsonElement(text).jsonObject)
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
        val limits = ApiLimits.problems(Json.parseToJsonElement(text).jsonObject)
        assertEquals(emptyList(), limits, "The document hides limits:\n" + limits.joinToString("\n"))
    }
}
