// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path

/**
 * Every route the application mounts, as `METHOD /path/{param}`, read from Ktor's routing tree at run time: the list
 * the isolation matrix must cover, so a route added without its case fails the build.
 */
internal fun Application.mountedRoutes(): Set<String> =
    plugin(RoutingRoot).getAllRoutes().map { "${it.method()} ${it.template()}" }.sorted().toSet()

// The method of the nearest `get {}`, `post {}`… above the handler; a handler with none answers any method.
private fun RoutingNode.method(): String {
    var node: RoutingNode? = this
    while (node != null) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector) return selector.method.value
        node = node.parent
    }
    return "ANY"
}

// The path with its parameters as `{name}`, whatever selectors (authentication, query parameters) sit in between.
private fun RoutingNode.template(): String = path.let { if (it.startsWith("/")) it else "/$it" }
