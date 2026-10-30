// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The scope first (SECURITY-AUDIT S2-03): a credential that may not use the route learns nothing about its org.
internal suspend fun CompilerRoutesDependencies.actorOf(call: ApplicationCall, accepted: Set<String>): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(accepted)
    return actors.resolve(principal)
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")
}

internal suspend fun CompilerRoutesDependencies.personOf(call: ApplicationCall, accepted: Set<String>): GateActor =
    actorOf(call, accepted).takeIf { it.kind == ActorKind.USER }
        ?: throw OstromException(ErrorKind.FORBIDDEN, "Only a person changes workflows and packages.")

// Only a person's browser session decides (SECURITY-AUDIT S2-04): a token with `workflows:write` (the CLI's, which
// an agent can read on the developer's machine) proposes a workflow, never accepts, edits an accepted one or deletes.
internal fun ApplicationCall.decides(): Boolean = GateScopes.ALL_USER in requirePrincipal().scopes

internal fun onlyProposed(document: JsonObject) {
    val status = (document["status"] as? JsonPrimitive)?.content
    if (status != "proposed") throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
}

internal const val DECIDES = "Only a person signed in to the console accepts, edits or deletes a workflow."

/** The scopes the compiler routes require; a user's browser session (`user:*`) holds them all. */
object CompilerScopes {
    /** Reads the workflows. */
    const val WORKFLOWS_READ: String = "workflows:read"

    /** Proposes workflows; accepting one needs a browser session. */
    const val WORKFLOWS_WRITE: String = "workflows:write"

    /** Reads the session packages, their audit and the gates catalogue. */
    const val PACKAGES_READ: String = "packages:read"

    /** Revokes and restores a package version. */
    const val PACKAGES_WRITE: String = "packages:write"

    /** Who reads the catalogue and the packages: a session starting needs both. */
    val readingPackages: Set<String> =
        setOf(GateScopes.ALL_USER, GateScopes.AGENT_SESSION, WORKFLOWS_READ, PACKAGES_READ)

    /** Who revokes. */
    val writingPackages: Set<String> = setOf(GateScopes.ALL_USER, PACKAGES_WRITE)

    /** Who reads the workflows. */
    val readingWorkflows: Set<String> =
        setOf(GateScopes.ALL_USER, GateScopes.AGENT_SESSION, WORKFLOWS_WRITE, WORKFLOWS_READ)

    /** Who writes them. */
    val writingWorkflows: Set<String> = setOf(GateScopes.ALL_USER, WORKFLOWS_WRITE)
}

internal fun CompilerRoutesDependencies.urlOf(call: ApplicationCall): String = serverUrlOf(publicUrl) {
    call.request.origin.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }
}

/**
 * The address sessions call back.
 *
 * @param publicUrl `OSTROM_PUBLIC_URL`, when set.
 * @param origin The request's origin.
 * @return The public URL without its trailing slash, else the origin.
 */
internal fun serverUrlOf(publicUrl: String?, origin: () -> String): String = publicUrl?.trimEnd('/') ?: origin()
