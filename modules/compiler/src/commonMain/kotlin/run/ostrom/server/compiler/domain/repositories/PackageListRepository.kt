// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.repositories

import run.ostrom.server.compiler.domain.models.ListedPackage
import kotlin.uuid.Uuid

/** The packages as their list shows them: one query, without their envelopes. */
interface PackageListRepository {
    /**
     * Lists the packages, newest first, 1000 at most, each with whether its repository pins it.
     *
     * @param orgId The organization.
     * @param repo Only those of this repository when not null.
     * @return The packages.
     */
    suspend fun listPackages(orgId: Uuid, repo: String?): List<ListedPackage>
}
