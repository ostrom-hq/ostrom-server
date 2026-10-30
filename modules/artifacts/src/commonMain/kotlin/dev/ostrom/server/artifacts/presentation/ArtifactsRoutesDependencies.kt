// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.presentation

import dev.ostrom.server.artifacts.domain.ArtifactLocations
import dev.ostrom.server.artifacts.domain.ManageArtifactUseCase
import dev.ostrom.server.artifacts.domain.PublishArtifactUseCase
import dev.ostrom.server.artifacts.domain.ReadArtifactsUseCase
import dev.ostrom.server.artifacts.domain.ViewArtifactUseCase
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the artifact routes need.
 *
 * @property publish Publishes artifacts and versions.
 * @property read Reads them.
 * @property manage Changes settings and shares, deletes.
 * @property view Mints tickets and serves pages.
 * @property locations Where artifacts are read.
 * @property orgResolver The member a request acts for.
 */
data class ArtifactsRoutesDependencies(
    val publish: PublishArtifactUseCase,
    val read: ReadArtifactsUseCase,
    val manage: ManageArtifactUseCase,
    val view: ViewArtifactUseCase,
    val locations: ArtifactLocations,
    val orgResolver: PrincipalOrgResolver,
)
