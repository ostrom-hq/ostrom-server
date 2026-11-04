// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.repositories.GateHistory
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateCheckNeeds
import dev.ostrom.server.gates.domain.services.GateCheckProofs
import dev.ostrom.server.gates.domain.services.GateChanges
import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.gates.domain.services.GateDigester
import dev.ostrom.server.gates.domain.services.GateGitHubAccounts
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.gates.domain.usecases.AskGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.EnsureGateReceiptUseCase
import dev.ostrom.server.gates.domain.usecases.EnsureGateReceiptUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetApprovalQueueUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalQueueUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetApprovalViewUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalViewUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetGateUseCase
import dev.ostrom.server.gates.domain.usecases.GetGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCase
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.OpenGateUseCase
import dev.ostrom.server.gates.domain.usecases.OpenGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.UseGateUseCase
import dev.ostrom.server.gates.domain.usecases.UseGateUseCaseImpl
import dev.ostrom.server.gates.infrastructure.ChannelsGateNotifier
import dev.ostrom.server.gates.infrastructure.DefaultGatePolicies
import dev.ostrom.server.gates.infrastructure.GatesMigrations
import dev.ostrom.server.gates.infrastructure.InProcessGateChanges
import dev.ostrom.server.gates.infrastructure.MetricsGateChannel
import dev.ostrom.server.gates.infrastructure.NoCiChecks
import dev.ostrom.server.gates.infrastructure.NoGateActors
import dev.ostrom.server.gates.infrastructure.NoGateCheckNeeds
import dev.ostrom.server.gates.infrastructure.NoGateCheckProofs
import dev.ostrom.server.gates.infrastructure.NoGateGitHubAccounts
import dev.ostrom.server.gates.infrastructure.NoGateNodes
import dev.ostrom.server.gates.infrastructure.PendingGatesCount
import dev.ostrom.server.gates.infrastructure.PostgresGatesRepository
import dev.ostrom.server.gates.infrastructure.ReceiptsGateSigner
import dev.ostrom.server.gates.infrastructure.Sha256GateDigester
import dev.ostrom.server.gates.infrastructure.UnavailableGateSessions
import dev.ostrom.server.gates.infrastructure.UnknownPullRequestHeads
import dev.ostrom.server.gates.presentation.GatesRoutesDependencies
import dev.ostrom.server.gates.presentation.gatesRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.observability.CachedCount
import dev.ostrom.server.kernel.observability.OstromMetrics
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
        // Named: the channels of other modules (notifications, Slack, GitHub) bind theirs next to it.
        single<GateChannel>(named("metrics")) { MetricsGateChannel(get()) }

        single { GatesRoutesDependencies(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
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
