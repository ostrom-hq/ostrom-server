// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts

import dev.ostrom.server.artifacts.domain.ArtifactLocations
import dev.ostrom.server.artifacts.domain.ArtifactsRepository
import dev.ostrom.server.artifacts.domain.ContentDigest
import dev.ostrom.server.artifacts.domain.ManageArtifactUseCase
import dev.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import dev.ostrom.server.artifacts.domain.PublishArtifactUseCase
import dev.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import dev.ostrom.server.artifacts.domain.ReadArtifactsUseCase
import dev.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import dev.ostrom.server.artifacts.domain.ViewArtifactUseCase
import dev.ostrom.server.artifacts.domain.ViewArtifactUseCaseImpl
import dev.ostrom.server.artifacts.infrastructure.ArtifactsConfig
import dev.ostrom.server.artifacts.infrastructure.ArtifactsMigrations
import dev.ostrom.server.artifacts.infrastructure.ArtifactsRepositoryImpl
import dev.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import dev.ostrom.server.artifacts.presentation.ArtifactsRoutesDependencies
import dev.ostrom.server.artifacts.presentation.artifactContentRoutes
import dev.ostrom.server.artifacts.presentation.artifactsRoutes
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The artifacts module: pages and file sets that members and their agents publish for the organization to read,
 * with versions, sharing (`personal`, `team`, `link`), signed tickets to read them on an isolated content origin
 * under TeamCode's content policy, and an MCP server for agents (`/api/v1/artifacts/mcp`). The files are stored in
 * PostgreSQL, each distinct content once per artifact.
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
        single<ViewArtifactUseCase> { ViewArtifactUseCaseImpl(get(), get(), get(), get()) }
        single { ArtifactsRoutesDependencies(get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = ArtifactsMigrations

    override fun routes(route: Route, koin: Koin) {
        val dependencies = koin.get<ArtifactsRoutesDependencies>()
        route.artifactsRoutes(dependencies)
        route.artifactContentRoutes(dependencies.view, dependencies.locations)
    }
}
