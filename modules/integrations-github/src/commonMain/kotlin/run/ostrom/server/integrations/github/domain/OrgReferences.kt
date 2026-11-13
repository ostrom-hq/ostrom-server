// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import kotlin.uuid.Uuid

/** Reads the organization a request names (`?org=`, `{org}`): its id or its short name. */
interface OrgReferences {
    /**
     * @param ref The id or the short name.
     * @return The organization's id, or null when it names none.
     */
    suspend fun orgId(ref: String): Uuid?
}
