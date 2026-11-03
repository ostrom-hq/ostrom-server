// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.module

import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.ServerConfig
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.http.DatabaseHealthCheck
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.http.ReadThrottle
import dev.ostrom.server.kernel.http.Readiness
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.network.TrustedProxies
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
 * @param config Where modules read their configuration keys ([ServerConfig.source]).
 * @param clock The clock use cases read; tests inject a fixed one.
 * @param telemetry Traces, metrics, logs and product events; disabled (nothing sent) unless configured.
 * @param metrics The server's instruments, created on [telemetry]'s meter.
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
): Module = module {
    single { logger }
    single { config }
    single { telemetry }
    single { metrics }
    single { driver }
    single { signer }
    single { clock }
    // Validated at start by ServerConfig.from: read again here so that tests set it through the ConfigSource.
    single { TrustedProxies.parse(config["OSTROM_TRUSTED_PROXIES"], config["OSTROM_CLIENT_ADDRESS_HEADER"]) }
    single { Readiness() }
    // AUDIT-SECU-LECTURES M1: one limiter for every costly read route, validated at start like the proxies above.
    single { ReadThrottle(get(), ServerConfig.readLimits(config), ReadThrottle.MAX_SUBJECTS) }
    single<MigrationRunner> { MigrationRunnerImpl(get(), get()) }
    single<HealthCheck>(named("database")) { DatabaseHealthCheck(get()) }
}
