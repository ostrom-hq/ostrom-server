// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.launches.domain.models.Launcher
import run.ostrom.server.launches.domain.services.LaunchModelRules

/** No module decides the models a launch may name: every well-formed model is allowed, the gateway checks each call. */
class NoLaunchModelRules : LaunchModelRules {
    override suspend fun refusal(launcher: Launcher, model: String): String? = null
}
