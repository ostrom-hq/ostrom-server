// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.cloud.domain.CloudAdminGate
import run.ostrom.server.cloud.domain.CloudClusterAccess
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.CloudClustersUseCase
import run.ostrom.server.cloud.domain.CloudClustersUseCaseImpl
import run.ostrom.server.cloud.domain.CloudProfilesRepository
import run.ostrom.server.cloud.domain.CloudProfilesUseCase
import run.ostrom.server.cloud.domain.CloudProfilesUseCaseImpl
import run.ostrom.server.cloud.domain.CloudRuntimeRepository
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.cloud.domain.CloudRuntimeUseCase
import run.ostrom.server.cloud.domain.CloudRuntimeUseCaseImpl
import run.ostrom.server.cloud.infrastructure.CloudClustersRepositoryImpl
import run.ostrom.server.cloud.infrastructure.CloudMigrations
import run.ostrom.server.cloud.infrastructure.CloudProfilesRepositoryImpl
import run.ostrom.server.cloud.infrastructure.CloudRuntimeRepositoryImpl
import run.ostrom.server.cloud.presentation.CloudAdminRoutesDependencies
import run.ostrom.server.cloud.presentation.cloudAdminRoutes
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * TeamCode's cloud administration (`/dashboard/cloud`, docs/MODULES.md "Settings in the console"): the instance's
 * cloud runtime and Kubernetes clusters, set by its administrators, and the organizations' versioned cloud profiles,
 * stored in PostgreSQL, cluster credentials sealed with the secrets key. At start it seeds the runtime once from the
 * former `OSTROM_RUNNERS_*` variables, saying they are deprecated. The runtime driver reads [CloudRuntimeSource] and
 * [CloudClusterAccess].
 */
object CloudModule : ServerModule {
    override val name: String = "cloud"

    override val koinModule: Module = module {
        single<CloudRuntimeRepository> { CloudRuntimeRepositoryImpl(get()) }
        single<CloudClustersRepository> { CloudClustersRepositoryImpl(get()) }
        single<CloudProfilesRepository> { CloudProfilesRepositoryImpl(get()) }
        single { CloudAdminGate(get()) }
        single { CloudRuntimeUseCaseImpl(get(), get(), get(), get(), get()) } binds
            arrayOf(CloudRuntimeUseCase::class, CloudRuntimeSource::class)
        single { CloudClustersUseCaseImpl(get(), get(), get(), get(), get()) } binds
            arrayOf(CloudClustersUseCase::class, CloudClusterAccess::class)
        single<CloudProfilesUseCase> { CloudProfilesUseCaseImpl(get(), get(), get()) }
        single {
            CloudAdminRoutesDependencies(
                get(),
                get(),
                get(),
                get(),
                getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get()),
                get(),
                get<ConfigSource>()["OSTROM_PUBLIC_URL"].orEmpty().trimEnd('/'),
            )
        }
    }

    override val migrations: List<SchemaMigration> = CloudMigrations

    override suspend fun initialize(koin: Koin) {
        koin.get<CloudRuntimeUseCase>().seed(koin.get<ConfigSource>()::get)
    }

    override fun routes(route: Route, koin: Koin) {
        route.cloudAdminRoutes(koin.get())
    }

    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.CLOUD_ADMIN)
}
