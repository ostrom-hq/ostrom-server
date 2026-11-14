// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.compiler.domain.repositories.CompilerSettingsRepository
import run.ostrom.server.compiler.domain.usecases.CompilerSettingsUseCase
import run.ostrom.server.compiler.domain.usecases.CompilerSettingsUseCaseImpl
import run.ostrom.server.compiler.infrastructure.PostgresCompilerSettingsRepository
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.repositories.WorkflowSettingsRepository
import run.ostrom.server.compiler.infrastructure.PostgresWorkflowSettingsRepository
import run.ostrom.server.compiler.domain.usecases.WorkflowSettingsUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowSettingsUseCaseImpl
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import kotlin.uuid.Uuid
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
import run.ostrom.server.compiler.domain.usecases.ImportWorkflowsUseCase
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
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The compiler module: the organization's workflows, compiled with the decided rules into a signed session package
 * per repository (version, pin, revocation, audit), and the gates catalogue the hooks and the gates module share. It
 * binds the gates module's [GateNodeResolver]. Its sources (rules, repositories) have a default here until the module
 * that owns them binds its own; its settings are each organization's, saved by its owners and admins in the console
 * (`/api/v1/packages/settings`).
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
        single {
            WorkflowsUseCaseImpl(get(), get(), get(), automations = getOrNull(), removals = getOrNull())
        } binds arrayOf(WorkflowsUseCase::class, ImportWorkflowsUseCase::class)
        single<WorkflowSettingsRepository> { PostgresWorkflowSettingsRepository(get()) }
        single<WorkflowSettingsUseCase> {
            WorkflowSettingsUseCaseImpl(
                get(),
                getOrNull<MemberDirectory>() ?: NoMembers,
                getOrNull<MemberLogins>() ?: NoMembers,
                get<OrgAuditTrail>(),
                get(),
            )
        }
        single<CompilerSettingsRepository> { PostgresCompilerSettingsRepository(get()) }
        single<CompilerSettingsUseCase> { CompilerSettingsUseCaseImpl(get(), get<OrgAuditTrail>(), get()) }
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
            CompilerRoutesDependencies(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), publicUrl,
            )
        }
    }

    override val migrations: List<SchemaMigration> = CompilerMigrations

    /**
     * Binds the default sources when no module bound its own (Koin refuses two bindings of one type), and warns while
     * the former instance-wide compile policy is set: it still applies to every organization that saved none.
     */
    override suspend fun initialize(koin: Koin) {
        koin.loadModules(listOf(defaultPorts(koin)))
        val config = koin.get<ConfigSource>()
        val set = DefaultCompilerSources.VARIABLES.filter { config[it] != null }
        if (set.isNotEmpty()) {
            koin.get<Logger>().warn(
                "compile policy variables deprecated: the default of every organization that saved none, " +
                    "set it per organization in the console",
                mapOf("variables" to set.joinToString(",")),
            )
        }
    }

    internal fun defaultPorts(koin: Koin): Module = module {
        if (koin.getOrNull<CompilerSources>() == null) single<CompilerSources> {
            DefaultCompilerSources(get(), DefaultCompilerSources.legacy(get()))
        }
    }

    override fun routes(route: Route, koin: Koin) {
        route.compilerRoutes(koin.get())
    }

    /** Session packages and the workflows it compiles (`/api/v1/packages`, `/api/v1/workflows`). */
    override suspend fun features(koin: Koin): Set<String> = setOf(
        ServerFeatures.PACKAGES,
        ServerFeatures.WORKFLOWS,
        ServerFeatures.WORKFLOWS_SETTINGS,
    )
}

/** The members when no module knows them: nobody is a member, so no editor is named and none is let in. */
internal object NoMembers : MemberDirectory, MemberLogins {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = false

    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
}
