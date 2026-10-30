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
import dev.ostrom.server.gates.domain.usecases.GetGateUseCase
import dev.ostrom.server.gates.domain.usecases.GetGateUseCaseImpl
import dev.ostrom.server.gates.domain.usecases.ListPendingRequestsUseCase
import dev.ostrom.server.gates.domain.usecases.ListPendingRequestsUseCaseImpl
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
        single<GatePolicies> { DefaultGatePolicies() }
        single<GateSessions> { UnavailableGateSessions() }
        single<GateNodeResolver> { NoGateNodes() }
        single<PullRequestHeads> { UnknownPullRequestHeads() }
        single<GateReceiptSigner> { UnavailableGateReceiptSigner() }
        single<GateNotifier> { SilentGateNotifier() }
        single<GateActorResolver> { NoGateActors() }
        single<EnsureGateReceiptUseCase> { EnsureGateReceiptUseCaseImpl(get(), get(), get()) }
        single<OpenGateUseCase> { OpenGateUseCaseImpl(get(), get(), get(), get(), get(), get(), get()) }
        single<AskGateUseCase> { AskGateUseCaseImpl(get(), get(), get(), get()) }
        single<AnswerGateUseCase> { AnswerGateUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<UseGateUseCase> { UseGateUseCaseImpl(get(), get()) }
        single<GetGateUseCase> { GetGateUseCaseImpl(get(), get(), get()) }
        single<ListPendingRequestsUseCase> { ListPendingRequestsUseCaseImpl(get()) }
        single { GatesRoutesDependencies(get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = GatesMigrations

    override fun routes(route: Route, koin: Koin) {
        route.gatesRoutes(koin.get())
    }
}
