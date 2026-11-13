// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.sharing.domain.GroupMembersUseCase
import run.ostrom.server.sharing.domain.GroupsUseCase

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
