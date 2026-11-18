// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.budgets.domain.GetMemberCapacityUseCase
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * What the member capacity route needs.
 *
 * @property callers Resolves the member of each call.
 * @property capacity Reads a member's capacity.
 */
data class MemberCapacityRoutesDependencies(val callers: CallerResolver, val capacity: GetMemberCapacityUseCase)
