// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.presentation

import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.sharing.domain.GroupMembersUseCase
import dev.ostrom.server.sharing.domain.GroupsUseCase

/**
 * What the group routes need.
 *
 * @property callers Resolves the member a credential acts for.
 * @property groups The groups.
 * @property members Their members.
 */
data class GroupRoutesDependencies(
    val callers: CallerResolver,
    val groups: GroupsUseCase,
    val members: GroupMembersUseCase,
)
