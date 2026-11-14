// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.launches

import kotlin.uuid.Uuid

/**
 * What a launch spent over its life, read from the usage ledger. The usage module binds it; the launches module reads
 * it to show a launch's limits (`GetLaunchLimits`). Unbound, a launch shows nothing spent.
 */
fun interface LaunchSpend {
    /**
     * What the launch's model calls cost so far.
     *
     * @param orgId The organization.
     * @param launchId The launch.
     *
     * @return USD millionths, 0 for a launch with no priced call.
     */
    suspend fun spentMicros(orgId: Uuid, launchId: String): Long
}
