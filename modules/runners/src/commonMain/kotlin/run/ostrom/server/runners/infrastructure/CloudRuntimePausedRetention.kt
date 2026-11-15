// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.PausedRetention
import kotlinx.coroutines.CancellationException

/**
 * The paused retention the console's cloud runtime sets (`pausedRetentionDays`, 30 days by default, none to keep paused
 * sessions), for every runtime of a server with the cloud module: Docker and registered runners' sessions expire as
 * the cluster's. A server without the cloud module keeps its paused sessions ([run.ostrom.server.runners.domain
 * .KeepPausedSessions]).
 *
 * A runtime that cannot be read gives [PausedRetention.FALLBACK_DAYS] (TeamCode's default), logged with the error's
 * class only, so that a pause never fails on it.
 *
 * @param runtime The cloud runtime.
 * @param logger Where an unreadable runtime is reported.
 */
class CloudRuntimePausedRetention(private val runtime: CloudRuntimeSource, private val logger: Logger) :
    PausedRetention {
    override suspend fun days(): Int? = try {
        runtime.current().pausedRetentionDays
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.log(
            LogLevel.WARN,
            "paused retention unreadable, the default applies",
            mapOf("error_class" to error::class.simpleName),
        )
        PausedRetention.FALLBACK_DAYS
    }
}
