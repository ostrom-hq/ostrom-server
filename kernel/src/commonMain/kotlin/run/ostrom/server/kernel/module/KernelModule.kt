// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.module

import run.ostrom.core.telemetry.Telemetry
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.ServerConfig
import run.ostrom.server.kernel.database.DatabaseNotifications
import run.ostrom.server.kernel.database.MigrationRunner
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.database.databaseNotifications
import run.ostrom.server.kernel.http.CallsInProgress
import run.ostrom.server.kernel.http.DatabaseHealthCheck
import run.ostrom.server.kernel.http.HealthCheck
import run.ostrom.server.kernel.http.ReadThrottle
import run.ostrom.server.kernel.http.Readiness
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.network.TrustedProxies
import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.kernel.observability.ProcessMemory
import run.ostrom.server.kernel.signing.InstanceSigner
import io.github.smyrgeorge.sqlx4k.Driver
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The kernel's Koin definitions, loaded before every functional module ([serverKoin]).
 *
 * @param logger The root logger.
 * @param driver The database.
 * @param signer The instance signing key.
 * @param config Where modules read their configuration keys ([ServerConfig.source]).
 * @param clock The clock use cases read; tests inject a fixed one.
 * @param telemetry Traces, metrics, logs and product events; disabled (nothing sent) unless configured.
 * @param metrics The server's instruments, created on [telemetry]'s meter.
 * @param notifications What the replicas tell each other through the database ([DatabaseNotifications]); a test
 *   whose [driver] wraps the pool gives the pool's.
 *
 * @return The module.
 */
@Suppress("LongParameterList") // The kernel's composition root: one parameter per shared dependency.
fun kernelModule(
    logger: Logger,
    driver: Driver,
    signer: InstanceSigner,
    config: ConfigSource,
    clock: Clock = Clock.System,
    telemetry: Telemetry = Telemetry.disabled(clock),
    metrics: OstromMetrics = OstromMetrics(telemetry.meter),
    notifications: DatabaseNotifications = databaseNotifications(driver),
): Module = module {
    single { logger }
    single { config }
    single { telemetry }
    single { metrics }
    single { driver }
    single { notifications }
    single { signer }
    single { clock }
    // Validated at start by ServerConfig.from: read again here so that tests set it through the ConfigSource.
    single { TrustedProxies.parse(config["OSTROM_TRUSTED_PROXIES"], config["OSTROM_CLIENT_ADDRESS_HEADER"]) }
    single { Readiness() }
    // AUDIT-SECU-LECTURES M1: one limiter for every costly read route, validated at start like the proxies above.
    single { ReadThrottle(get(), ServerConfig.readLimits(config), ReadThrottle.MAX_SUBJECTS) }
    // P1 memory: the calls this replica runs at once, under a ceiling taken from its container's limit.
    single { CallsInProgress(ServerConfig.callCeilings(config), ProcessMemory::limitBytes) }
    single<MigrationRunner> { MigrationRunnerImpl(get(), get()) }
    single<HealthCheck>(named("database")) { DatabaseHealthCheck(get()) }
}
