// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.PausedRetention

/**
 * The paused retention the console's cloud runtime sets (`pausedRetentionDays`, 30 days by default, none to keep paused
 * sessions), for every runtime: Docker and registered runners' sessions expire as the cluster's.
 *
 * @param runtime The cloud runtime; null on a server without the cloud module, which keeps the default.
 */
class CloudRuntimePausedRetention(private val runtime: CloudRuntimeSource?) : PausedRetention {
    override suspend fun days(): Int? = (runtime?.current() ?: CloudRuntime()).pausedRetentionDays
}
