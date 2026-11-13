// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.presentation

import run.ostrom.server.admin.domain.InstanceAdminsUseCase
import run.ostrom.server.admin.domain.InstanceSettingsUseCase

/**
 * What the admin routes need.
 *
 * @property admins The administrators.
 * @property settings The settings sections.
 */
class AdminRoutesDependencies(val admins: InstanceAdminsUseCase, val settings: InstanceSettingsUseCase)
