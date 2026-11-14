// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.presentation

import run.ostrom.server.hooks.domain.HookDeliveryUseCase
import run.ostrom.server.hooks.domain.ManageHookCatalogUseCase
import run.ostrom.server.hooks.domain.PublishHookVersionUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.skills.domain.RunnerSessions

/**
 * What the hook routes need.
 *
 * @property publish Publishes versions.
 * @property manage Administers the catalog.
 * @property delivery What members receive.
 * @property orgResolver The member a request acts for.
 * @property runners The cloud sessions a runner call names.
 */
data class HooksRoutesDependencies(
    val publish: PublishHookVersionUseCase,
    val manage: ManageHookCatalogUseCase,
    val delivery: HookDeliveryUseCase,
    val orgResolver: PrincipalOrgResolver,
    val runners: RunnerSessions,
)
