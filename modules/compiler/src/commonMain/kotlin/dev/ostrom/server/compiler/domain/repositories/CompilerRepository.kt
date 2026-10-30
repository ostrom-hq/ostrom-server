// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.repositories

import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.PackageAudit
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The compiler's storage: workflows, their decisions, harness versions, packages, pins and the audit. */
interface CompilerRepository {
    /**
     * Lists an organization's workflows.
     *
     * @param orgId The organization.
     * @return The workflows, oldest first.
     */
    suspend fun workflows(orgId: Uuid): List<StoredWorkflow>

    /**
     * Reads a workflow.
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @return The workflow, or null.
     */
    suspend fun workflow(orgId: Uuid, id: String): StoredWorkflow?

    /**
     * Saves a workflow if its version is still [expectedVersion] (null: whatever it is, or a new workflow).
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @param raw Its document.
     * @param expectedVersion The version the editor read.
     * @param by Who saves.
     * @param at When.
     * @return The saved workflow, or null when someone saved another version in between.
     */
    suspend fun saveWorkflow(
        orgId: Uuid,
        id: String,
        raw: JsonObject,
        expectedVersion: Int?,
        by: String,
        at: Instant,
    ): StoredWorkflow?

    /**
     * Deletes a workflow.
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @return False when there was none.
     */
    suspend fun deleteWorkflow(orgId: Uuid, id: String): Boolean

    /**
     * Lists the decisions taken on workflows.
     *
     * @param orgId The organization.
     * @return The decisions.
     */
    suspend fun decisions(orgId: Uuid): List<DecisionDoc>

    /**
     * Records a decision.
     *
     * @param orgId The organization.
     * @param decision The decision.
     */
    suspend fun addDecision(orgId: Uuid, decision: DecisionDoc)

    /**
     * The version a harness digest already has in a repository.
     *
     * @param orgId The organization.
     * @param repo The repository, or null for the organization.
     * @param digest The harness digest.
     * @return Its version, or null when it is new.
     */
    suspend fun harness(orgId: Uuid, repo: String?, digest: String): HarnessStamp?

    /**
     * Records a harness version; the first one recorded for a digest wins.
     *
     * @param orgId The organization.
     * @param repo The repository.
     * @param digest The harness digest.
     * @param stamp Its version.
     * @return The version recorded for the digest.
     */
    suspend fun saveHarness(orgId: Uuid, repo: String?, digest: String, stamp: HarnessStamp): HarnessStamp

    /**
     * Reads a package by its envelope digest or its content digest.
     *
     * @param orgId The organization.
     * @param digest Either digest.
     * @return The package, or null.
     */
    suspend fun findPackage(orgId: Uuid, digest: String): StoredPackage?

    /**
     * Lists the packages, newest first.
     *
     * @param orgId The organization.
     * @param repo Only those of this repository when not null.
     * @return The packages.
     */
    suspend fun packages(orgId: Uuid, repo: String?): List<StoredPackage>

    /**
     * Records a package; the first one recorded for a content digest wins.
     *
     * @param orgId The organization.
     * @param stored The package.
     * @return The package recorded for its content.
     */
    suspend fun savePackage(orgId: Uuid, stored: StoredPackage): StoredPackage

    /**
     * Replaces a package's revocation.
     *
     * @param orgId The organization.
     * @param stored The package, revoked or restored.
     */
    suspend fun updatePackage(orgId: Uuid, stored: StoredPackage)

    /**
     * The pinned version of a repository.
     *
     * @param orgId The organization.
     * @param repo The repository, or null for the organization.
     * @return The pin, or null.
     */
    suspend fun pin(orgId: Uuid, repo: String?): PackagePin?

    /**
     * Pins a version, or removes the pin.
     *
     * @param orgId The organization.
     * @param repo The repository.
     * @param pin The pin, or null to remove it.
     */
    suspend fun setPin(orgId: Uuid, repo: String?, pin: PackagePin?)

    /**
     * Writes an audit line.
     *
     * @param orgId The organization.
     * @param line The line.
     */
    suspend fun audit(orgId: Uuid, line: PackageAudit)

    /**
     * Reads the audit, newest first.
     *
     * @param orgId The organization.
     * @param limit At most this many lines.
     * @return The lines.
     */
    suspend fun auditLog(orgId: Uuid, limit: Int): List<PackageAudit>
}
