// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.confirmsByConsole
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// A person's token (the CLI's, elevated by `ostrom login --admin` or not; an agent can read it on the developer's
// machine) proposes a workflow at once; a decision it asks (accept, edit an accepted one, delete) waits for a
// confirmation on the console (audit CLI H1), unless the person may not decide it at all.
internal suspend fun CompilerRoutesDependencies.holds(
    call: ApplicationCall,
    actor: GateActor,
    gated: Boolean,
    proposal: Boolean,
): Boolean {
    if (!call.requirePrincipal().confirmsByConsole || proposal) return false
    if (!mayDecide(actor, gated)) throw refusal(actor, gated)
    return true
}

// What a person who may not decide a workflow is told: an editor is let in by the rights, but never on a gate.
internal suspend fun CompilerRoutesDependencies.refusal(actor: GateActor, gated: Boolean): OstromException {
    val onlyTheGate = gated && !mayDecide(actor, gated = true) && mayDecide(actor, gated = false)
    return OstromException(ErrorKind.FORBIDDEN, if (onlyTheGate) GATED else DECIDES, type = EDITORS_ONLY)
}

// Whether this person may decide a workflow, whose gate nodes (before or after the change) are [gated].
internal suspend fun CompilerRoutesDependencies.mayDecide(actor: GateActor, gated: Boolean): Boolean =
    settings.mayDecide(actor.orgId, actor.id, actor.role, gated)

// Whether a workflow document holds a gate node: a gate is Ostrom's security boundary, an owner's or an admin's to
// change whatever the editing rights say.
internal fun gated(document: JsonObject): Boolean = (document["nodes"] as? JsonArray).orEmpty().any {
    ((it as? JsonObject)?.get("kind") as? JsonPrimitive)?.content == "gate"
}
