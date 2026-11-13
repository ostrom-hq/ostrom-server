// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.OrgReferences
import run.ostrom.server.orgs.domain.FindOrgUseCase
import run.ostrom.server.orgs.domain.OrgNotFoundException
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
