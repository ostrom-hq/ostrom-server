// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.SessionState
import kotlin.time.Clock

/**
 * The device of each remote session as it is now (TeamCode's `CloudSessionHostView`): its name and system read at
 * each answer, its host online while an execution reported within the reconciler's heartbeat loss.
 *
 * @param devices The devices of sign-ins; null where the identity module binds none (no name is shown).
 * @param clock The time.
 */
class RemoteHostViews(private val devices: SignedInDevices?, private val clock: Clock) {
    /**
     * Maps sessions to the API, each remote one with its host, the devices read in one query.
     *
     * @param sessions The sessions.
     * @return Their answers, in order.
     */
    suspend fun responses(sessions: List<CloudSession>): List<CloudSessionResponse> {
        val ids = sessions.mapNotNull { it.host?.deviceId }.toSet()
        val known = if (ids.isEmpty() || devices == null) emptyMap() else devices.devices(ids)
        val now = clock.now()
        return sessions.map { session ->
            val host = session.host ?: return@map session.toResponse()
            val device = host.deviceId?.let { known[it] }
            val heartbeat = session.lastHeartbeatAt
            val running = session.state == SessionState.RUNNING || session.state == SessionState.PAUSING
            val online = running && heartbeat != null &&
                now - heartbeat <= ReconcileCloudSessionUseCaseImpl.HEARTBEAT_LOSS
            session.toResponse().copy(
                host = CloudSessionHostDto(
                    deviceId = host.deviceId?.toString(),
                    deviceName = device?.name,
                    platform = device?.platform,
                    osVersion = device?.osVersion,
                    directoryName = host.directoryName,
                    online = online,
                    lastHeartbeatAt = heartbeat?.toString(),
                    permissionMode = host.permissionMode,
                    folderId = host.folderId,
                ),
            )
        }
    }

    /**
     * @param session A session.
     * @return Its answer, with its host when it is remote.
     */
    suspend fun response(session: CloudSession): CloudSessionResponse = responses(listOf(session)).single()
}
