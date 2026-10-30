// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateDigester
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
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import dev.ostrom.server.gates.domain.usecases.GetApprovalUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.GetGateUseCase
import dev.ostrom.server.gates.domain.usecases.GetGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCase
import dev.ostrom.server.gates.domain.usecases.ListApprovalsUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.OpenGateUseCase
import dev.ostrom.server.gates.domain.usecases.OpenGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.UseGateUseCase
import dev.ostrom.server.gates.domain.usecases.UseGateUseCaseImpl
import dev.ostrom.server.gates.infrastructure.DefaultGatePolicies
import dev.ostrom.server.gates.infrastructure.GatesMigrations
import dev.ostrom.server.gates.infrastructure.NoGateActors
import dev.ostrom.server.gates.infrastructure.NoGateNodes
import dev.ostrom.server.gates.infrastructure.PostgresGatesRepository
import dev.ostrom.server.gates.infrastructure.Sha256GateDigester
import dev.ostrom.server.gates.infrastructure.SilentGateNotifier
import dev.ostrom.server.gates.infrastructure.UnavailableGateReceiptSigner
import dev.ostrom.server.gates.infrastructure.UnavailableGateSessions
import dev.ostrom.server.gates.infrastructure.UnknownPullRequestHeads
import dev.ostrom.server.gates.presentation.GateActorResolver
import dev.ostrom.server.gates.presentation.GatesRoutesDependencies
import dev.ostrom.server.gates.presentation.gatesRoutes
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The gates module: the human yes a boundary action waits for. One request per approver, the first yes closes the
 * others, the yes is bound to what was shown and used once. Its ports (identity, sessions, receipts, workflows,
 * GitHub, notifications) have fail-closed defaults here; the modules that own them bind theirs after this one.
 */
object GatesModule : ServerModule {
    override val name: String = "gates"

    override val koinModule: Module = module {
        single<GatesRepository> { PostgresGatesRepository(get()) }
        single<GateDigester> { Sha256GateDigester() }
        single<EnsureGateReceiptUseCase> { EnsureGateReceiptUseCaseImpl(get(), get(), get()) }
        single<OpenGateUseCase> { OpenGateUseCaseImpl(get(), get(), get(), get(), get(), get(), get()) }
        single<AskGateUseCase> { AskGateUseCaseImpl(get(), get(), get(), get()) }
        single<AnswerGateUseCase> { AnswerGateUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<UseGateUseCase> { UseGateUseCaseImpl(get(), get()) }
        single<GetGateUseCase> { GetGateUseCaseImpl(get(), get(), get()) }
        single<ListApprovalsUseCase> { ListApprovalsUseCaseImpl(get()) }
        single<GetApprovalUseCase> { GetApprovalUseCaseImpl(get()) }
        single { GatesRoutesDependencies(get(), get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = GatesMigrations

    /**
     * Binds the fail-closed default of each port no loaded module bound. Koin refuses two modules binding the same
     * type, so the defaults are not in [koinModule]: the owner of a port (identity, sessions, receipts, compiler,
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
        if (koin.getOrNull<GateReceiptSigner>() == null) single<GateReceiptSigner> { UnavailableGateReceiptSigner() }
        if (koin.getOrNull<GateNotifier>() == null) single<GateNotifier> { SilentGateNotifier() }
        if (koin.getOrNull<GateActorResolver>() == null) single<GateActorResolver> { NoGateActors() }
    }

    override fun routes(route: Route, koin: Koin) {
        route.gatesRoutes(koin.get())
    }
}
