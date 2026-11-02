// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.usecases.catalogJson
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.holdForConfirmation
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.OstromJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The packages, workflows and gates catalogue routes of the contract. Every route is authenticated and scoped to the
 * credential's organization; only a person changes a workflow or revokes a version.
 *
 * @param dependencies The use cases.
 */
fun Route.compilerRoutes(dependencies: CompilerRoutesDependencies) {
    authenticated {
        catalogRoutes(dependencies)
        packagesRoutes(dependencies)
        workflowsRoutes(dependencies)
    }
}

private fun Route.catalogRoutes(dependencies: CompilerRoutesDependencies) = with(dependencies) {
    get<GateCatalogApi> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(catalogJson(catalog(actor.orgId, params.repo)))
    }
    get<GateCatalogApi.Signed> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(policy(actor.orgId, params.parent.repo))
    }
    get<ApproversApi> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(approvers(actor.orgId, params.repo).toResponse())
    }
}

private fun Route.packagesRoutes(dependencies: CompilerRoutesDependencies) = with(dependencies) {
    get<PackagesApi.Current> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        val byAgent = actor.kind == ActorKind.AGENT_SESSION
        val served = publish(actor.orgId, params.parent.repo, urlOf(call), actor.id, byAgent)
        call.respond(served.envelope)
    }
    get<PackagesApi> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(PackagesPage(repository.packages(actor.orgId, params.repo).map { summary(actor, it) }))
    }
    post<PackagesApi.Preview> {
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(preview(actor.orgId, call.receive<PreviewRequest>().repo, urlOf(call)))
    }
    get<PackagesApi.Audit> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        val limit = (params.limit ?: MAX_AUDIT).coerceIn(1, MAX_AUDIT)
        val lines = repository.auditLog(actor.orgId, limit)
        call.respond(AuditPage(lines.map { AuditEvent(it.id, it.at, it.event, it.actor, it.subject, it.meta) }))
    }
    get<PackagesApi.Digest> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        val stored = find(actor, params.digest)
        call.respond(stored.envelope.copy(pinned = pinned(actor, stored)))
    }
    get<PackagesApi.Digest.Status> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(summary(actor, find(actor, params.parent.digest)))
    }
    post<PackagesApi.Digest.Revoke> { params ->
        val actor = personOf(call, CompilerScopes.writingPackages)
        requireManager(actor)
        val reason = call.receive<RevokeRequest>().reason
        call.respond(summary(actor, revoke.revoke(actor.orgId, params.parent.digest, actor.id, reason)))
    }
    post<PackagesApi.Digest.Restore> { params ->
        val actor = personOf(call, CompilerScopes.writingPackages)
        // Second audit M3: a restore puts a revoked package back in force; the CLI's token never does it.
        call.requireDecides(actor)
        call.respond(summary(actor, revoke.restore(actor.orgId, params.parent.digest, actor.id)))
    }
}

private fun Route.workflowsRoutes(dependencies: CompilerRoutesDependencies) = with(dependencies) {
    get<WorkflowsApi> { params ->
        val actor = actorOf(call, CompilerScopes.readingWorkflows)
        val status = params.status?.let(::statusOf)
        call.respond(WorkflowsPage(workflows.list(actor.orgId, params.repo, status).map(::documentOf)))
    }
    post<WorkflowsApi> {
        val actor = personOf(call, CompilerScopes.writingWorkflows)
        val body = call.receive<JsonObject>()
        if (call.holdsDecision(actor, proposed(body))) {
            return@post call.hold(actor, WorkflowWrites.CREATE, CompilerGovernanceChange.payload(null, body))
        }
        if (!call.decides(actor)) onlyProposed(body)
        val saved = workflows.save(actor.orgId, body, null, actor.id, create = true)
        call.respond(HttpStatusCode.Created, documentOf(saved))
    }
    get<WorkflowsApi.Id> { params ->
        call.respond(documentOf(workflows.get(actorOf(call, CompilerScopes.readingWorkflows).orgId, params.id)))
    }
    put<WorkflowsApi.Id> { params ->
        val actor = personOf(call, CompilerScopes.writingWorkflows)
        val body = call.receive<SaveWorkflowRequest>()
        val id = (body.workflow["id"] as? JsonPrimitive)?.content
        if (id != params.id) {
            throw OstromException(ErrorKind.INVALID_REQUEST, "The workflow's id is not the path's.")
        }
        if (!call.decides(actor)) {
            val proposal = proposed(body.workflow) && proposed(workflows.get(actor.orgId, params.id).doc.raw)
            if (call.holdsDecision(actor, proposal)) {
                val payload = CompilerGovernanceChange.payload(params.id, OstromJson.encodeToJsonElement(body))
                return@put call.hold(actor, WorkflowWrites.SAVE, payload)
            }
            if (!proposal) throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
        }
        val saved = workflows.save(actor.orgId, body.workflow, body.expectedVersion, actor.id, create = false)
        call.respond(documentOf(saved))
    }
    delete<WorkflowsApi.Id> { params ->
        val actor = personOf(call, CompilerScopes.writingWorkflows)
        if (call.holdsDecision(actor, proposal = false)) {
            return@delete call.hold(actor, WorkflowWrites.DELETE, CompilerGovernanceChange.payload(params.id, null))
        }
        if (!call.decides(actor)) throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
        workflows.delete(actor.orgId, params.id)
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MAX_AUDIT = 200

private suspend fun CompilerRoutesDependencies.find(actor: GateActor, digest: String): StoredPackage =
    repository.findPackage(actor.orgId, digest) ?: throw OstromException(ErrorKind.NOT_FOUND, "Package not found.")

private suspend fun CompilerRoutesDependencies.pinned(actor: GateActor, stored: StoredPackage): Boolean =
    repository.pin(actor.orgId, stored.envelope.repo)?.contentDigest == stored.envelope.contentDigest

private suspend fun CompilerRoutesDependencies.summary(actor: GateActor, stored: StoredPackage) = PackageSummary(
    version = stored.envelope.version,
    digest = stored.envelope.digest,
    contentDigest = stored.envelope.contentDigest,
    repo = stored.envelope.repo,
    compiledAt = stored.compiledAt,
    pinned = pinned(actor, stored),
    revoked = stored.revoked,
    revokedBy = stored.revokedBy,
    revokeReason = stored.revokeReason,
    counts = stored.counts,
)

private fun statusOf(value: String): WorkflowStatus =
    WorkflowStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw OstromException(ErrorKind.INVALID_REQUEST, "Unknown workflow status.")

// The document as saved, with its version, author and date: `version` is what a save sends back as `expectedVersion`.
// A token's decision on a workflow, held for the console: nothing is written.
private suspend fun ApplicationCall.hold(actor: GateActor, kind: String, payload: JsonObject) =
    holdForConfirmation(actor.orgId, GovernanceScopes.HARNESS_WRITE, GovernanceWrite(kind, payload))

/**
 * A stored workflow as the routes show it.
 *
 * @param stored The workflow.
 *
 * @return Its document, with its version and last change.
 */
internal fun documentOf(stored: StoredWorkflow): JsonObject = JsonObject(
    stored.doc.raw + mapOf(
        "version" to JsonPrimitive(stored.version),
        "updatedBy" to JsonPrimitive(stored.updatedBy),
        "updatedAt" to JsonPrimitive(stored.updatedAt.toString()),
    ),
)
