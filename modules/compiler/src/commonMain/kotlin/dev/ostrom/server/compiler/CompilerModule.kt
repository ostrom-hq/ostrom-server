// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.services.CompilerSources
import dev.ostrom.server.compiler.domain.services.HarnessReceiptSigner
import dev.ostrom.server.compiler.domain.usecases.CompileSourcesUseCase
import dev.ostrom.server.compiler.domain.usecases.CompileSourcesUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.PreviewPackageUseCase
import dev.ostrom.server.compiler.domain.usecases.PreviewPackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.PublishPackageUseCase
import dev.ostrom.server.compiler.domain.usecases.PublishPackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.RevokePackageUseCase
import dev.ostrom.server.compiler.domain.usecases.RevokePackageUseCaseImpl
import dev.ostrom.server.compiler.domain.usecases.WorkflowDecisionSigner
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCaseImpl
import dev.ostrom.server.compiler.infrastructure.CatalogGateNodes
import dev.ostrom.server.compiler.infrastructure.CompilerMigrations
import dev.ostrom.server.compiler.infrastructure.DefaultCompilerSources
import dev.ostrom.server.compiler.infrastructure.PostgresCompilerRepository
import dev.ostrom.server.compiler.infrastructure.ReceiptsCompilerSigner
import dev.ostrom.server.compiler.presentation.CompilerRoutesDependencies
import dev.ostrom.server.compiler.presentation.compilerRoutes
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.EnvironmentConfigSource
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The compiler module: the organization's workflows, compiled with the decided rules into a signed session package
 * per repository (version, pin, revocation, audit), and the gates catalogue the hooks and the gates module share. It
 * binds the gates module's [GateNodeResolver]. Its sources (rules, repositories, settings) have a default here, read
 * from the environment, until the module that owns them binds its own.
 */
object CompilerModule : ServerModule {
    override val name: String = "compiler"

    override val koinModule: Module = module {
        single<CompilerRepository> { PostgresCompilerRepository(get()) }
        single { ReceiptsCompilerSigner(get()) }
        single<HarnessReceiptSigner> { get<ReceiptsCompilerSigner>() }
        single<WorkflowDecisionSigner> { get<ReceiptsCompilerSigner>() }
        single<CompileSourcesUseCase> { CompileSourcesUseCaseImpl(get(), get(), get(), get()) }
        single<PublishPackageUseCase> { PublishPackageUseCaseImpl(get(), get(), get(), get(), get()) }
        single<PreviewPackageUseCase> { PreviewPackageUseCaseImpl(get()) }
        single<RevokePackageUseCase> { RevokePackageUseCaseImpl(get(), get()) }
        single<GatesCatalogUseCase> { GatesCatalogUseCaseImpl(get(), get()) }
        single<WorkflowsUseCase> { WorkflowsUseCaseImpl(get(), get(), get()) }
        // Created when the gates module looks for it, possibly before this module's initialize has bound the
        // sources: the catalogue is only fetched at the first gate.
        single<GateNodeResolver> {
            val koin = getKoin()
            CatalogGateNodes { koin.get() }
        }
        single {
            val publicUrl = get<CompilerConfig>().config["OSTROM_PUBLIC_URL"]
            CompilerRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), publicUrl)
        }
    }

    override val migrations: List<SchemaMigration> = CompilerMigrations

    /** Binds the default sources when no module bound its own (Koin refuses two bindings of one type). */
    override suspend fun initialize(koin: Koin) {
        koin.loadModules(listOf(defaultPorts(koin)))
    }

    internal fun defaultPorts(koin: Koin): Module = module {
        if (koin.getOrNull<CompilerConfig>() == null) single { CompilerConfig(EnvironmentConfigSource()) }
        if (koin.getOrNull<CompilerSources>() == null) {
            single<CompilerSources> { DefaultCompilerSources(get<CompilerConfig>().config) }
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.compilerRoutes(koin.get())
    }
}

/**
 * Where the compiler reads its environment settings; a test binds its own.
 *
 * @property config The source.
 */
class CompilerConfig(val config: ConfigSource)
