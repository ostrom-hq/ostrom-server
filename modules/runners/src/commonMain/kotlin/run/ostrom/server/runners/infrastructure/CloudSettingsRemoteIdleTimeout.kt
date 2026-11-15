// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudSettingsSource
import run.ostrom.server.runners.domain.RemoteIdleTimeout
import run.ostrom.server.runners.domain.RemoteSessionRules
import kotlin.uuid.Uuid

/**
 * [RemoteIdleTimeout] from the organization's cloud settings (`remoteIdleTimeoutMinutes`).
 *
 * @param settings The cloud settings; null without the cloud module, where the default applies.
 */
class CloudSettingsRemoteIdleTimeout(private val settings: CloudSettingsSource?) : RemoteIdleTimeout {
    override suspend fun minutes(orgId: Uuid): Int =
        if (settings == null) {
            RemoteSessionRules.DEFAULT_IDLE_TIMEOUT_MINUTES
        } else {
            settings.current(orgId).remoteIdleTimeoutMinutes
        }
}
