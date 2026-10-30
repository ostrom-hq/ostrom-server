// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey

/** The template of a request that matched no route (a 404 before routing): never its path. */
const val UNMATCHED_ROUTE: String = "unmatched"

private val routeTemplateKey = AttributeKey<String>("ostrom.route.template")

/**
 * The template of the route a call matched, such as `/api/v1/artifacts/{id}/content` or `/l/{slug}/{...}` (Ktor names
 * a tail segment `{...}`): what logs, traces and metrics name a request by. Never the concrete path, which can hold an
 * artifact ticket, a public link's slug or another access secret (SECURITY-AUDIT S2-45), nor the query.
 * [UNMATCHED_ROUTE] until routing matched.
 */
val ApplicationCall.routeTemplate: String get() = attributes.getOrNull(routeTemplateKey) ?: UNMATCHED_ROUTE

/** Records the matched route's template on every call, for [routeTemplate]. Installed by [configureHttp]. */
internal fun Application.recordRouteTemplates() {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        call.attributes.put(routeTemplateKey, routeTemplate(call.route.toString()))
    }
}

/**
 * The template of a routing node from Ktor's description of it, without the selector segments that are not path:
 * `/(authenticate ostrom-token)/api/v1/secrets/[limit?]/(method:GET)` becomes `/api/v1/secrets`.
 *
 * @param node Ktor's description of the matched routing node.
 *
 * @return The template, `/` for the root.
 */
internal fun routeTemplate(node: String): String = "/" + node.split('/').filter { segment ->
    segment.isNotEmpty() && !segment.isSelector('(', ')') && !segment.isSelector('[', ']')
}.joinToString("/")

private fun String.isSelector(open: Char, close: Char): Boolean = startsWith(open) && endsWith(close)
