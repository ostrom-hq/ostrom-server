// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.sharing.domain.ForecastService

/**
 * What the forecasts route needs.
 *
 * @property callers Resolves the member a credential acts for.
 * @property forecasts The forecasts.
 */
data class ForecastRoutesDependencies(val callers: CallerResolver, val forecasts: ForecastService)
