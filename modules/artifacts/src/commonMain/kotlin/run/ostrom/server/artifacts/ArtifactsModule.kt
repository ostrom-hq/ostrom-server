// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.ContentDigest
import run.ostrom.server.artifacts.domain.ManageArtifactUseCase
import run.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.PublishArtifactUseCase
import run.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCase
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import run.ostrom.server.artifacts.domain.ViewArtifactUseCase
import run.ostrom.server.artifacts.domain.ViewArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ViewTicketSeal
import run.ostrom.server.artifacts.domain.ViewerChecks
import run.ostrom.server.artifacts.infrastructure.ArtifactsConfig
import run.ostrom.server.artifacts.infrastructure.ArtifactsMigrations
import run.ostrom.server.artifacts.infrastructure.ArtifactsRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.KernelViewerChecks
import run.ostrom.server.artifacts.infrastructure.SealedViewTickets
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import run.ostrom.server.artifacts.presentation.ArtifactsRoutesDependencies
import run.ostrom.server.artifacts.presentation.artifactContentRoutes
import run.ostrom.server.artifacts.presentation.artifactsRoutes
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The artifacts module: pages and file sets that members and their agents publish for the organization to read,
 * with versions, sharing (`personal`, `team`, `link`), sealed member-bound tickets to read them on an isolated
 * content origin under TeamCode's content policy, and an MCP server for agents (`/api/v1/artifacts/mcp`). The files
 * are stored in PostgreSQL, each distinct content once per artifact.
 */
object ArtifactsModule : ServerModule {
    override val name: String = "artifacts"

    override val koinModule: Module = module {
        single<ArtifactLocations> { ArtifactsConfig.from(get<ConfigSource>()) }
        single<ArtifactsRepository> { ArtifactsRepositoryImpl(get()) }
        single<ContentDigest> { Sha256ContentDigest() }
        single<PublishArtifactUseCase> { PublishArtifactUseCaseImpl(get(), get(), get()) }
        single<ReadArtifactsUseCase> { ReadArtifactsUseCaseImpl(get()) }
        single<ManageArtifactUseCase> { ManageArtifactUseCaseImpl(get(), get()) }
        single<ViewTicketSeal> { SealedViewTickets(get()) }
        single<ViewerChecks> { KernelViewerChecks(get(), getOrNull()) }
        single<ViewArtifactUseCase> { ViewArtifactUseCaseImpl(get(), get(), get(), get(), get()) }
        single { ArtifactsRoutesDependencies(get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = ArtifactsMigrations

    override fun routes(route: Route, koin: Koin) {
        val dependencies = koin.get<ArtifactsRoutesDependencies>()
        route.artifactsRoutes(dependencies)
        route.artifactContentRoutes(dependencies.view, dependencies.locations)
    }

    /** Shared artifacts, their viewer and their MCP server. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.ARTIFACTS)
}
