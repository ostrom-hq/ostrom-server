// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.receipts.domain.services.ReceiptBodySigner
import dev.ostrom.server.receipts.domain.services.ReceiptRules
import dev.ostrom.server.receipts.domain.services.TrustedReceiptKeys
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.ListReceiptsUseCase
import dev.ostrom.server.receipts.domain.usecases.ListReceiptsUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCaseImpl
import dev.ostrom.server.receipts.infrastructure.InstanceReceiptSigner
import dev.ostrom.server.receipts.infrastructure.InstanceTrustedReceiptKeys
import dev.ostrom.server.receipts.infrastructure.NoReceiptRules

import dev.ostrom.server.receipts.infrastructure.PostgresReceiptsRepository
import dev.ostrom.server.receipts.infrastructure.ReceiptsMigrations
import dev.ostrom.server.receipts.presentation.ReceiptsRoutesDependencies
import dev.ostrom.server.receipts.presentation.receiptsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The receipts module: signs the receipts of the other modules (gates, sessions, compiler) with the instance key,
 * byte-compatible with the prototype, stores them per organization and serves them with their verification. Needs the
 * instance module, which records the keys.
 */
object ReceiptsModule : ServerModule {
    override val name: String = "receipts"

    override val koinModule: Module = module {
        single<ReceiptsRepository> { PostgresReceiptsRepository(get()) }
        single<ReceiptBodySigner> { InstanceReceiptSigner(get()) }
        single<TrustedReceiptKeys> { InstanceTrustedReceiptKeys(get()) }
        single<SignReceiptUseCase> { SignReceiptUseCaseImpl(get(), get(), get()) }
        single<GetReceiptUseCase> { GetReceiptUseCaseImpl(get()) }
        single<ListReceiptsUseCase> { ListReceiptsUseCaseImpl(get()) }
        single<VerifyReceiptUseCase> { VerifyReceiptUseCaseImpl(get(), get()) }
        single<ListReceiptKeysUseCase> { ListReceiptKeysUseCaseImpl(get()) }
        single { ReceiptsRoutesDependencies(get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = ReceiptsMigrations

    /** Binds [NoReceiptRules] unless a loaded module binds the rules. */
    override suspend fun initialize(koin: Koin) {
        if (koin.getOrNull<ReceiptRules>() == null) {
            koin.loadModules(listOf(module { single<ReceiptRules> { NoReceiptRules() } }))
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.receiptsRoutes(koin.get())
    }
}
