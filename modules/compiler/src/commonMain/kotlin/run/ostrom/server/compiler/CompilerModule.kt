// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.services.CompiledPackages
import run.ostrom.server.compiler.domain.services.CompilerSources
import run.ostrom.server.compiler.domain.services.HarnessReceiptSigner
import run.ostrom.server.compiler.domain.usecases.ApproversGraphUseCase
import run.ostrom.server.compiler.domain.usecases.ApproversGraphUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.CompileSourcesUseCase
import run.ostrom.server.compiler.domain.usecases.CompileSourcesUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.GatePolicyUseCase
import run.ostrom.server.compiler.domain.usecases.GatePolicyUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import run.ostrom.server.compiler.domain.usecases.GatesCatalogUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.PreviewPackageUseCase
import run.ostrom.server.compiler.domain.usecases.PreviewPackageUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.PublishPackageUseCase
import run.ostrom.server.compiler.domain.usecases.PublishPackageUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.RevokePackageUseCase
import run.ostrom.server.compiler.domain.usecases.RevokePackageUseCaseImpl
import run.ostrom.server.compiler.domain.usecases.WorkflowDecisionSigner
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCaseImpl
import run.ostrom.server.compiler.infrastructure.CatalogGateNodes
import run.ostrom.server.compiler.infrastructure.CompilerMigrations
import run.ostrom.server.compiler.infrastructure.CompilerWorkflowDirectory
import run.ostrom.server.compiler.infrastructure.DefaultCompilerSources
import run.ostrom.server.compiler.infrastructure.PostgresCompilerRepository
import run.ostrom.server.compiler.infrastructure.ReceiptsCompilerSigner
import run.ostrom.server.compiler.presentation.CompilerGovernanceChange
import run.ostrom.server.compiler.presentation.CompilerRoutesDependencies
import run.ostrom.server.compiler.presentation.compilerRoutes
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.workflows.WorkflowDirectory
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The compiler module: the organization's workflows, compiled with the decided rules into a signed session package
 * per repository (version, pin, revocation, audit), and the gates catalogue the hooks and the gates module share. It
 * binds the gates module's [GateNodeResolver]. Its sources (rules, repositories, settings) have a default here, read
 * from the server's configuration, until the module that owns them binds its own.
 */
object CompilerModule : ServerModule {
    override val name: String = "compiler"

    override val koinModule: Module = module {
        single<CompilerRepository> { PostgresCompilerRepository(get()) }
        single { ReceiptsCompilerSigner(get()) }
        single<HarnessReceiptSigner> { get<ReceiptsCompilerSigner>() }
        single<WorkflowDecisionSigner> { get<ReceiptsCompilerSigner>() }
        single { CompiledPackages() }
        single<CompileSourcesUseCase> { CompileSourcesUseCaseImpl(get(), get(), get(), get(), get()) }
        single<PublishPackageUseCase> { PublishPackageUseCaseImpl(get(), get(), get(), get(), get()) }
        single<PreviewPackageUseCase> { PreviewPackageUseCaseImpl(get()) }
        single<RevokePackageUseCase> { RevokePackageUseCaseImpl(get(), get()) }
        single<GatesCatalogUseCase> { GatesCatalogUseCaseImpl(get(), get()) }
        single<GatePolicyUseCase> { GatePolicyUseCaseImpl(get(), get(), get()) }
        single<ApproversGraphUseCase> { ApproversGraphUseCaseImpl(get()) }
        single<WorkflowsUseCase> { WorkflowsUseCaseImpl(get(), get(), get(), removals = getOrNull()) }
        single<WorkflowDirectory> { CompilerWorkflowDirectory(get<CompilerRepository>()) }
        single<GovernanceChange>(named("compiler")) { CompilerGovernanceChange(get(), get()) }
        // Created when the gates module looks for it, possibly before this module's initialize has bound the
        // sources: the catalogue is only fetched at the first gate.
        single<GateNodeResolver> {
            val koin = getKoin()
            CatalogGateNodes({ koin.get() }) { koin.get() }
        }
        single {
            val publicUrl = get<ConfigSource>()["OSTROM_PUBLIC_URL"]
            CompilerRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), publicUrl)
        }
    }

    override val migrations: List<SchemaMigration> = CompilerMigrations

    /** Binds the default sources when no module bound its own (Koin refuses two bindings of one type). */
    override suspend fun initialize(koin: Koin) {
        koin.loadModules(listOf(defaultPorts(koin)))
    }

    internal fun defaultPorts(koin: Koin): Module = module {
        if (koin.getOrNull<CompilerSources>() == null) single<CompilerSources> { DefaultCompilerSources(get()) }
    }

    override fun routes(route: Route, koin: Koin) {
        route.compilerRoutes(koin.get())
    }
}
