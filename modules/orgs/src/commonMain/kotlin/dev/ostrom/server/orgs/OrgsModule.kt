// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.kernel.auth.MemberDirectory
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.module.TestAccountSeeder
import dev.ostrom.server.orgs.domain.AcceptInvitationUseCase
import dev.ostrom.server.orgs.domain.AcceptInvitationUseCaseImpl
import dev.ostrom.server.orgs.domain.ChangeRoleUseCase
import dev.ostrom.server.orgs.domain.ChangeRoleUseCaseImpl
import dev.ostrom.server.orgs.domain.CountMembersUseCase
import dev.ostrom.server.orgs.domain.CountMembersUseCaseImpl
import dev.ostrom.server.orgs.domain.CreateInviteLinkUseCase
import dev.ostrom.server.orgs.domain.CreateInviteLinkUseCaseImpl
import dev.ostrom.server.orgs.domain.CreateOrgUseCase
import dev.ostrom.server.orgs.domain.CreateOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.DeleteOrgUseCase
import dev.ostrom.server.orgs.domain.DeleteOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.FindOrgUseCase
import dev.ostrom.server.orgs.domain.FindOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.FreePlanSeats
import dev.ostrom.server.orgs.domain.GetMemberUseCase
import dev.ostrom.server.orgs.domain.GetMemberUseCaseImpl
import dev.ostrom.server.orgs.domain.GetOrgUseCase
import dev.ostrom.server.orgs.domain.GetOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.InvitationMailer
import dev.ostrom.server.orgs.domain.InvitationServices
import dev.ostrom.server.orgs.domain.InvitationTokens
import dev.ostrom.server.orgs.domain.InvitationsRepository
import dev.ostrom.server.orgs.domain.InviteByEmailUseCase
import dev.ostrom.server.orgs.domain.InviteByEmailUseCaseImpl
import dev.ostrom.server.orgs.domain.LeaveOrgUseCase
import dev.ostrom.server.orgs.domain.LeaveOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.ListInvitationsUseCase
import dev.ostrom.server.orgs.domain.ListInvitationsUseCaseImpl
import dev.ostrom.server.orgs.domain.ListMembersUseCase
import dev.ostrom.server.orgs.domain.ListMembersUseCaseImpl
import dev.ostrom.server.orgs.domain.ListMyOrgsUseCase
import dev.ostrom.server.orgs.domain.ListMyOrgsUseCaseImpl
import dev.ostrom.server.orgs.domain.MembersRepository
import dev.ostrom.server.orgs.domain.OrgAudit
import dev.ostrom.server.orgs.domain.OrgRateLimiter
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.orgs.domain.People
import dev.ostrom.server.orgs.domain.PreviewInvitationUseCase
import dev.ostrom.server.orgs.domain.PreviewInvitationUseCaseImpl
import dev.ostrom.server.orgs.domain.RemoveMemberUseCase
import dev.ostrom.server.orgs.domain.RemoveMemberUseCaseImpl
import dev.ostrom.server.orgs.domain.RenameOrgUseCase
import dev.ostrom.server.orgs.domain.RenameOrgUseCaseImpl
import dev.ostrom.server.orgs.domain.RevokeInvitationUseCase
import dev.ostrom.server.orgs.domain.RevokeInvitationUseCaseImpl
import dev.ostrom.server.orgs.domain.SeatPolicy
import dev.ostrom.server.orgs.domain.TransferOwnershipUseCase
import dev.ostrom.server.orgs.domain.TransferOwnershipUseCaseImpl
import dev.ostrom.server.orgs.infrastructure.IdentityInvitationTokens
import dev.ostrom.server.orgs.infrastructure.IdentityOrgAudit
import dev.ostrom.server.orgs.infrastructure.IdentityPeople
import dev.ostrom.server.orgs.infrastructure.IdentityRateLimiter
import dev.ostrom.server.orgs.infrastructure.InvitationsRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.MailInvitationMailer
import dev.ostrom.server.orgs.infrastructure.MemberDirectoryImpl
import dev.ostrom.server.orgs.infrastructure.MembersRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.MembershipOrgResolver
import dev.ostrom.server.orgs.infrastructure.OrgsMigrations
import dev.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import dev.ostrom.server.orgs.infrastructure.TestAccountSeederImpl
import dev.ostrom.server.orgs.presentation.InvitationCommands
import dev.ostrom.server.orgs.presentation.MemberCommands
import dev.ostrom.server.orgs.presentation.OrgCommands
import dev.ostrom.server.orgs.presentation.OrgsConfig
import dev.ostrom.server.orgs.presentation.OrgsRoutesDependencies
import dev.ostrom.server.orgs.presentation.myOrganizationsRoutes
import dev.ostrom.server.orgs.presentation.orgsRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The orgs module: organizations, members and their roles, invitations, the free-plan guard, and
 * the server's [PrincipalOrgResolver].
 *
 * Other modules use it through the interfaces it binds: [PrincipalOrgResolver] (through the
 * kernel's `requireOrgPrincipal`), [MemberDirectory] (who a wait may be asked of), [CountMembersUseCase] for
 * per-seat billing. Until the billing
 * module binds its own [SeatPolicy] here, every organization has the free plan's limit
 * ([FreePlanSeats]).
 */
object OrgsModule : ServerModule {
    override val name: String = "orgs"

    override val koinModule: Module = module {
        single { OrgsConfig.from(get()) }
        single<OrgsRepository> { OrgsRepositoryImpl(get()) }
        single<MembersRepository> { MembersRepositoryImpl(get()) }
        single<InvitationsRepository> { InvitationsRepositoryImpl(get()) }
        single<People> { IdentityPeople(get()) }
        single<OrgAudit> { IdentityOrgAudit(get()) }
        single<InvitationTokens> { IdentityInvitationTokens(get(), get()) }
        single<OrgRateLimiter> { IdentityRateLimiter(get()) }
        single<InvitationMailer> { MailInvitationMailer(get()) }
        single<SeatPolicy> { FreePlanSeats() }
        single<PrincipalOrgResolver> { MembershipOrgResolver(get(), get()) }
        single<MemberDirectory> { MemberDirectoryImpl(get()) }
        // Identity has no teams: the QA account (a person, their team, a token) is assembled here.
        single<TestAccountSeeder> { TestAccountSeederImpl(get(), get(), get(), get()) }
        single {
            InvitationServices(get(), get(), get(), get(), get(), get<OrgsConfig>()::invitationUrl)
        }

        single<FindOrgUseCase> { FindOrgUseCaseImpl(get()) }
        single<ListMyOrgsUseCase> { ListMyOrgsUseCaseImpl(get()) }
        single<CreateOrgUseCase> { CreateOrgUseCaseImpl(get(), get(), get(), get(), get(), get()) }
        single<GetOrgUseCase> { GetOrgUseCaseImpl(get(), get()) }
        single<RenameOrgUseCase> { RenameOrgUseCaseImpl(get(), get(), get()) }
        single<DeleteOrgUseCase> { DeleteOrgUseCaseImpl(get(), get(), get(), get()) }
        single<TransferOwnershipUseCase> {
            TransferOwnershipUseCaseImpl(get(), get(), get(), get())
        }
        single<ListMembersUseCase> { ListMembersUseCaseImpl(get(), get(), get()) }
        single<GetMemberUseCase> { GetMemberUseCaseImpl(get(), get(), get()) }
        single<ChangeRoleUseCase> { ChangeRoleUseCaseImpl(get(), get(), get(), get()) }
        single<RemoveMemberUseCase> { RemoveMemberUseCaseImpl(get(), get(), get(), get()) }
        single<LeaveOrgUseCase> { LeaveOrgUseCaseImpl(get(), get(), get()) }
        single<CountMembersUseCase> { CountMembersUseCaseImpl(get()) }
        single<InviteByEmailUseCase> {
            InviteByEmailUseCaseImpl(get(), get(), get(), get(), get(), get())
        }
        single<CreateInviteLinkUseCase> {
            CreateInviteLinkUseCaseImpl(get(), get(), get(), get(), get())
        }
        single<ListInvitationsUseCase> { ListInvitationsUseCaseImpl(get(), get(), get(), get()) }
        single<RevokeInvitationUseCase> {
            RevokeInvitationUseCaseImpl(get(), get(), get(), get(), get())
        }
        single<PreviewInvitationUseCase> {
            PreviewInvitationUseCaseImpl(get(), get(), get(), get())
        }
        single<AcceptInvitationUseCase> {
            AcceptInvitationUseCaseImpl(get(), get(), get(), get(), get(), get())
        }

        single { OrgCommands(get(), get(), get(), get(), get(), get(), get()) }
        single { MemberCommands(get(), get(), get(), get(), get()) }
        single { InvitationCommands(get(), get(), get(), get(), get(), get()) }
        single { OrgsRoutesDependencies(get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = OrgsMigrations

    override fun routes(route: Route, koin: Koin) {
        val dependencies = koin.get<OrgsRoutesDependencies>()
        route.orgsRoutes(dependencies)
        route.myOrganizationsRoutes(dependencies)
    }
}
