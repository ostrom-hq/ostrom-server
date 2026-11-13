// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.gates.presentation.requireAnyScope
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.confirmsByConsole
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
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

// Only a person's browser session decides (SECURITY-AUDIT S2-04), and only an owner's or an admin's (second audit H4):
// any other member, in the browser too, proposes.
internal fun ApplicationCall.decides(actor: GateActor): Boolean {
    val principal = requirePrincipal()
    return !principal.confirmsByConsole && GateScopes.ALL_USER in principal.scopes && actor.role in MANAGERS
}

// A person's token (the CLI's, elevated by `ostrom login --admin` or not; an agent can read it on the developer's
// machine) proposes a workflow at once; a decision it asks (accept, edit an accepted one, delete) is an owner's or an
// admin's only, and waits for their confirmation on the console (audit CLI H1).
internal fun ApplicationCall.holdsDecision(actor: GateActor, proposal: Boolean): Boolean {
    if (!requirePrincipal().confirmsByConsole || proposal) return false
    if (actor.role !in MANAGERS) throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
    return true
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

internal fun proposed(document: JsonObject): Boolean = (document["status"] as? JsonPrimitive)?.content == "proposed"

internal fun onlyProposed(document: JsonObject) {
    if (!proposed(document)) throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
}

internal const val DECIDES =
    "Only an owner or an admin accepts, edits or deletes a workflow, signed in to the console or confirming there."

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
