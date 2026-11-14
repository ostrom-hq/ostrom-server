// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactLocations
import run.ostrom.server.artifacts.domain.ArtifactPublicationsRepository
import run.ostrom.server.artifacts.domain.ArtifactUsageReports
import run.ostrom.server.artifacts.domain.ArtifactLifecycleRepository
import run.ostrom.server.artifacts.domain.ArtifactMaintenance
import run.ostrom.server.artifacts.domain.ArtifactMaintenanceRepository
import run.ostrom.server.artifacts.domain.ArtifactUsageRepository
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.ContentDigest
import run.ostrom.server.artifacts.domain.ManageArtifactUseCase
import run.ostrom.server.artifacts.domain.ManageArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ReadArtifactUsageUseCase
import run.ostrom.server.artifacts.domain.PublicationGate
import run.ostrom.server.artifacts.domain.PublicationGateImpl
import run.ostrom.server.artifacts.domain.PublishArtifactUseCase
import run.ostrom.server.artifacts.domain.PublishArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCase
import run.ostrom.server.artifacts.domain.ReadArtifactsUseCaseImpl
import run.ostrom.server.artifacts.domain.ViewArtifactUseCase
import run.ostrom.server.artifacts.domain.ViewArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.ViewTicketSeal
import run.ostrom.server.artifacts.domain.ViewerChecks
import run.ostrom.server.artifacts.domain.storage.ArtifactAdministrationUseCase
import run.ostrom.server.artifacts.domain.storage.ArtifactAdministrationUseCaseImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactContents
import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.artifacts.domain.storage.BucketArtifactContents
import run.ostrom.server.artifacts.domain.storage.BucketUploadChunks
import run.ostrom.server.artifacts.domain.storage.UploadChunks
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadsRepository
import run.ostrom.server.artifacts.domain.uploads.CommitArtifactUploadUseCase
import run.ostrom.server.artifacts.domain.uploads.CommitArtifactUploadUseCaseImpl
import run.ostrom.server.artifacts.domain.uploads.UploadArtifactUseCase
import run.ostrom.server.artifacts.domain.uploads.UploadArtifactUseCaseImpl
import run.ostrom.server.artifacts.domain.uploads.UploadStores
import run.ostrom.server.artifacts.infrastructure.ArtifactPublicationsRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.ArtifactUploadsRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.ArtifactLifecycleRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.ArtifactMaintenanceRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.ArtifactMaintenanceSweeper
import run.ostrom.server.artifacts.infrastructure.ArtifactUsageRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.ArtifactsConfig
import run.ostrom.server.artifacts.infrastructure.ArtifactsMigrations
import run.ostrom.server.artifacts.infrastructure.ArtifactsRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.AuditedArtifactEvents
import run.ostrom.server.artifacts.infrastructure.KernelViewerChecks
import run.ostrom.server.artifacts.infrastructure.SealedViewTickets
import run.ostrom.server.artifacts.infrastructure.Sha256ContentDigest
import run.ostrom.server.artifacts.infrastructure.storage.ArtifactStorageRepositoryImpl
import run.ostrom.server.artifacts.infrastructure.storage.S3ObjectStore
import run.ostrom.server.artifacts.presentation.ArtifactsRoutesDependencies
import run.ostrom.server.artifacts.presentation.StorageRoutesDependencies
import run.ostrom.server.artifacts.presentation.artifactContentRoutes
import run.ostrom.server.artifacts.presentation.artifactStorageRoutes
import run.ostrom.server.artifacts.presentation.artifactUploadRoutes
import run.ostrom.server.artifacts.presentation.artifactsRoutes
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.network.outboundHttpClient
import run.ostrom.server.secrets.domain.SecretCipher
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * The artifacts module: pages and file sets that members and their agents publish for the organization to read,
 * with versions, sharing (`personal`, `team`, `link`), sealed member-bound tickets to read them on an isolated
 * content origin under TeamCode's content policy, and an MCP server for agents (`/api/v1/artifacts/mcp`). An owner or
 * an administrator configures the organization's S3-compatible bucket and the artifact settings, as in TeamCode.
 */
object ArtifactsModule : ServerModule {
    override val name: String = "artifacts"

    // Before [koinModule], whose declarations read it as the module is built.
    /** The client of the organizations' buckets. */
    private val BUCKET_CLIENT = named("artifactsBucketClient")

    override val koinModule: Module = module {
        single<ArtifactLocations> { ArtifactsConfig.from(get<ConfigSource>()) }
        single<ArtifactsRepository> { ArtifactsRepositoryImpl(get()) }
        single<ContentDigest> { Sha256ContentDigest() }
        single<ArtifactEvents> { AuditedArtifactEvents(get()) }
        single<ArtifactContents> { BucketArtifactContents(get(), get()) }
        single<ArtifactPublicationsRepository> { ArtifactPublicationsRepositoryImpl(get()) }
        single<ArtifactUsageRepository> { ArtifactUsageRepositoryImpl(get()) }
        single<ArtifactLifecycleRepository> { ArtifactLifecycleRepositoryImpl(get()) }
        single<PublicationGate> { PublicationGateImpl(get(), get(), get(), get()) }
        single<PublishArtifactUseCase> { PublishArtifactUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<ReadArtifactsUseCase> { ReadArtifactsUseCaseImpl(get(), get()) }
        single<ManageArtifactUseCase> { ManageArtifactUseCaseImpl(get(), get(), get(), get(), get()) }
        single<ViewTicketSeal> { SealedViewTickets(get()) }
        single<ViewerChecks> { KernelViewerChecks(get(), getOrNull()) }
        single<ViewArtifactUseCase> { ViewArtifactUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single { ArtifactsRoutesDependencies(get(), get(), get(), get(), get(), get(), get()) }
        single<ArtifactStorageRepository> { ArtifactStorageRepositoryImpl(get(), lazy { get<SecretCipher>() }) }
        // The bucket an administrator typed: every connection checked where it is made (no link-local, metadata or
        // unspecified address; a private one is allowed, for a bucket on the organization's own network, as in
        // TeamCode), no redirect followed, every call bounded.
        single(BUCKET_CLIENT) {
            outboundHttpClient(allowPrivate = true) {
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
        } onClose { it?.close() }
        single<ArtifactObjectStore> { S3ObjectStore(get(BUCKET_CLIENT)) }
        single<ArtifactAdministrationUseCase> {
            val digest = get<ContentDigest>()
            ArtifactAdministrationUseCaseImpl(get(), get(), { bytes -> digest.sha256(bytes) })
        }
        single<ArtifactUsageReports> { ArtifactUsageRepositoryImpl(get()) }
        single { ReadArtifactUsageUseCase(get()) }
        single { StorageRoutesDependencies(get(), get(), get()) }
        single<ArtifactUploadsRepository> { ArtifactUploadsRepositoryImpl(get()) }
        single<UploadChunks> { BucketUploadChunks(get(), get()) }
        single<CommitArtifactUploadUseCase> {
            CommitArtifactUploadUseCaseImpl(get(), get(), get(), get(), get(), get())
        }
        single<UploadArtifactUseCase> {
            UploadArtifactUseCaseImpl(get(), UploadStores(get(), get(), get()), get(), get(), get(), get())
        }
        single<ArtifactMaintenanceRepository> { ArtifactMaintenanceRepositoryImpl(get()) }
        single { ArtifactMaintenance(get(), get(), get(), get()) }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 10_000L
    private const val REQUEST_TIMEOUT_MILLIS = 60_000L

    override val migrations: List<SchemaMigration> = ArtifactsMigrations

    override fun routes(route: Route, koin: Koin) {
        val dependencies = koin.get<ArtifactsRoutesDependencies>()
        route.artifactsRoutes(dependencies)
        route.artifactContentRoutes(dependencies.view, dependencies.locations)
        route.artifactStorageRoutes { koin.get<StorageRoutesDependencies>() }
        route.artifactUploadRoutes(koin.get(), dependencies.orgResolver)
    }

    /** Shared artifacts, their viewer and their MCP server. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.ARTIFACTS)

    override fun start(scope: CoroutineScope, koin: Koin) {
        val maintenance = koin.get<ArtifactMaintenance>()
        val sweeper = ArtifactMaintenanceSweeper({ maintenance.run() }, koin.get())
        scope.launch { sweeper.run() }
    }
}
