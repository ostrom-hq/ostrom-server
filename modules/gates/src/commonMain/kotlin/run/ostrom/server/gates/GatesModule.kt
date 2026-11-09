// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.repositories.GateHistory
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.domain.services.CiChecks
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateCheckNeeds
import run.ostrom.server.gates.domain.services.GateCheckProofs
import run.ostrom.server.gates.domain.services.GateChanges
import run.ostrom.server.gates.domain.services.GateChannel
import run.ostrom.server.gates.domain.services.GateDigester
import run.ostrom.server.gates.domain.services.GateGitHubAccounts
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateNotifier
import run.ostrom.server.gates.domain.services.GatePolicies
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.domain.services.PullRequestHeads
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCaseImpl
import run.ostrom.server.gates.domain.usecases.AskGateUseCase
import run.ostrom.server.gates.domain.usecases.AskGateUseCaseImpl
import run.ostrom.server.gates.domain.usecases.EnsureGateReceiptUseCase
import run.ostrom.server.gates.domain.usecases.EnsureGateReceiptUseCaseImpl
import run.ostrom.server.gates.domain.usecases.GetApprovalQueueUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalQueueUseCaseImpl
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCaseImpl
import run.ostrom.server.gates.domain.usecases.GetApprovalViewUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalViewUseCaseImpl
import run.ostrom.server.gates.domain.usecases.GetGateUseCase
import run.ostrom.server.gates.domain.usecases.GetGateUseCaseImpl
import run.ostrom.server.gates.domain.usecases.ListApprovalsUseCase
import run.ostrom.server.gates.domain.usecases.ListApprovalsUseCaseImpl
import run.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import run.ostrom.server.gates.domain.usecases.ListSessionGatesUseCaseImpl
import run.ostrom.server.gates.domain.usecases.OpenGateUseCase
import run.ostrom.server.gates.domain.usecases.OpenGateUseCaseImpl
import run.ostrom.server.gates.domain.usecases.ReportLocalDenialUseCase
import run.ostrom.server.gates.domain.usecases.ReportLocalDenialUseCaseImpl
import run.ostrom.server.gates.domain.usecases.UseGateUseCase
import run.ostrom.server.gates.domain.usecases.UseGateUseCaseImpl
import run.ostrom.server.gates.infrastructure.ChannelsGateNotifier
import run.ostrom.server.gates.infrastructure.DefaultGatePolicies
import run.ostrom.server.gates.infrastructure.GatesMigrations
import run.ostrom.server.gates.infrastructure.InProcessGateChanges
import run.ostrom.server.gates.infrastructure.MetricsGateChannel
import run.ostrom.server.gates.infrastructure.NoCiChecks
import run.ostrom.server.gates.infrastructure.NoGateActors
import run.ostrom.server.gates.infrastructure.NoGateCheckNeeds
import run.ostrom.server.gates.infrastructure.NoGateCheckProofs
import run.ostrom.server.gates.infrastructure.NoGateGitHubAccounts
import run.ostrom.server.gates.infrastructure.NoGateNodes
import run.ostrom.server.gates.infrastructure.PendingGatesCount
import run.ostrom.server.gates.infrastructure.PostgresGatesRepository
import run.ostrom.server.gates.infrastructure.ReceiptsGateSigner
import run.ostrom.server.gates.infrastructure.Sha256GateDigester
import run.ostrom.server.gates.infrastructure.UnavailableGateSessions
import run.ostrom.server.gates.infrastructure.UnknownPullRequestHeads
import run.ostrom.server.gates.presentation.GatesRoutesDependencies
import run.ostrom.server.gates.presentation.gatesRoutes
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.observability.CachedCount
import run.ostrom.server.kernel.observability.OstromMetrics
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The gates module: the human yes a boundary action waits for. One request per approver, the first yes closes the
 * others, the yes is bound to what was shown and used once. Its ports (identity, sessions, receipts, workflows,
 * GitHub, notifications) have fail-closed defaults here; the modules that own them bind theirs after this one.
 */
object GatesModule : ServerModule {
    override val name: String = "gates"

    override val koinModule: Module = module {
        single { PostgresGatesRepository(get()) }
        single<GatesRepository> { get<PostgresGatesRepository>() }
        // The history of boundary actions, which the rules' replay reads.
        single<GateHistory> { get<PostgresGatesRepository>() }
        single<GateDigester> { Sha256GateDigester() }
        single<GateReceiptSigner> { ReceiptsGateSigner(get(), get(), get(), get(), get()) }
        single<EnsureGateReceiptUseCase> { EnsureGateReceiptUseCaseImpl(get(), get(), get()) }
        single<OpenGateUseCase> { OpenGateUseCaseImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
        single<AskGateUseCase> { AskGateUseCaseImpl(get(), get(), get(), get()) }
        single<GateChanges> { InProcessGateChanges() }
        single<AnswerGateUseCase> { AnswerGateUseCaseImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
        single<UseGateUseCase> { UseGateUseCaseImpl(get(), get(), get(), get()) }
        single<GetGateUseCase> { GetGateUseCaseImpl(get(), get(), get(), get()) }
        single<ListApprovalsUseCase> { ListApprovalsUseCaseImpl(get()) }
        single<GetApprovalUseCase> { GetApprovalUseCaseImpl(get()) }
        single<GetApprovalQueueUseCase> { GetApprovalQueueUseCaseImpl(get(), get(), get()) }
        single<GetApprovalViewUseCase> { GetApprovalViewUseCaseImpl(get(), get(), get()) }
        single<ListSessionGatesUseCase> { ListSessionGatesUseCaseImpl(get()) }
        single<ReportLocalDenialUseCase> { ReportLocalDenialUseCaseImpl(get(), get()) }
        // Named: the channels of other modules (notifications, Slack, GitHub) bind theirs next to it.
        single<GateChannel>(named("metrics")) { MetricsGateChannel(get()) }

        single {
            GatesRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
        }
    }

    override val migrations: List<SchemaMigration> = GatesMigrations

    /**
     * Binds the fail-closed default of each port no loaded module bound. Koin refuses two modules binding the same
     * type, so the defaults are not in [koinModule]: the owner of a port (identity, sessions, compiler,
     * integrations-github, notifications) binds it in its own module, and this fills the rest.
     */
    override suspend fun initialize(koin: Koin) {
        koin.loadModules(listOf(defaultPorts(koin)))
    }

    internal fun defaultPorts(koin: Koin): Module = module {
        if (koin.getOrNull<GatePolicies>() == null) single<GatePolicies> { DefaultGatePolicies() }
        if (koin.getOrNull<GateSessions>() == null) single<GateSessions> { UnavailableGateSessions() }
        if (koin.getOrNull<GateNodeResolver>() == null) single<GateNodeResolver> { NoGateNodes() }
        if (koin.getOrNull<PullRequestHeads>() == null) single<PullRequestHeads> { UnknownPullRequestHeads() }
        if (koin.getOrNull<CiChecks>() == null) single<CiChecks> { NoCiChecks() }
        if (koin.getOrNull<GateCheckNeeds>() == null) single<GateCheckNeeds> { NoGateCheckNeeds() }
        if (koin.getOrNull<GateCheckProofs>() == null) single<GateCheckProofs> { NoGateCheckProofs() }
        if (koin.getOrNull<GateNotifier>() == null) {
            single<GateNotifier> { ChannelsGateNotifier({ koin.getAll<GateChannel>() }, get()) }
        }
        if (koin.getOrNull<GateActorResolver>() == null) single<GateActorResolver> { NoGateActors() }
        if (koin.getOrNull<GateGitHubAccounts>() == null) single<GateGitHubAccounts> { NoGateGitHubAccounts() }
    }

    override fun routes(route: Route, koin: Koin) {
        route.gatesRoutes(koin.get(), koin.get())
    }

    /** Keeps the `ostrom.gates.pending` gauge's count, read every 30 s across organizations. */
    override fun start(scope: CoroutineScope, koin: Koin) {
        val pending = CachedCount(PendingGatesCount(koin.get())::read)
        koin.get<OstromMetrics>().observeGatesPending(pending::value)
        pending.refreshEvery(scope)
    }
}
