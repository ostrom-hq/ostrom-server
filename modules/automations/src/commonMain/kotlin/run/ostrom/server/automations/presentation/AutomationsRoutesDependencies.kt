// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import run.ostrom.server.automations.domain.ManageAutomationsUseCase
import run.ostrom.server.automations.domain.RunAutomationsUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the automation routes need.
 *
 * @property manage Creates, reads, changes and deletes.
 * @property runs Triggers, tests and lists runs.
 * @property orgResolver The member a request acts for.
 * @property publicUrl The server's public URL, the base of webhook URLs (relative ones when empty).
 */
data class AutomationsRoutesDependencies(
    val manage: ManageAutomationsUseCase,
    val runs: RunAutomationsUseCase,
    val orgResolver: PrincipalOrgResolver,
    val publicUrl: String,
)
