package dev.ostrom.server.kernel.module

import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.http.DatabaseHealthCheck
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.observability.OstromMetrics
import dev.ostrom.server.kernel.signing.InstanceSigner
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
 * @param clock The clock use cases read; tests inject a fixed one.
 * @param telemetry Traces, metrics, logs and product events; disabled (nothing sent) unless configured.
 * @param metrics The server's instruments, created on [telemetry]'s meter.
 *
 * @return The module.
 */
fun kernelModule(
    logger: Logger,
    driver: Driver,
    signer: InstanceSigner,
    clock: Clock = Clock.System,
    telemetry: Telemetry = Telemetry.disabled(clock),
    metrics: OstromMetrics = OstromMetrics(telemetry.meter),
): Module = module {
    single { logger }
    single { telemetry }
    single { metrics }
    single { driver }
    single { signer }
    single { clock }
    single<MigrationRunner> { MigrationRunnerImpl(get(), get()) }
    single<HealthCheck>(named("database")) { DatabaseHealthCheck(get()) }
}
