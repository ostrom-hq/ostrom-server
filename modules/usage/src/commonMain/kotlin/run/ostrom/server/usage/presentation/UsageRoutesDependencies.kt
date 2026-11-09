// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.presentation

import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.usage.domain.IngestUsageEventsUseCase
import run.ostrom.server.usage.domain.ListUsageEventsUseCase
import run.ostrom.server.usage.domain.UsageReportUseCase
import run.ostrom.server.usage.domain.UsageSummaryUseCase
import run.ostrom.server.usage.domain.WatchUsageUseCase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the usage routes need.
 *
 * @property callers Resolves the member of each call.
 * @property report Spending over a period.
 * @property ingest Records client events.
 * @property summary Totals.
 * @property listEvents Pages the ledger.
 * @property watch Follows it live.
 * @property heartbeat The interval of the live stream's heartbeat comments.
 * @property liveLifetime How long one live stream lasts before the client reconnects and is checked again.
 */
data class UsageRoutesDependencies(
    val callers: CallerResolver,
    val report: UsageReportUseCase,
    val ingest: IngestUsageEventsUseCase,
    val summary: UsageSummaryUseCase,
    val listEvents: ListUsageEventsUseCase,
    val watch: WatchUsageUseCase,
    val heartbeat: Duration = 15.seconds,
    val liveLifetime: Duration = 10.minutes,
)
