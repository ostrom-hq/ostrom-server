// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.CloudSessionsSwitch
import run.ostrom.server.runners.infrastructure.RunnersConfig

/**
 * Cloud sessions are on when the runners module runs them on a runtime (`OSTROM_RUNNERS_DRIVER` other than `none`).
 *
 * @param config The runners module's configuration, null on a server built without it.
 */
class RunnersCloudSwitch(private val config: RunnersConfig?) : CloudSessionsSwitch {
    override suspend fun enabled(): Boolean =
        config != null && config.driver != RunnersConfig.DriverKind.NONE
}
