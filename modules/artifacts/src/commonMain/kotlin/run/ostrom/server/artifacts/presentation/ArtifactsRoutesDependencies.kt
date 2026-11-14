// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.ManageArtifactUseCase
import run.ostrom.server.artifacts.domain.PublishArtifactUseCase
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCase
import run.ostrom.server.artifacts.domain.ViewArtifactUseCase
import run.ostrom.server.artifacts.domain.uploads.CommitArtifactUploadUseCase

/**
 * What the artifact routes need.
 *
 * @property publish Publishes artifacts and versions.
 * @property read Reads them.
 * @property manage Changes settings and shares, deletes.
 * @property view Mints tickets and serves pages.
 * @property locations Where artifacts are read.
 * @property orgResolver The member a request acts for.
 * @property commit Commits uploads as versions.
 */
data class ArtifactsRoutesDependencies(
    val publish: PublishArtifactUseCase,
    val read: ReadArtifactsUseCase,
    val manage: ManageArtifactUseCase,
    val view: ViewArtifactUseCase,
    val locations: ArtifactLocations,
    val orgResolver: ArtifactOrgResolver,
    val commit: CommitArtifactUploadUseCase,
)
