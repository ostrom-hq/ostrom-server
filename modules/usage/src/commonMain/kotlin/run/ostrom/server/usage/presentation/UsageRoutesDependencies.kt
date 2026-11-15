// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.presentation

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.usage.domain.GetUsageReviewUseCase
import run.ostrom.server.usage.domain.IngestUsageEventsUseCase
import run.ostrom.server.usage.domain.ListPendingUsageUseCase
import run.ostrom.server.usage.domain.ListUsageEventsUseCase
import run.ostrom.server.usage.domain.ListUsageReconciliationsUseCase
import run.ostrom.server.usage.domain.ReconcileUsageUseCase
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
 * @property pending The unknown usage awaiting review.
 * @property reconciliations The corrections made.
 * @property review One hold under review, or its correction.
 * @property reconcile Corrects an unknown charge.
 * @property audit Writes a correction to the organization's audit log.
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
    val pending: ListPendingUsageUseCase,
    val reconciliations: ListUsageReconciliationsUseCase,
    val review: GetUsageReviewUseCase,
    val reconcile: ReconcileUsageUseCase,
    val audit: OrgAuditTrail,
    val heartbeat: Duration = 15.seconds,
    val liveLifetime: Duration = 10.minutes,
)
