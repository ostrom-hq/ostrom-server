// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.module

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.ServerConfig
import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.http.DatabaseHealthCheck
import dev.ostrom.server.kernel.http.HealthCheck
import dev.ostrom.server.kernel.logging.Logger
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
 *
 * @return The module.
 */
fun kernelModule(
    logger: Logger,
    driver: Driver,
    signer: InstanceSigner,
    config: ConfigSource,
    clock: Clock = Clock.System,
): Module = module {
    single { logger }
    single { config }
    single { driver }
    single { signer }
    single { clock }
    single<MigrationRunner> { MigrationRunnerImpl(get(), get()) }
    single<HealthCheck>(named("database")) { DatabaseHealthCheck(get()) }
}
