// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudSettingsSource
import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.domain.BackgroundWorkLimit
import kotlin.uuid.Uuid

/**
 * [BackgroundWorkLimit] from the organization's cloud settings (`backgroundWorkMaxMinutes`, in the console).
 *
 * @param settings The cloud settings; null without the cloud module, where the default applies.
 */
class CloudSettingsBackgroundWorkLimit(private val settings: CloudSettingsSource?) : BackgroundWorkLimit {
    override suspend fun maxMinutes(orgId: Uuid): Int =
        settings?.current(orgId)?.backgroundWorkMaxMinutes ?: BackgroundWork.DEFAULT_MAX_MINUTES
}
