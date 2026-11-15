// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.core.Koin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiCataloguesTest {
    @Test
    fun aCatalogueDescribingEachRouteOncePasses() = probe(listOf(PROBE)).let { probe ->
        serverTest(listOf(probe)) { server -> server.assertApiCatalogue(probe) }
    }

    @Test
    fun anUndescribedRouteFails() = probe(listOf(PROBE), extra = "/api/v1/other").let { probe ->
        serverTest(listOf(probe)) { server -> assertFailsWith<AssertionError> { server.assertApiCatalogue(probe) } }
    }

    @Test
    fun anEntryWithoutItsRouteFails() = probe(listOf(PROBE, "/api/v1/gone")).let { probe ->
        serverTest(listOf(probe)) { server -> assertFailsWith<AssertionError> { server.assertApiCatalogue(probe) } }
    }

    @Test
    fun aRouteDescribedTwiceFails() = probe(listOf(PROBE, PROBE)).let { probe ->
        serverTest(listOf(probe)) { server -> assertFailsWith<AssertionError> { server.assertApiCatalogue(probe) } }
    }

    @Test
    fun aModuleWithoutCatalogueFails() = probe(null).let { probe ->
        serverTest(listOf(probe)) { server -> assertFailsWith<AssertionError> { server.assertApiCatalogue(probe) } }
    }

    @Test
    fun aCatalogueTheServerDidNotMountFails() = probe(null).let { mounted ->
        serverTest(listOf(mounted)) { server ->
            assertFailsWith<AssertionError> { server.assertApiCatalogue(probe(listOf(PROBE))) }
        }
    }

    /** A module mounting `GET /api/v1/probe` and [extra], documenting [documented] (no catalogue when null). */
    private fun probe(documented: List<String>?, extra: String? = null) = object : ServerModule {
        override val name: String = "probe"
        override val koinModule = module { }
        override val apiOperations: (ApiCatalog.() -> Unit)? = documented?.let { paths ->
            {
                paths.forEach { path ->
                    get(path) {
                        summary = "A probe"
                        responses { empty("It answers.", HttpStatusCode.OK) }
                    }
                }
            }
        }

        override fun routes(route: Route, koin: Koin) {
            route.get(PROBE) { call.respondText("ok") }
            extra?.let { path -> route.get(path) { call.respondText("ok") } }
        }
    }

    private companion object {
        const val PROBE = "/api/v1/probe"
    }
}
