package dev.ostrom.server.kernel.module

import dev.ostrom.server.kernel.auth.RejectingTokenAuthenticator
import dev.ostrom.server.kernel.auth.TokenAuthenticator
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
 * The kernel's Koin definitions, loaded before every functional module so that a module can override one
 * (the identity module binds its own [TokenAuthenticator]).
 *
 * @param logger The root logger.
 * @param driver The database.
 * @param signer The instance signing key.
 * @param clock The clock use cases read; tests inject a fixed one.
 *
 * @return The module.
 */
fun kernelModule(logger: Logger, driver: Driver, signer: InstanceSigner, clock: Clock = Clock.System): Module = module {
    single { logger }
    single { driver }
    single { signer }
    single { clock }
    single<TokenAuthenticator> { RejectingTokenAuthenticator() }
    single<MigrationRunner> { MigrationRunnerImpl(get(), get()) }
    single<HealthCheck>(named("database")) { DatabaseHealthCheck(get()) }
}
