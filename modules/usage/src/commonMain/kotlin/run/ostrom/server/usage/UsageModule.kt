// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import run.ostrom.server.gateway.domain.ModelUsageSink
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import run.ostrom.server.sessions.domain.services.SessionCosts
import run.ostrom.server.usage.domain.IngestUsageEventsUseCase
import run.ostrom.server.usage.domain.IngestUsageEventsUseCaseImpl
import run.ostrom.server.usage.domain.ListUsageEventsUseCase
import run.ostrom.server.usage.domain.ListUsageEventsUseCaseImpl
import run.ostrom.server.usage.domain.PurgeUsageUseCase
import run.ostrom.server.usage.domain.PurgeUsageUseCaseImpl
import run.ostrom.server.usage.domain.SpendAdmission
import run.ostrom.server.usage.domain.SpendAdmissionImpl
import run.ostrom.server.usage.domain.SpendReader
import run.ostrom.server.usage.domain.SpendReaderImpl
import run.ostrom.server.usage.domain.UsageFeed
import run.ostrom.server.usage.domain.UsageRecorder
import run.ostrom.server.usage.domain.UsageRecorderImpl
import run.ostrom.server.usage.domain.UsageReportUseCase
import run.ostrom.server.usage.domain.UsageReportUseCaseImpl
import run.ostrom.server.usage.domain.UsageRepository
import run.ostrom.server.usage.domain.UsageSummaryUseCase
import run.ostrom.server.usage.domain.UsageSummaryUseCaseImpl
import run.ostrom.server.usage.domain.WatchUsageUseCase
import run.ostrom.server.usage.domain.WatchUsageUseCaseImpl
import run.ostrom.server.usage.infrastructure.LedgerModelUsageSink
import run.ostrom.server.usage.infrastructure.LedgerSessionCosts
import run.ostrom.server.usage.infrastructure.UsageFeedImpl
import run.ostrom.server.usage.infrastructure.UsageMigrations
import run.ostrom.server.usage.infrastructure.UsageRepositoryImpl
import run.ostrom.server.usage.presentation.UsageRoutesDependencies
import run.ostrom.server.usage.presentation.usageRoutes
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * The usage module's configuration, read once (`OSTROM_USAGE_*`).
 *
 * @property retention How long events are kept before the hourly purge deletes them.
 */
data class UsageConfig(val retention: Duration) {
    /** The defaults and the reading of the configuration. */
    companion object {
        /** Days kept by default: a full year plus a month, so that year-over-year totals stay possible. */
        const val DEFAULT_RETENTION_DAYS: Int = 400

        /** The longest retention accepted: ten years. */
        const val MAX_RETENTION_DAYS: Int = 3650

        /**
         * Reads it.
         *
         * @param source The configuration.
         *
         * @return The configuration.
         *
         * @throws InvalidConfigException When `OSTROM_USAGE_RETENTION_DAYS` is not 1 to 3650.
         */
        fun from(source: ConfigSource): UsageConfig {
            val raw = source["OSTROM_USAGE_RETENTION_DAYS"] ?: return UsageConfig(DEFAULT_RETENTION_DAYS.days)
            val days = raw.toIntOrNull()?.takeIf { it in 1..MAX_RETENTION_DAYS }
                ?: throw InvalidConfigException("OSTROM_USAGE_RETENTION_DAYS is 1 to 3650.")
            return UsageConfig(days.days)
        }
    }
}

/**
 * The usage module: the central ledger. It binds [UsageRecorder], [SpendReader] and [SpendAdmission], which the
 * gateway, the budgets and the sharing modules use.
 */
object UsageModule : ServerModule {
    override val name: String = "usage"

    override val koinModule: Module = module {
        single { UsageConfig.from(getOrNull<ConfigSource>() ?: EnvironmentConfigSource()) }
        single<UsageRepository> { UsageRepositoryImpl(get()) }
        single<UsageFeed> { UsageFeedImpl() }
        single<UsageRecorder> { UsageRecorderImpl(get(), get(), get()) }
        // The ports of the gateway and the sessions, which fall back to dropping everything without this module.
        single<ModelUsageSink> { LedgerModelUsageSink(get()) }
        single<SessionCosts> { LedgerSessionCosts(get()) }
        single<SpendReader> { SpendReaderImpl(get()) }
        single<SpendAdmission> { SpendAdmissionImpl(get(), get()) }
        single<IngestUsageEventsUseCase> { IngestUsageEventsUseCaseImpl(get(), get(), get()) }
        single<UsageSummaryUseCase> { UsageSummaryUseCaseImpl(get(), get()) }
        single<UsageReportUseCase> { UsageReportUseCaseImpl(get(), get()) }
        single<ListUsageEventsUseCase> { ListUsageEventsUseCaseImpl(get()) }
        single<WatchUsageUseCase> { WatchUsageUseCaseImpl(get()) }
        single<PurgeUsageUseCase> { PurgeUsageUseCaseImpl(get(), get(), get<UsageConfig>().retention) }
        single {
            UsageRoutesDependencies(callerResolver(), get(), get(), get(), get(), get())
        }
    }

    override val migrations: List<SchemaMigration> = UsageMigrations

    override suspend fun initialize(koin: Koin) {
        // A malformed retention stops the server at start, not at the first purge.
        koin.get<UsageConfig>()
    }

    override fun routes(route: Route, koin: Koin) {
        route.usageRoutes(koin.get())
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val purge = koin.get<PurgeUsageUseCase>()
        val logger = koin.get<Logger>()
        scope.launch { purgeHourly(purge, logger) }
    }

    /**
     * Purges now, then every hour, until cancelled. A failed run is logged and retried at the next hour.
     *
     * @param purge The purge.
     * @param logger Where failures go.
     * @param interval The interval between runs.
     */
    suspend fun purgeHourly(purge: PurgeUsageUseCase, logger: Logger, interval: Duration = 1.hours) {
        while (true) {
            try {
                purge()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The class only, never the message: PostgreSQL quotes row values in it.
                logger.warn("usage purge failed", mapOf("error" to error::class.simpleName))
            }
            delay(interval)
        }
    }
}

/**
 * The [CallerResolver] a test binds, or else the one on the kernel's `PrincipalOrgResolver` (which refuses everyone
 * until the identity and orgs modules bind theirs).
 */
internal fun Scope.callerResolver(): CallerResolver = getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get())
