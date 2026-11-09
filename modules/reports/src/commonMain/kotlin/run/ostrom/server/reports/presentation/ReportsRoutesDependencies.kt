// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.reports.domain.GetSpendingUseCase
import run.ostrom.server.reports.domain.ListActivityUseCase

/**
 * What the reports routes need.
 *
 * @property getSpending The spending report.
 * @property listActivity The activity feed.
 * @property orgs The member a request acts for (the credential's organization, or the one `Ostrom-Org` names).
 */
data class ReportsRoutesDependencies(
    val getSpending: GetSpendingUseCase,
    val listActivity: ListActivityUseCase,
    val orgs: PrincipalOrgResolver,
)
