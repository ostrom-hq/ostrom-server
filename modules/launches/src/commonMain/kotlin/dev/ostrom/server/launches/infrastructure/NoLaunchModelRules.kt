// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.launches.domain.services.LaunchModelRules
import kotlin.uuid.Uuid

/** No module decides the models a launch may name: every well-formed model is allowed, the gateway checks each call. */
class NoLaunchModelRules : LaunchModelRules {
    override suspend fun refusal(orgId: Uuid, login: String, model: String): String? = null
}
