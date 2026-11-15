// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.budgets.domain.PoolSizingService
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * What the pool size route needs.
 *
 * @property callers Resolves the member a credential acts for.
 * @property sizing The pool's sizing.
 */
data class PoolSizeRoutesDependencies(val callers: CallerResolver, val sizing: PoolSizingService)
