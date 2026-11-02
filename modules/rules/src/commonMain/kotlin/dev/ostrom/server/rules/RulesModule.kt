// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.compiler.domain.services.CompilerSources
import dev.ostrom.server.compiler.infrastructure.DefaultCompilerSources
import dev.ostrom.server.gates.domain.services.GateCheckNeeds
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.kernel.auth.GovernanceChange
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.rules.domain.repositories.AnalysisRepository
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.AnalysisEngine
import dev.ostrom.server.rules.domain.services.RepositoryReader
import dev.ostrom.server.rules.domain.services.DecisionSigner
import dev.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCase
import dev.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.BulkUpdateRulesUseCase
import dev.ostrom.server.rules.domain.usecases.BulkUpdateRulesUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.CreateRuleUseCase
import dev.ostrom.server.rules.domain.usecases.CreateRuleUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.DeleteRuleUseCase
import dev.ostrom.server.rules.domain.usecases.DeleteRuleUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.GetAnalysisStateUseCase
import dev.ostrom.server.rules.domain.usecases.GetAnalysisStateUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.GetGatePolicyUseCase
import dev.ostrom.server.rules.domain.usecases.GetGatePolicyUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.GetReplayUseCase
import dev.ostrom.server.rules.domain.usecases.GetReplayUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.GetRuleUseCase
import dev.ostrom.server.rules.domain.usecases.GetRuleUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.ListDecisionsUseCase
import dev.ostrom.server.rules.domain.usecases.ListDecisionsUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.ListRulesUseCase
import dev.ostrom.server.rules.domain.usecases.ListRulesUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.SetGatePolicyUseCase
import dev.ostrom.server.rules.domain.usecases.SetGatePolicyUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.SignDecisionsUseCase
import dev.ostrom.server.rules.domain.usecases.SignDecisionsUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.StartAnalysisUseCase
import dev.ostrom.server.rules.domain.usecases.StartAnalysisUseCaseImpl
import dev.ostrom.server.rules.domain.usecases.UpdateRuleUseCase
import dev.ostrom.server.rules.domain.usecases.UpdateRuleUseCaseImpl
import dev.ostrom.server.rules.infrastructure.NoRepositoryReader
import dev.ostrom.server.rules.infrastructure.PostgresAnalysisRepository
import dev.ostrom.server.rules.infrastructure.PostgresRulesRepository
import dev.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import dev.ostrom.server.rules.infrastructure.ReceiptsDecisionSigner
import dev.ostrom.server.rules.infrastructure.RulesCompilerSources
import dev.ostrom.server.rules.infrastructure.RulesGateCheckNeeds
import dev.ostrom.server.rules.infrastructure.RulesGatePolicies
import dev.ostrom.server.rules.infrastructure.RulesMigrations
import dev.ostrom.server.rules.presentation.AnalysisRoutesDependencies
import dev.ostrom.server.rules.presentation.RulesGovernanceChange
import dev.ostrom.server.rules.presentation.RulesGovernanceDependencies
import dev.ostrom.server.rules.presentation.RulesRoutesDependencies
import dev.ostrom.server.rules.presentation.analysisRoutes
import dev.ostrom.server.rules.presentation.rulesRoutes
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.OrgPrincipalCallerResolver
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

/**
 * The rules module: the team's rules and their signed decisions, the decisions log, and the self-approval policy. It
 * owns the gates module's [GatePolicies] port: what the console sets on `/api/v1/gates/policy` is what every gate of
 * the organization applies. It also binds the compiler's [CompilerSources]: the rules harness carries the rules, and
 * a rule's gate enters the gates catalogue that the hooks and the gates module both read. The analysis of the
 * organization and the replay of its pull requests run on an [AnalysisEngine]: by default the deterministic
 * [RepositoryAnalysisEngine], which reads the repositories through a [RepositoryReader] and proposes their rules.
 */
object RulesModule : ServerModule {
    override val name: String = "rules"

    override val koinModule: Module = module {
        single<RulesRepository> { PostgresRulesRepository(get()) }
        single<DecisionSigner> { ReceiptsDecisionSigner(get()) }
        single<SignDecisionsUseCase> { SignDecisionsUseCaseImpl(get(), get(), get()) }
        single<ListRulesUseCase> { ListRulesUseCaseImpl(get()) }
        single<GetRuleUseCase> { GetRuleUseCaseImpl(get()) }
        single<CreateRuleUseCase> { CreateRuleUseCaseImpl(get(), get(), get(), get()) }
        single<UpdateRuleUseCase> { UpdateRuleUseCaseImpl(get(), get(), get(), get()) }
        single<BulkUpdateRulesUseCase> { BulkUpdateRulesUseCaseImpl(get(), get(), get(), get()) }
        single<DeleteRuleUseCase> { DeleteRuleUseCaseImpl(get(), get(), get(), get()) }
        single<ListDecisionsUseCase> { ListDecisionsUseCaseImpl(get(), get()) }
        single<GetGatePolicyUseCase> { GetGatePolicyUseCaseImpl(get()) }
        single<SetGatePolicyUseCase> { SetGatePolicyUseCaseImpl(get(), get(), get()) }
        single<GatePolicies> { RulesGatePolicies(get()) }
        single<GateCheckNeeds> { RulesGateCheckNeeds(get()) }
        single<GovernanceChange>(named("rules")) {
            RulesGovernanceChange(RulesGovernanceDependencies(get(), get(), get(), get(), get(), get(), get(), get()))
        }
        // The compiler binds its default sources only when none is bound: these replace them, repositories and
        // settings still read from the compiler's configuration.
        single<CompilerSources> { RulesCompilerSources(get(), DefaultCompilerSources(get())) }
        single<AnalysisRepository> { PostgresAnalysisRepository(get()) }
        single<StartAnalysisUseCase> { StartAnalysisUseCaseImpl(get(), get(), get(), get()) }
        single<GetAnalysisStateUseCase> { GetAnalysisStateUseCaseImpl(get(), get(), get()) }
        single<GetReplayUseCase> { GetReplayUseCaseImpl(get()) }
        single<AnalyzeOrganizationUseCase> { AnalyzeOrganizationUseCaseImpl(get(), get(), get(), get(), get()) }
        single { RepositoryAnalysisEngine(get(), get(), get()) }
        single { AnalysisRoutesDependencies(callerResolver(), get(), get(), get(), get()) }
        single {
            RulesRoutesDependencies(
                callerResolver(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }

    override val migrations: List<SchemaMigration> = RulesMigrations

    /**
     * Binds the deterministic engine when no module bound another, and the reader that sees no organization connected
     * when no GitHub module bound one (Koin refuses two bindings).
     */
    override suspend fun initialize(koin: Koin) {
        koin.loadModules(listOf(defaultPorts(koin)))
    }

    internal fun defaultPorts(koin: Koin): Module = module {
        if (koin.getOrNull<RepositoryReader>() == null) single<RepositoryReader> { NoRepositoryReader() }
        if (koin.getOrNull<AnalysisEngine>() == null) single<AnalysisEngine> { get<RepositoryAnalysisEngine>() }
    }

    /** Starts the engine's workers: each analyses the queued runs one after the other until the server stops. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val engine = koin.get<RepositoryAnalysisEngine>()
        repeat(RepositoryAnalysisEngine.WORKERS) { scope.launch { engine.work() } }
    }

    override fun routes(route: Route, koin: Koin) {
        route.rulesRoutes(koin.get())
        route.analysisRoutes(koin.get())
    }
}

/**
 * The [CallerResolver] a test binds, or else the one on the kernel's `PrincipalOrgResolver` (which refuses everyone
 * until the identity and orgs modules bind theirs).
 */
internal fun Scope.callerResolver(): CallerResolver = getOrNull<CallerResolver>() ?: OrgPrincipalCallerResolver(get())
