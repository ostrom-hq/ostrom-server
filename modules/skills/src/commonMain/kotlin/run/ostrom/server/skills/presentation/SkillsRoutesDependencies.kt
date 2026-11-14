// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.skills.domain.ManageSkillCatalogUseCase
import run.ostrom.server.skills.domain.PublishSkillVersionUseCase
import run.ostrom.server.skills.domain.RunnerSessions
import run.ostrom.server.skills.domain.SkillDeliveryUseCase

/**
 * What the skill routes need.
 *
 * @property publish Publishes versions.
 * @property manage Administers the catalog and the personal skills.
 * @property delivery What members receive and report.
 * @property orgResolver The member a request acts for.
 * @property runners The cloud sessions a runner call names.
 */
data class SkillsRoutesDependencies(
    val publish: PublishSkillVersionUseCase,
    val manage: ManageSkillCatalogUseCase,
    val delivery: SkillDeliveryUseCase,
    val orgResolver: PrincipalOrgResolver,
    val runners: RunnerSessions,
)
