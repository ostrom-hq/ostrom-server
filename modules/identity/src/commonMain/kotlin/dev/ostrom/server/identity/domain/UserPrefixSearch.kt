// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import kotlin.uuid.Uuid

/** Which of some users a prefix names: the member search of an organization. */
interface UserPrefixSearch {
    /**
     * Keeps the users whose login or name starts with [prefix], whatever the case.
     *
     * @param ids The users to look at, a team bounded by its seats.
     * @param prefix The start of the login or name.
     * @return The users it names.
     */
    suspend fun matching(ids: Collection<Uuid>, prefix: String): Set<Uuid>
}
