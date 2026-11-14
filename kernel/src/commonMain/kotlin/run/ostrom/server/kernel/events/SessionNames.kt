// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.events

import kotlin.uuid.Uuid

/**
 * The names of the organization's sessions a page of the journal refers to (TeamCode's read of `cloud_sessions.name`
 * with its notification sources): a notification is titled with its session's name. The runners module binds it on its
 * cloud sessions, so the notification router reads them without that module's tables. Unbound, no session has a name.
 */
fun interface SessionNames {
    /**
     * @param orgId The organization.
     * @param ids The sessions' ids, as the journal and the waits name them; an id that names no session is skipped.
     *
     * @return The name of each session that exists, by its id.
     */
    suspend fun of(orgId: Uuid, ids: Set<String>): Map<String, String>
}
