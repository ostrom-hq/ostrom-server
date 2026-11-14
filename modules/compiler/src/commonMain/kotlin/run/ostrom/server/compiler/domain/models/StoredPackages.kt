// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.models

import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.core.api.models.WorkflowStatus
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * A saved workflow: its document as saved, and its version for optimistic saves.
 *
 * @property doc The document.
 * @property version Incremented at each save, from 1.
 * @property updatedBy Who saved it last.
 * @property updatedAt When.
 */
data class StoredWorkflow(val doc: WorkflowDoc, val version: Int, val updatedBy: String, val updatedAt: Instant)

/**
 * The documents a package, a gates catalogue or an approvers graph is built from: every workflow but the rejected ones,
 * which compile nothing (a proposal a person turned down is only kept, so that the analysis never proposes it again).
 *
 * @return The documents, in their order.
 */
fun List<StoredWorkflow>.compiled(): List<WorkflowDoc> =
    filter { it.doc.workflow.status != WorkflowStatus.REJECTED }.map { it.doc }

/**
 * A compiled package version, signed once: the same content keeps its version and signature.
 *
 * @property envelope The signed envelope, `pinned` as it was at compilation.
 * @property compiledAt When it was compiled (`toISOString`).
 * @property counts What it holds (workflows, nodes, hooks, scripts, enforced scripts, notifications, files).
 * @property revokedBy Who revoked it.
 * @property revokeReason Why.
 */
data class StoredPackage(
    val envelope: PackageEnvelope,
    val compiledAt: String,
    val counts: Map<String, Int>,
    val revokedBy: String? = null,
    val revokeReason: String? = null,
) {
    /** True once revoked: launchers refuse it. */
    val revoked: Boolean get() = envelope.revoked
}

/**
 * The version a repository's sessions receive.
 *
 * @property contentDigest The pinned content.
 * @property by Who pinned it (`compiler (auto pin)` for the automatic pin).
 * @property at When.
 */
data class PackagePin(val contentDigest: String, val by: String, val at: Instant)

/**
 * A line of the packages audit: metadata only, never a script, a message or an output.
 *
 * @property id The line.
 * @property at When.
 * @property event `package.compiled`, `package.pinned`, `package.revoked` or `package.restored`.
 * @property actor Who.
 * @property subject The package version.
 * @property meta Details.
 */
data class PackageAudit(
    val id: String,
    val at: String,
    val event: String,
    val actor: String,
    val subject: String,
    val meta: JsonObject,
)

/**
 * How an organization compiles, as its owners and admins set it in the console (`/api/v1/packages/settings`).
 *
 * @property manualPin True when a person pins each version (`pin` `manual`).
 * @property codePublishers Who may publish code in a script, lower case, or null for everyone.
 * @property prodApprover The approver of a gate that names none.
 */
data class CompilerSettings(
    val manualPin: Boolean = false,
    val codePublishers: List<String>? = null,
    val prodApprover: String? = null,
)
