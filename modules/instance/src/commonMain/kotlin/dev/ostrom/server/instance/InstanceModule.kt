package dev.ostrom.server.instance

import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCaseImpl
import dev.ostrom.server.instance.domain.InstanceRepository
import dev.ostrom.server.instance.domain.ListSigningKeysUseCase
import dev.ostrom.server.instance.domain.ListSigningKeysUseCaseImpl
import dev.ostrom.server.instance.domain.RegisterCurrentSigningKeyUseCase
import dev.ostrom.server.instance.domain.RegisterCurrentSigningKeyUseCaseImpl
import dev.ostrom.server.instance.infrastructure.InstanceMigrations
import dev.ostrom.server.instance.infrastructure.InstanceRepositoryImpl
import dev.ostrom.server.instance.presentation.InstanceRoutesDependencies
import dev.ostrom.server.instance.presentation.instanceRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/** The instance module: the instance id and its signing keys, the reference example of docs/MODULES.md. */
object InstanceModule : ServerModule {
    override val name: String = "instance"

    override val koinModule: Module = module {
        single<InstanceRepository> { InstanceRepositoryImpl(get()) }
        single<GetOrCreateInstanceUseCase> { GetOrCreateInstanceUseCaseImpl(get(), get()) }
        single<RegisterCurrentSigningKeyUseCase> { RegisterCurrentSigningKeyUseCaseImpl(get(), get(), get()) }
        single<ListSigningKeysUseCase> { ListSigningKeysUseCaseImpl(get()) }
        single { InstanceRoutesDependencies(get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = InstanceMigrations

    override suspend fun initialize(koin: Koin) {
        koin.get<GetOrCreateInstanceUseCase>()()
        koin.get<RegisterCurrentSigningKeyUseCase>()()
    }

    override fun routes(route: Route, koin: Koin) {
        route.instanceRoutes(koin.get())
    }
}
