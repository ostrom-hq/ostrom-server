package dev.ostrom.server.artifacts.presentation

import dev.ostrom.server.artifacts.domain.ArtifactLocations
import dev.ostrom.server.artifacts.domain.ServedFile
import dev.ostrom.server.artifacts.domain.ViewArtifactUseCase
import dev.ostrom.server.artifacts.infrastructure.ArtifactsConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.util.getOrFail
import kotlin.uuid.Uuid

/**
 * The pages of artifacts, served on the isolated content origin only (`OSTROM_ARTIFACTS_CONTENT_ORIGIN`):
 * `/a/<id>/<ticket>/<path>` for members (the ticket comes from the API) and `/l/<slug>/<path>` for public links.
 * Every answer, errors included, carries TeamCode's content policy: the page runs sandboxed in an opaque origin,
 * without cookies or storage, and reaches only its own files and a few script CDNs. Nothing is served when no
 * content origin is configured, nor on another host.
 *
 * @param view Serves the files.
 * @param locations Where the content origin is.
 */
fun Route.artifactContentRoutes(view: ViewArtifactUseCase, locations: ArtifactLocations) {
    val host = ArtifactsConfig.contentHost(locations) ?: return
    val headers = contentHeaders(locations)
    get("/a/{id}/{ticket}/{path...}") {
        val rawId = call.parameters.getOrFail("id")
        val ticket = call.parameters.getOrFail("ticket")
        val id = Uuid.parseOrNull(rawId)
        val path = call.request.path().substringAfter("/a/$rawId/$ticket/")
        val file = if (id == null || call.request.host() != host) null else view.byTicket(id, ticket, path)
        call.serve(file, headers)
    }
    get("/l/{slug}/{path...}") {
        val slug = call.parameters.getOrFail("slug")
        val path = call.request.path().substringAfter("/l/$slug").removePrefix("/")
        val file = if (call.request.host() != host) null else view.byLink(slug, path)
        call.serve(file, headers)
    }
}

/**
 * The content policy, on every answer of the content origin. Only the console may frame the pages when its
 * address is absolute; otherwise any page may, which opens nothing: the content never holds a session.
 */
internal fun contentHeaders(locations: ArtifactLocations): Map<String, String> = mapOf(
    "Content-Security-Policy" to listOf(
        "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox",
        "default-src 'none'",
        "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: https://cdnjs.cloudflare.com " +
            "https://cdn.jsdelivr.net/npm/ https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com",
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
        "font-src 'self' data: https://fonts.gstatic.com",
        "img-src 'self' data: blob:",
        "media-src 'self' data: blob:",
        "connect-src 'self'",
        "form-action 'none'",
        "frame-src 'none'",
        "object-src 'none'",
        "base-uri 'none'",
        "frame-ancestors ${frameAncestor(locations.viewerUrl)}",
    ).joinToString("; "),
    "Referrer-Policy" to "no-referrer",
    "X-Content-Type-Options" to "nosniff",
    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), usb=(), payment=(), display-capture=()",
    "Access-Control-Allow-Origin" to "*",
    "X-Robots-Tag" to "noindex, nofollow",
)

private fun frameAncestor(viewerUrl: String): String {
    val scheme = viewerUrl.substringBefore("://", "")
    return if (scheme == "http" || scheme == "https") {
        "$scheme://" + viewerUrl.substringAfter("://").substringBefore('/')
    } else {
        "*"
    }
}

private suspend fun ApplicationCall.serve(file: ServedFile?, headers: Map<String, String>) {
    headers.forEach { (name, value) -> response.header(name, value) }
    if (file == null) {
        response.header("Cache-Control", "no-store, no-transform")
        respondText("Not found.", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
    }
    response.header("Cache-Control", "private, max-age=600")
    if (file.contentType.startsWith("text/markdown")) {
        respondText(MarkdownPage.render(file.bytes.decodeToString()), ContentType.Text.Html)
    } else {
        respondBytes(file.bytes, ContentType.parse(file.contentType))
    }
}
