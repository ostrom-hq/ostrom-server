// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.OrgReferences
import dev.ostrom.server.orgs.domain.FindOrgUseCase
import dev.ostrom.server.orgs.domain.OrgNotFoundException
import kotlin.uuid.Uuid

/**
 * [OrgReferences] over the orgs module: an id or a short name, as the `Ostrom-Org` header reads it.
 *
 * @param find The orgs module's lookup.
 */
class OrgsOrgReferences(private val find: FindOrgUseCase) : OrgReferences {
    override suspend fun orgId(ref: String): Uuid? = try {
        find(ref)
    } catch (_: OrgNotFoundException) {
        null
    }
}
