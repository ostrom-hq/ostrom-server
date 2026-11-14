// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.automations.domain.AutomationLimits
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import kotlin.uuid.Uuid

/**
 * The cap of running automation runs per member, as the cloud runtime the administrators set
 * (`maxRunningAutomationsPerMember`, read at most every 30 seconds by each replica).
 *
 * @param runtime The cloud runtime in force; null when no cloud module is bound (the default cap then holds).
 */
internal class CloudRuntimeAutomationLimits(private val runtime: CloudRuntimeSource?) : AutomationLimits {
    override suspend fun maxRunningPerMember(orgId: Uuid): Int =
        (runtime?.current() ?: CloudRuntime()).maxRunningAutomationsPerMember
}
