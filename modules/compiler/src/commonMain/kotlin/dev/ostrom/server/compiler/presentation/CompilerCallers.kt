// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.auditGovernance
import dev.ostrom.server.kernel.auth.governs
import dev.ostrom.server.kernel.auth.outsideOrganization
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
        ?: throw principal.outsideOrganization()
}

internal suspend fun CompilerRoutesDependencies.personOf(call: ApplicationCall, accepted: Set<String>): GateActor =
    actorOf(call, accepted).takeIf { it.kind == ActorKind.USER }
        ?: throw OstromException(ErrorKind.FORBIDDEN, "Only a person changes workflows and packages.")

// Only a person's browser session decides (SECURITY-AUDIT S2-04): a token with `workflows:write` (the CLI's, which
// an agent can read on the developer's machine) proposes a workflow, never accepts, edits an accepted one or deletes.
// And only an owner or an admin (second audit H4): any other member, in the browser too, proposes. A workflow is also
// decided with the token of `ostrom login --admin` ([governing] `harness:write`, S2-52 decision of 02/11/2026).
internal fun ApplicationCall.decides(actor: GateActor, governing: String? = null): Boolean {
    val principal = requirePrincipal()
    val governs = GateScopes.ALL_USER in principal.scopes || governing != null && principal.governs(governing)
    return governs && actor.role in MANAGERS
}

// Whether the actor decides workflows; a decision the elevated token makes (not a console session's) is recorded in
// the audit log first.
internal suspend fun ApplicationCall.decidesWorkflows(actor: GateActor): Boolean {
    val decides = decides(actor, GovernanceScopes.HARNESS_WRITE)
    val principal = requirePrincipal()
    if (decides && GateScopes.ALL_USER !in principal.scopes) auditGovernance(principal, GovernanceScopes.HARNESS_WRITE)
    return decides
}

// Revoking a package stops every session that runs it: an owner or an admin, with any of their credentials, so a
// leaked package is stopped from the terminal too (second audit H4).
internal fun requireManager(actor: GateActor) {
    if (actor.role !in MANAGERS) throw OstromException(ErrorKind.FORBIDDEN, MANAGES)
}

// Restoring puts a revoked package back in force: an owner's or an admin's browser session only (second audit M3).
internal fun ApplicationCall.requireDecides(actor: GateActor) {
    if (!decides(actor)) throw OstromException(ErrorKind.FORBIDDEN, RESTORES)
}

private val MANAGERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)

internal const val MANAGES = "Only an owner or an admin revokes a package."
internal const val RESTORES = "Only an owner or an admin signed in to the console restores a package."

internal fun onlyProposed(document: JsonObject) {
    val status = (document["status"] as? JsonPrimitive)?.content
    if (status != "proposed") throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
}

internal const val DECIDES =
    "Only an owner or an admin signed in to the console, or with ostrom login --admin, accepts, edits or deletes a " +
        "workflow."

/** The scopes the compiler routes require; a user's browser session (`user:*`) holds them all. */
object CompilerScopes {
    /** Reads the workflows. */
    const val WORKFLOWS_READ: String = "workflows:read"

    /** Proposes workflows; accepting one needs a browser session. */
    const val WORKFLOWS_WRITE: String = "workflows:write"

    /** Reads the session packages, their audit and the gates catalogue. */
    const val PACKAGES_READ: String = "packages:read"

    /** Revokes a package version (an owner or an admin); restoring one needs the console too. */
    const val PACKAGES_WRITE: String = "packages:write"

    /** Who reads the catalogue and the packages: a session starting needs both. */
    val readingPackages: Set<String> =
        setOf(GateScopes.ALL_USER, GateScopes.AGENT_SESSION, WORKFLOWS_READ, PACKAGES_READ)

    /** Who revokes. */
    val writingPackages: Set<String> = setOf(GateScopes.ALL_USER, PACKAGES_WRITE)

    /** Who reads the workflows. */
    val readingWorkflows: Set<String> =
        setOf(GateScopes.ALL_USER, GateScopes.AGENT_SESSION, WORKFLOWS_WRITE, WORKFLOWS_READ)

    /** Who writes them: proposing takes `workflows:write`, deciding the token of `ostrom login --admin` too. */
    val writingWorkflows: Set<String> = setOf(GateScopes.ALL_USER, WORKFLOWS_WRITE, GovernanceScopes.HARNESS_WRITE)
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
internal fun serverUrlOf(publicUrl: String?, origin: () -> String): String =
    if (publicUrl == null) origin() else publicUrl.trimEnd('/')
