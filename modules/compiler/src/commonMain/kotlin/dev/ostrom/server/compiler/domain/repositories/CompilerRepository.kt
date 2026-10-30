// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.repositories

import dev.ostrom.server.compiler.domain.models.PackageAudit
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import kotlin.uuid.Uuid

/** The compiler's storage: its workflows and decisions ([WorkflowsRepository]), harnesses, packages, pins, audit. */
interface CompilerRepository : WorkflowsRepository {
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
