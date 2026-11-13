// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.hooks.domain.HookDeliveryUseCase
import run.ostrom.server.hooks.domain.HookDeliveryUseCaseImpl
import run.ostrom.server.hooks.domain.HooksRepository
import run.ostrom.server.hooks.domain.ManageHookCatalogUseCase
import run.ostrom.server.hooks.domain.ManageHookCatalogUseCaseImpl
import run.ostrom.server.hooks.domain.PublishHookVersionUseCase
import run.ostrom.server.hooks.domain.PublishHookVersionUseCaseImpl
import run.ostrom.server.hooks.infrastructure.HooksMigrations
import run.ostrom.server.hooks.infrastructure.HooksRepositoryImpl
import run.ostrom.server.hooks.presentation.HooksRoutesDependencies
import run.ostrom.server.hooks.presentation.hooksRoutes
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.skills.infrastructure.JdkSha256
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The team hooks module (TeamCode's `docs/hooks.md`): a versioned, immutable catalog of hooks (a definition and its
 * files, under one SHA-256 digest), published from the console or by a CI's service key with `hooks:publish`, and
 * delivered only once an owner or an admin pins a version from the console, to all members or to members set to yes.
 * A hook runs code on members' computers, so each member's CLI also asks them to approve each version.
 */
object HooksModule : ServerModule {
    override val name: String = "hooks"

    override val koinModule: Module = module {
        single<HooksRepository> { HooksRepositoryImpl(get()) }
        single<PublishHookVersionUseCase> { PublishHookVersionUseCaseImpl(get(), JdkSha256(), get(), get()) }
        single<ManageHookCatalogUseCase> {
            ManageHookCatalogUseCaseImpl(get(), get(), JdkSha256(), get(), get())
        }
        single<HookDeliveryUseCase> { HookDeliveryUseCaseImpl(get(), get(), JdkSha256()) }
        single { HooksRoutesDependencies(get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = HooksMigrations

    override fun routes(route: Route, koin: Koin) {
        route.hooksRoutes(koin.get())
    }

    /** The hook catalog and its delivery. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.HOOKS)
}
