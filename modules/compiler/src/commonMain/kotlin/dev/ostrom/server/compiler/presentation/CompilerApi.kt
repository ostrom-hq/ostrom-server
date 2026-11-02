// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.server.kernel.http.NullsKept
import io.ktor.resources.Resource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The gates catalogue of a repository (contract `getGateCatalog`). */
@Serializable
@Resource("/api/v1/gates/catalog")
class GateCatalogApi(val repo: String? = null) {
    /**
     * The catalogue signed by the instance for the repository's hooks (`ostrom.gates/1`, SECURITY-AUDIT S2-20).
     *
     * @property parent The catalogue, which carries the repository.
     */
    @Serializable
    @Resource("signed")
    class Signed(val parent: GateCatalogApi = GateCatalogApi())
}

/**
 * Who approves what in a repository, from the gates catalogue (not yet in the contract: additive).
 *
 * @property repo The repository, or null for the organization.
 */
@Serializable
@Resource("/api/v1/gates/approvers")
class ApproversApi(val repo: String? = null)

/**
 * Who approves what: every approver with their gates, the gates nobody answers, the actions no gate covers.
 *
 * @property approvers The approvers, by name.
 * @property unanswered The gates that name no approver: their actions can never run.
 * @property ungated The action classes no gate covers.
 */
@Serializable
data class ApproversGraphResponse(
    val approvers: List<ApproverDto>,
    val unanswered: List<ApproverGateDto>,
    val ungated: List<String>,
)

/**
 * One approver and the gates they answer.
 *
 * @property approver The approver, as the workflow names them.
 * @property gates Their gates.
 */
@Serializable
data class ApproverDto(val approver: String, val gates: List<ApproverGateDto>)

/**
 * A gate and the actions it covers.
 *
 * @property workflowId The workflow.
 * @property workflowName Its name.
 * @property nodeId The gate node.
 * @property label The gate's label.
 * @property enforce False while the workflow is only proposed.
 * @property actions The action classes it covers.
 */
@Serializable
data class ApproverGateDto(
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val enforce: Boolean,
    val actions: List<String>,
)

/** The packages (contract `getCurrentPackage`, `listPackages`, `previewPackage`, `getPackage`…). */
@Serializable
@Resource("/api/v1/packages")
class PackagesApi(val repo: String? = null) {
    /** The version the repository's sessions receive. */
    @Serializable
    @Resource("current")
    class Current(val parent: PackagesApi = PackagesApi())

    /** Compiled now, neither stored nor signed. */
    @Serializable
    @Resource("preview")
    class Preview(val parent: PackagesApi = PackagesApi())

    /** The packages audit. */
    @Serializable
    @Resource("audit")
    class Audit(val parent: PackagesApi = PackagesApi(), val limit: Int? = null)

    /** One version, by its envelope or content digest. */
    @Serializable
    @Resource("{digest}")
    class Digest(val parent: PackagesApi = PackagesApi(), val digest: String) {
        /** Its summary. */
        @Serializable
        @Resource("status")
        class Status(val parent: Digest)

        /** Revokes it. */
        @Serializable
        @Resource("revoke")
        class Revoke(val parent: Digest)

        /** Restores it. */
        @Serializable
        @Resource("restore")
        class Restore(val parent: Digest)
    }
}

/** The workflows (contract `listWorkflows`, `createWorkflow`, `getWorkflow`, `saveWorkflow`, `deleteWorkflow`). */
@Serializable
@Resource("/api/v1/workflows")
class WorkflowsApi(val repo: String? = null, val status: String? = null) {
    /** One workflow. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: WorkflowsApi = WorkflowsApi(), val id: String)
}

/**
 * A version as listed (contract `PackageSummary`).
 *
 * @property version The version.
 * @property digest The envelope digest.
 * @property contentDigest The content digest.
 * @property repo The repository, or null for the organization.
 * @property compiledAt When.
 * @property pinned True when the repository's sessions receive it.
 * @property revoked True once revoked.
 * @property revokedBy Who revoked it.
 * @property revokeReason Why.
 * @property counts What it holds.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PackageSummary.Answer::class)
data class PackageSummary(
    val version: String,
    val digest: String,
    val contentDigest: String,
    val repo: String?,
    val compiledAt: String,
    val pinned: Boolean,
    val revoked: Boolean,
    val revokedBy: String? = null,
    val revokeReason: String? = null,
    val counts: Map<String, Int>,
) {
    /** Writes the fields the contract declares required and nullable as `null` when null. */
    object Answer : NullsKept<PackageSummary>(generatedSerializer(), "repo")
}

/**
 * A page of versions.
 *
 * @property items The versions, newest first.
 */
@Serializable
data class PackagesPage(val items: List<PackageSummary>)

/**
 * The body of a preview.
 *
 * @property repo The repository, or null for the organization.
 */
@Serializable
data class PreviewRequest(val repo: String? = null)

/**
 * The body of a revocation.
 *
 * @property reason Why.
 */
@Serializable
data class RevokeRequest(val reason: String)

/**
 * An audit line (contract `AuditEvent`).
 *
 * @property id The line.
 * @property at When.
 * @property event What.
 * @property actor Who.
 * @property subject The package version.
 * @property meta Details.
 */
@Serializable
data class AuditEvent(
    val id: String,
    val at: String,
    val event: String,
    val actor: String,
    val subject: String,
    val meta: JsonObject,
)

/**
 * The audit, newest first.
 *
 * @property items The lines.
 */
@Serializable
data class AuditPage(val items: List<AuditEvent>)

/**
 * A page of workflows.
 *
 * @property items The workflows.
 */
@Serializable
data class WorkflowsPage(val items: List<JsonObject>)

/**
 * The body of a save.
 *
 * @property workflow The workflow.
 * @property expectedVersion The version the editor read; a newer one answers 409 `workflow_conflict`.
 */
@Serializable
data class SaveWorkflowRequest(val workflow: JsonObject, val expectedVersion: Int? = null)
