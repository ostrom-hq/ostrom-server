// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.auth.TOKEN_AUTH
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.openapi.ApiKeySecurityScheme
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.PathItem
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.SecuritySchemeIn
import io.ktor.openapi.Tag
import io.ktor.server.auth.AuthenticationRouteSelector
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.OpenApiRoutePathFormat
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.openapi.plus
import io.ktor.server.routing.path
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64

/** The OpenAPI document of the Ostrom API, generated from the server's routes when first requested. */
const val API_OPENAPI_PATH: String = "/api/openapi.json"

/** Swagger UI for [API_OPENAPI_PATH]. */
const val API_DOCS_PATH: String = "/api/docs"

/**
 * The scheme of a bearer token: the CLI's, the app's, a device's, an agent's, a runner's or a service key. Named as
 * the authentication provider, whose requirement Ktor adds to each route inside `authenticated { }`.
 */
internal const val BEARER_SCHEME = TOKEN_AUTH

/** The scheme of the console's session cookie (with its `X-CSRF-Token` on a write). */
internal const val SESSION_SCHEME = "session"

/** The console's session cookie over HTTPS (`ostrom_session` on a plain-HTTP development server). */
private const val SESSION_COOKIE = "__Host-ostrom_session"

/**
 * The version of `swagger-ui-dist` the page loads from unpkg, pinned with the Subresource Integrity hash of each file,
 * as TeamCode's: the browser refuses a modified file. A new version changes these three constants
 * (`curl -s <url> | openssl dgst -sha384 -binary | openssl base64 -A`).
 */
private const val SWAGGER_UI_VERSION = "5.17.14"
private const val SWAGGER_UI_CSS_SRI = "sha384-wxLW6kwyHktdDGr6Pv1zgm/VGJh99lfUbzSn6HNHBENZlCN7W602k9VkGdxuFvPn"
private const val SWAGGER_UI_JS_SRI = "sha384-wmyclcVGX/WhUkdkATwhaK1X1JtiNrr2EoYJ+diV3vj4v6OC5yCeSu+yW13SYJep"

private val documentJson = Json { encodeDefaults = false }

/**
 * The routes one part of the server mounted (the kernel or a module) and its catalogue.
 *
 * @property owner The kernel or the module's name.
 * @property catalog Its catalogue; null while the module has none yet, so its routes appear undescribed.
 * @property routes The routes it mounted.
 */
class ApiDocsSection(val owner: String, val catalog: ApiCatalog?, val routes: List<RoutingNode>)

/**
 * What the document leaves undescribed.
 *
 * @property undescribed `METHOD /path` of each API route whose module has a catalogue without its entry.
 * @property unrouted `METHOD /path` of each catalogue entry no route of its module matches, but the conditional ones.
 * @property uncatalogued The API routes of each module that has no catalogue yet, by module.
 * @property duplicated `METHOD /path` of each entry a catalogue declares more than once (the first one is used).
 */
data class ApiDocsCoverage(
    val undescribed: List<String>,
    val unrouted: List<String>,
    val uncatalogued: Map<String, List<String>>,
    val duplicated: List<String>,
)

/**
 * The OpenAPI document of the server, generated from its routing tree with `ktor-server-routing-openapi` as TeamCode
 * does (`presentation/.../docs/ApiDocs.kt`), never written by hand: each route gets its description from its module's
 * catalogue ([run.ostrom.server.kernel.module.ServerModule.apiOperations]) and its schemas from the Kotlin models.
 * The kernel's HTTP layer fills it as it mounts each module's routes ([section]); built once, at the first request.
 */
class ApiDocs {
    private val sections = mutableListOf<ApiDocsSection>()
    private var root: RoutingNode? = null
    private var document: String? = null

    /**
     * Mounts the routes of [owner] and records them.
     *
     * @param route The routing root.
     * @param owner The kernel or the module's name.
     * @param operations Its catalogue; null while it has none.
     * @param build Mounts its routes.
     */
    fun section(route: Route, owner: String, operations: (ApiCatalog.() -> Unit)?, build: Route.() -> Unit) {
        val node = route as RoutingNode
        root = node
        val before = node.getAllRoutes().toSet()
        route.build()
        val catalog = operations?.let { ApiCatalog().apply(it) }
        sections += ApiDocsSection(owner, catalog, node.getAllRoutes().filter { it !in before })
    }

    /**
     * The document as served, built once: the routing tree no longer changes once the server runs. Building it
     * annotates the routing tree, so one build at a time.
     *
     * @return The document, as JSON.
     */
    fun document(): String = synchronized(this) {
        document ?: build().also { document = it }
    }

    /**
     * What the document is built from: each part, whether it has a catalogue, and the routes it mounted. Two servers
     * with the same signature have the same document, so a test process builds it once per signature (the testing
     * module's `Contract.of`).
     *
     * @return One line per part.
     */
    fun signature(): String = sections.joinToString("\n") { section ->
        "${section.owner} ${section.catalog != null} ${section.routes.mapNotNull { it.key() }.joinToString(",")}"
    }

    /**
     * What the document leaves undescribed, for the test that holds every module's catalogue to its routes.
     *
     * @return The gaps.
     */
    fun coverage(): ApiDocsCoverage {
        val undescribed = mutableListOf<String>()
        val unrouted = mutableListOf<String>()
        val uncatalogued = linkedMapOf<String, List<String>>()
        val duplicated = mutableListOf<String>()
        for (section in sections) {
            val routes = section.routes.mapNotNull { it.key() }.filter { isApiRoute(it.substringAfter(' ')) }
            val catalog = section.catalog
            if (catalog == null) {
                if (routes.isNotEmpty()) uncatalogued[section.owner] = routes
                continue
            }
            duplicated += catalog.operations.groupBy { it.key }.filterValues { it.size > 1 }.keys
            val keys = catalog.operations.map { it.key }.toSet()
            undescribed += routes.filter { it !in keys }
            unrouted += catalog.operations.filter { !it.conditional && it.key !in routes }.map { it.key }.distinct()
        }
        return ApiDocsCoverage(undescribed, unrouted, uncatalogued, duplicated)
    }

    @OptIn(ExperimentalKtorApi::class)
    private fun build(): String {
        val routing = checkNotNull(root) { "No route was mounted." }
        val tags = linkedMapOf<String, String>()
        for (section in sections) {
            val catalog = section.catalog?.also { tags += it.tags }
            for (route in section.routes) annotate(route, catalog)
        }
        // The routes no section mounted (these documents' own) stay out of the document.
        val owned = sections.flatMap { it.routes }.toSet()
        routing.getAllRoutes().filter { it !in owned }.forEach { it.hide() }
        val base = OpenApiDoc(
            info = OpenApiInfo(
                title = "Ostrom API",
                // The API version, not the build: the server's version is behind authentication (capabilities).
                version = ServerFeatures.API_VERSION.toString(),
                description = API_DESCRIPTION,
                license = OpenApiInfo.License(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0"),
            ),
            tags = tags.map { (name, text) -> Tag(name = name, description = text) },
        )
        val schemes = mapOf(
            BEARER_SCHEME to ReferenceOr.Value(HttpSecurityScheme(scheme = "bearer", description = BEARER_DESCRIPTION)),
            SESSION_SCHEME to ReferenceOr.Value(
                ApiKeySecurityScheme(SESSION_COOKIE, SecuritySchemeIn.COOKIE, SESSION_DESCRIPTION),
            ),
        )
        val generated = base + schemes + routing.getAllRoutes()
        // A public operation says so (`security: []`) rather than leaving its security undefined.
        val document = generated.copy(
            // The routing tree only writes values, never references.
            paths = generated.paths.mapValues { (_, item) ->
                ReferenceOr.Value((item as ReferenceOr.Value<PathItem>).value.withExplicitSecurity())
            },
        )
        val encoded = documentJson.encodeToJsonElement(OpenApiDoc.serializer(), document)
        return encoded.jsonObject.withSingleSchemaPerType().withAnyJsonElement().withoutUndescribedQueryParameters()
            .toString()
    }

    private fun annotate(route: RoutingNode, catalog: ApiCatalog?) {
        val key = route.key() ?: return
        val operation = catalog?.let { entries -> entries.operations.firstOrNull { it.key == key } }
        when {
            operation != null -> {
                val access = ApiAccess(public = !route.isAuthenticated(), signed = key.contains(" /webhooks/"))
                route.describe(operation.describeWith(access))
                route.describe(commonResponses(operation, access))
            }
            // Undescribed but kept in the document: the coverage lists it.
            isApiRoute(key.substringAfter(' ')) -> Unit
            else -> route.hide()
        }
    }
}

/**
 * Serves the document and Swagger UI, both public (they hold no organization's data).
 *
 * @param docs The document.
 */
internal fun Route.apiDocsRoutes(docs: ApiDocs) {
    get(API_OPENAPI_PATH) {
        call.respondText(docs.document(), ContentType.Application.Json)
    }
    get(API_DOCS_PATH) {
        call.response.header("Content-Security-Policy", SWAGGER_UI_CSP)
        call.respondText(swaggerUiPage(API_OPENAPI_PATH), ContentType.Text.Html)
    }
}

/**
 * The documented surface: the API under `/api/v1` and the incoming webhooks.
 *
 * @param path The route's path.
 *
 * @return True when the route belongs in the document.
 */
internal fun isApiRoute(path: String): Boolean = path.startsWith("/api/v1/") || path.startsWith("/webhooks/")

private fun PathItem.withExplicitSecurity(): PathItem {
    fun Operation?.explicit() = this?.let { it.copy(security = it.security ?: emptyList()) }
    return copy(
        get = get.explicit(), put = put.explicit(), post = post.explicit(), delete = delete.explicit(),
        options = options.explicit(), head = head.explicit(), patch = patch.explicit(), trace = trace.explicit(),
    )
}

/** `METHOD /path` of a route, or null for a route that answers any method. */
private fun RoutingNode.key(): String? = method()?.let { "${it.value} ${path(OpenApiRoutePathFormat)}" }

private fun RoutingNode.method(): HttpMethod? =
    generateSequence(this) { it.parent }.firstNotNullOfOrNull { (it.selector as? HttpMethodRouteSelector)?.method }

private fun RoutingNode.isAuthenticated(): Boolean =
    generateSequence(this) { it.parent }.any { it.selector is AuthenticationRouteSelector }

/** The page's only inline script, allowed by its hash in [SWAGGER_UI_CSP]. */
private fun swaggerUiBootstrap(specPath: String) =
    "window.onload = () => { window.ui = SwaggerUIBundle({ url: \"$specPath\", dom_id: \"#swagger-ui\" }); };"

/**
 * The page runs third-party code on the server's origin, so it may only load the pinned files from unpkg, run its own
 * bootstrap, call this origin, and never be framed.
 */
private val SWAGGER_UI_CSP: String by lazy {
    val bootstrap = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(swaggerUiBootstrap(API_OPENAPI_PATH).encodeToByteArray()),
    )
    "default-src 'none'; script-src https://unpkg.com 'sha256-$bootstrap'; style-src https://unpkg.com " +
        "'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; " +
        "form-action 'none'"
}

/**
 * The Swagger UI page that reads [specPath].
 *
 * @param specPath The document's path.
 *
 * @return The page.
 */
internal fun swaggerUiPage(specPath: String): String =
    """
    <!doctype html>
    <html lang="en">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Ostrom API</title>
      <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@$SWAGGER_UI_VERSION/swagger-ui.css"
            integrity="$SWAGGER_UI_CSS_SRI" crossorigin="anonymous">
    </head>
    <body>
      <div id="swagger-ui"></div>
      <script src="https://unpkg.com/swagger-ui-dist@$SWAGGER_UI_VERSION/swagger-ui-bundle.js"
              integrity="$SWAGGER_UI_JS_SRI" crossorigin="anonymous"></script>
      <script>${swaggerUiBootstrap(specPath)}</script>
    </body>
    </html>
    """.trimIndent()

private const val API_DESCRIPTION = "The HTTP API of an Ostrom server. The `ostrom` CLI, the console and the app use " +
    "the same routes.\n\n" +
    "**Authentication.** Send `Authorization: Bearer <token>` (the CLI, the app, a device, an agent, a runner or a " +
    "service key), or the console's session cookie. With the cookie, every write (POST, PUT, PATCH, DELETE) also " +
    "sends `X-CSRF-Token`, equal to the readable `__Host-ostrom_csrf` cookie, from an allowed `Origin`.\n\n" +
    "**Organizations.** A route without `orgs/{orgId}` in its path acts in the caller's organization. A member of " +
    "several organizations names it with the `Ostrom-Org` header; without it they get 409 `org_required`. A resource " +
    "of another organization answers 404 exactly like one that does not exist, never 403.\n\n" +
    "**Errors.** Every error answers `{\"error\": {\"type\": \"<stable key>\", \"message\": \"<text>\"}}`. Clients " +
    "branch on `type`, never on `message`. A throttled call answers 429 `rate_limited`, with `Retry-After` when the " +
    "limit is per caller.\n\n" +
    "**Features.** The operations available depend on the features this server serves and has configured " +
    "(`GET /api/v1/capabilities`). Enumerations are open: a newer server may answer a value a client does not know."

private const val BEARER_DESCRIPTION = "A token: the CLI's or the app's after signing in, a device's, an agent's, a " +
    "runner's, or a service key. What it may call depends on its kind and its scopes."

private const val SESSION_DESCRIPTION = "The console's session cookie (`ostrom_session` on a plain-HTTP development " +
    "server). A write also sends `X-CSRF-Token`."
