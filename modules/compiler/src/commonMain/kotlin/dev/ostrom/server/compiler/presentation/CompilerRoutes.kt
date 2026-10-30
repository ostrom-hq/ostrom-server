// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.services.RulesHarness
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.compiler.domain.usecases.PreviewPackageUseCase
import dev.ostrom.server.compiler.domain.usecases.PublishPackageUseCase
import dev.ostrom.server.compiler.domain.usecases.RevokePackageUseCase
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the compiler routes need.
 *
 * @property actors Resolves callers (the gates module's resolver: the organization comes from the credential).
 * @property repository Reads versions, pins and the audit.
 * @property publish Compiles and pins.
 * @property preview Compiles without storing.
 * @property revoke Revokes and restores.
 * @property catalog The gates catalogue.
 * @property workflows Reads and saves workflows.
 * @property publicUrl The address sessions call back (`OSTROM_PUBLIC_URL`), else the request's origin.
 */
data class CompilerRoutesDependencies(
    val actors: GateActorResolver,
    val repository: CompilerRepository,
    val publish: PublishPackageUseCase,
    val preview: PreviewPackageUseCase,
    val revoke: RevokePackageUseCase,
    val catalog: GatesCatalogUseCase,
    val workflows: WorkflowsUseCase,
    val publicUrl: String?,
)

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
        val entries = catalog(actor.orgId, params.repo)
        call.respond(
            buildJsonObject {
                val gated = entries.filter { it.gate != null }.distinctBy { it.gate }
                put("gates", JsonArray(gated.map { RulesHarness.gateJson(it) }))
                put("actions", JsonArray(entries.map { RulesHarness.actionJson(it) }))
                put("failMode", "closed")
                put("waitSeconds", WAIT_SECONDS)
            },
        )
    }
}

private fun Route.packagesRoutes(dependencies: CompilerRoutesDependencies) = with(dependencies) {
    get<PackagesApi.Current> { params ->
        val actor = actorOf(call, CompilerScopes.readingPackages)
        val served = publish(actor.orgId, params.parent.repo, urlOf(call), actor.id)
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
        val reason = call.receive<RevokeRequest>().reason
        call.respond(summary(actor, revoke.revoke(actor.orgId, params.parent.digest, actor.id, reason)))
    }
    post<PackagesApi.Digest.Restore> { params ->
        val actor = personOf(call, CompilerScopes.writingPackages)
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
        if (!call.decides()) onlyProposed(body)
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
        if (!call.decides()) {
            onlyProposed(body.workflow)
            onlyProposed(workflows.get(actor.orgId, params.id).doc.raw)
        }
        val saved = workflows.save(actor.orgId, body.workflow, body.expectedVersion, actor.id, create = false)
        call.respond(documentOf(saved))
    }
    delete<WorkflowsApi.Id> { params ->
        val actor = personOf(call, CompilerScopes.writingWorkflows)
        if (!call.decides()) throw OstromException(ErrorKind.FORBIDDEN, DECIDES)
        workflows.delete(actor.orgId, params.id)
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MAX_AUDIT = 200
private const val WAIT_SECONDS = 300

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
private fun documentOf(stored: StoredWorkflow): JsonObject = JsonObject(
    stored.doc.raw + mapOf(
        "version" to JsonPrimitive(stored.version),
        "updatedBy" to JsonPrimitive(stored.updatedBy),
        "updatedAt" to JsonPrimitive(stored.updatedAt.toString()),
    ),
)
