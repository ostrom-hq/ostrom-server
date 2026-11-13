// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.skills.domain.ManageSkillCatalogUseCase
import run.ostrom.server.skills.domain.PublishSkillVersionUseCase
import run.ostrom.server.skills.domain.SkillDeliveryUseCase

/**
 * What the skill routes need.
 *
 * @property publish Publishes versions.
 * @property manage Administers the catalog and the personal skills.
 * @property delivery What members receive and report.
 * @property orgResolver The member a request acts for.
 */
data class SkillsRoutesDependencies(
    val publish: PublishSkillVersionUseCase,
    val manage: ManageSkillCatalogUseCase,
    val delivery: SkillDeliveryUseCase,
    val orgResolver: PrincipalOrgResolver,
)
