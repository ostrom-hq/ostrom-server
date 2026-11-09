// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.gates.presentation.requireAnyScope
import run.ostrom.server.integrations.github.domain.GitHubAppManifests
import run.ostrom.server.integrations.github.domain.ManifestForm
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.TOKEN_AUTH
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.resources.Resource
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/github/app/manifest/start`: creates the instance's GitHub App by the manifest flow.
 *
 * @property owner The GitHub organization that will own the App; absent for the caller's own account.
 */
@Serializable
@Resource("/api/v1/github/app/manifest/start")
class GitHubManifestStartPath(val owner: String? = null)

/**
 * `GET /api/v1/github/app/manifest/callback`: GitHub's return once the App is created: the state, bound to the session
 * that started the flow, is the proof (AUDIT-SECU-2 B2).
 *
 * @property code GitHub's code, exchanged once for the App.
 * @property state The state of the start.
 */
@Serializable
@Resource("/api/v1/github/app/manifest/callback")
class GitHubManifestCallbackPath(val code: String? = null, val state: String? = null)

/**
 * What the manifest routes need.
 *
 * @property manifests The manifest flow.
 * @property webUrl GitHub's web address, the only place the page may post to.
 * @property webOrigin The console, where the callback sends the browser back.
 */
data class GitHubManifestRoutesDependencies(
    val manifests: GitHubAppManifests,
    val webUrl: String,
    val webOrigin: String,
)

/**
 * The manifest routes. The start is a browser session's (an administrator of the instance, checked by the flow) and
 * answers a page that posts the manifest to GitHub, the only way GitHub takes one; the page runs no script but its
 * own (CSP with a nonce) and posts nowhere but GitHub. The callback sends the browser back to the console's settings.
 *
 * @param dependencies The flow.
 */
fun Route.gitHubManifestRoutes(dependencies: GitHubManifestRoutesDependencies) = with(dependencies) {
    // GitHub sends the browser back with its session cookie (SameSite=Lax): the state is bound to that session.
    authenticate(TOKEN_AUTH, optional = true) {
        get<GitHubManifestCallbackPath> { params ->
            val session = call.principal<AuthenticatedPrincipal>()?.let(::sessionOf)
            manifests.finish(params.code, params.state, session)
            call.respondRedirect("$webOrigin/console/settings?github=created")
        }
    }
    authenticated {
        get<GitHubManifestStartPath> { params ->
            call.requireStartedHere(webOrigin)
            val principal = call.requirePrincipal()
            principal.requireAnyScope(setOf(GateScopes.ALL_USER))
            val form = manifests.start(principal, params.owner, sessionOf(principal))
            val nonce = Uuid.random().toHexString()
            call.response.header(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'nonce-$nonce'; style-src 'unsafe-inline'; form-action $webUrl; " +
                    "base-uri 'none'; frame-ancestors 'none'",
            )
            call.response.header("Referrer-Policy", "no-referrer")
            // The page carries a single-use state: never kept by a cache.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(manifestPage(form, nonce), ContentType.Text.Html)
        }
    }
}

/**
 * The page that posts the manifest to GitHub at once (and on a click, without scripts).
 *
 * @param form The form.
 * @param nonce The nonce of its script.
 * @return The HTML.
 */
internal fun manifestPage(form: ManifestForm, nonce: String): String = """
    <!doctype html>
    <html lang="en">
    <meta charset="utf-8">
    <title>Ostrom → GitHub</title>
    <body style="font-family: system-ui; display: grid; place-items: center; min-height: 100vh; margin: 0">
    <form id="manifest" method="post" action="${escape(form.action)}">
    <input type="hidden" name="manifest" value="${escape(form.manifest)}">
    <p>Opening GitHub to create the Ostrom App…</p>
    <button type="submit">Continue to GitHub</button>
    </form>
    <script nonce="$nonce">document.getElementById('manifest').submit()</script>
    </body>
    </html>
""".trimIndent()

// For an attribute value between double quotes.
private fun escape(text: String): String = text.replace("&", "&amp;").replace("\"", "&quot;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
