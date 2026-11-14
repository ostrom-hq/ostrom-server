// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sessions

import kotlin.uuid.Uuid

/**
 * An organization's cloud profile, as what names one sees it.
 *
 * @property id Its id, what an automation's action or a workflow's agent node names.
 * @property name Its name.
 */
data class NamedCloudProfile(val id: Uuid, val name: String)

/**
 * The organizations' cloud profiles (`runners.cloud.profiles.v1`), read by what names one: an automation's action and
 * a workflow's agent node are refused at save when they name an id none of them has, and an import matches TeamCode's
 * profiles to them. The cloud module binds it; unbound (sessions on Docker or on registered runners), no profile is
 * checked, as such a server ignores them.
 */
fun interface CloudProfileDirectory {
    /**
     * The organization's profiles.
     *
     * @param orgId The organization.
     *
     * @return They, in any order.
     */
    suspend fun profiles(orgId: Uuid): List<NamedCloudProfile>
}

/**
 * Whether [id] is one of the organization's profiles.
 *
 * @param orgId The organization.
 * @param id The profile.
 *
 * @return True when it is.
 */
suspend fun CloudProfileDirectory.has(orgId: Uuid, id: Uuid): Boolean = profiles(orgId).any { it.id == id }
