// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.runners.domain.ResolveCloudActorUseCase
import run.ostrom.server.runners.domain.ResolveCloudActorUseCaseImpl
import run.ostrom.server.runners.domain.CloudConversationService
import run.ostrom.server.runners.domain.CloudFileService

/**
 * What the conversation routes need.
 *
 * @property conversation Transcript reads, inputs and the agent's options.
 * @property orgResolver The member a request acts for.
 * @property hosts Shows a remote session's device in the sessions answered.
 * @property resolveActor Refuses an `Ostrom-On-Behalf-Of` naming another member on the owner's own writes.
 * @property files Files sent to sessions (`runners.cloud.files.v1`); null without the artifacts module's bucket: the
 *   file routes are not served and a message naming files is refused (`400 files_unsupported`).
 */
data class CloudConversationRoutesDependencies(
    val conversation: CloudConversationService,
    val orgResolver: PrincipalOrgResolver,
    val hosts: RemoteHostViews,
    val resolveActor: ResolveCloudActorUseCase = ResolveCloudActorUseCaseImpl(null),
    val files: CloudFileService? = null,
)
