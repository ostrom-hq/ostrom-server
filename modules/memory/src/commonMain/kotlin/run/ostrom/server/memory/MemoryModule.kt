// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.memory.domain.MemoryAudit
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryService
import run.ostrom.server.memory.domain.MemoryServiceImpl
import run.ostrom.server.memory.infrastructure.MemoryMigrations
import run.ostrom.server.memory.infrastructure.MemoryOrgDeletionListener
import run.ostrom.server.memory.infrastructure.MemoryRepositoryImpl
import run.ostrom.server.memory.infrastructure.OrgMemoryAudit
import run.ostrom.server.memory.presentation.MemoryCallers
import run.ostrom.server.memory.presentation.MemoryRoutesDependencies
import run.ostrom.server.memory.presentation.memoryRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The memory module (TeamCode docs/memory.md): the organization's entries, which every member reads, and each member's
 * personal entries, which nobody else reads; an index and a search that never return content, the entry read on demand.
 * It resolves its callers with the server's `CallerResolver` (the launches module binds it for agent tokens).
 */
object MemoryModule : ServerModule {
    override val name: String = "memory"

    override val koinModule: Module = module {
        single<MemoryRepository> { MemoryRepositoryImpl(get(), get()) }
        single<MemoryAudit> { OrgMemoryAudit(get()) }
        single<MemoryService> { MemoryServiceImpl(get(), get(), get()) }
        single { MemoryCallers(get()) }
        single { MemoryRoutesDependencies(get(), get()) }
        single<OrgDeletionListener>(named("memory")) { MemoryOrgDeletionListener(get()) }
    }

    override val migrations: List<SchemaMigration> = MemoryMigrations

    override fun routes(route: Route, koin: Koin) {
        route.memoryRoutes(koin.get())
    }

    /** The memory, always served. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.MEMORY)
}
