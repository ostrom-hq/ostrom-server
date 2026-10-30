// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import kotlin.uuid.Uuid

/** Turns the `{org}` of a path (an id or a short name) into the organization's id. */
interface OrgReferences {
    /**
     * @param ref The `{org}` of the path.
     * @return The organization's id, or null when it names none.
     */
    suspend fun orgId(ref: String): Uuid?
}

/**
 * [OrgReferences] by id only: the orgs module, which knows the short names, is not on this branch yet. Once it is,
 * the module binds an adapter over its `FindOrgUseCase` instead (JOURNAL, SRV-LECTURES-GH).
 */
class IdOrgReferences : OrgReferences {
    override suspend fun orgId(ref: String): Uuid? = Uuid.parseOrNull(ref)
}
