// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberEmails
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.kernel.module.TestAccountSeeder
import run.ostrom.server.orgs.domain.AcceptInvitationUseCase
import run.ostrom.server.orgs.domain.AcceptInvitationUseCaseImpl
import run.ostrom.server.orgs.domain.ChangeRoleUseCase
import run.ostrom.server.orgs.domain.ChangeRoleUseCaseImpl
import run.ostrom.server.orgs.domain.CheckMemberWriteUseCase
import run.ostrom.server.orgs.domain.CheckMemberWriteUseCaseImpl
import run.ostrom.server.orgs.domain.CountMembersUseCase
import run.ostrom.server.orgs.domain.CountMembersUseCaseImpl
import run.ostrom.server.orgs.domain.CreateInviteLinkUseCase
import run.ostrom.server.orgs.domain.CreateInviteLinkUseCaseImpl
import run.ostrom.server.orgs.domain.CreateOrgUseCase
import run.ostrom.server.orgs.domain.OrgCreationGate
import run.ostrom.server.orgs.domain.OrgCreationGuards
import run.ostrom.server.orgs.infrastructure.OrgSignUpInvitations
import run.ostrom.server.orgs.infrastructure.SignUpOrgCreationGate
import run.ostrom.server.identity.domain.SignUpInvitations
import run.ostrom.server.orgs.domain.CreateOrgUseCaseImpl
import run.ostrom.server.orgs.domain.DeleteOrgUseCase
import run.ostrom.server.orgs.domain.DeleteOrgUseCaseImpl
import run.ostrom.server.orgs.domain.FindOrgUseCase
import run.ostrom.server.orgs.domain.FindOrgUseCaseImpl
import run.ostrom.server.orgs.domain.FreePlanSeats
import run.ostrom.server.orgs.domain.GetMemberUseCase
import run.ostrom.server.orgs.domain.GetMemberUseCaseImpl
import run.ostrom.server.orgs.domain.GetOrgUseCase
import run.ostrom.server.orgs.domain.GetOrgUseCaseImpl
import run.ostrom.server.orgs.domain.InvitationMailer
import run.ostrom.server.orgs.domain.InvitationServices
import run.ostrom.server.orgs.domain.InvitationTokens
import run.ostrom.server.orgs.domain.InvitationsRepository
import run.ostrom.server.orgs.domain.InviteByEmailUseCase
import run.ostrom.server.orgs.domain.InviteByEmailUseCaseImpl
import run.ostrom.server.orgs.domain.LeaveOrgUseCase
import run.ostrom.server.orgs.domain.LeaveOrgUseCaseImpl
import run.ostrom.server.orgs.domain.ListInvitationsUseCase
import run.ostrom.server.orgs.domain.ListInvitationsUseCaseImpl
import run.ostrom.server.orgs.domain.ListMembersUseCase
import run.ostrom.server.orgs.domain.ListMembersUseCaseImpl
import run.ostrom.server.orgs.domain.ListMyOrgsUseCase
import run.ostrom.server.orgs.domain.ListMyOrgsUseCaseImpl
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.OrgDeletionsRepository
import run.ostrom.server.orgs.domain.OrgAudit
import run.ostrom.server.orgs.domain.OrgRateLimiter
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.orgs.domain.People
import run.ostrom.server.orgs.domain.PreviewInvitationUseCase
import run.ostrom.server.orgs.domain.PreviewInvitationUseCaseImpl
import run.ostrom.server.orgs.domain.RemoveMemberUseCase
import run.ostrom.server.orgs.domain.RemoveMemberUseCaseImpl
import run.ostrom.server.orgs.domain.RevokeInvitationUseCase
import run.ostrom.server.orgs.domain.RevokeInvitationUseCaseImpl
import run.ostrom.server.orgs.domain.SeatPolicy
import run.ostrom.server.orgs.domain.SettleOrgDeletionsUseCase
import run.ostrom.server.orgs.domain.SettleOrgDeletionsUseCaseImpl
import run.ostrom.server.orgs.domain.TransferOwnershipUseCase
import run.ostrom.server.orgs.domain.TransferOwnershipUseCaseImpl
import run.ostrom.server.orgs.domain.UpdateOrgUseCase
import run.ostrom.server.orgs.domain.UpdateOrgUseCaseImpl
import run.ostrom.server.orgs.infrastructure.IdentityInvitationTokens
import run.ostrom.server.orgs.infrastructure.IdentityOrgAudit
import run.ostrom.server.orgs.infrastructure.IdentityPeople
import run.ostrom.server.orgs.infrastructure.IdentityRateLimiter
import run.ostrom.server.orgs.infrastructure.InvitationsRepositoryImpl
import run.ostrom.server.orgs.infrastructure.MailInvitationMailer
import run.ostrom.server.orgs.infrastructure.MemberDirectoryImpl
import run.ostrom.server.orgs.infrastructure.MemberEmailsImpl
import run.ostrom.server.orgs.infrastructure.MemberLoginsImpl
import run.ostrom.server.orgs.infrastructure.MembersRepositoryImpl
import run.ostrom.server.orgs.infrastructure.MembershipOrgResolver
import run.ostrom.server.orgs.infrastructure.OrgDeletionWorker
import run.ostrom.server.orgs.infrastructure.OrgDeletionsRepositoryImpl
import run.ostrom.server.orgs.infrastructure.OrgsMigrations
import run.ostrom.server.orgs.infrastructure.OrgsRepositoryImpl
import run.ostrom.server.orgs.infrastructure.TestAccountSeederImpl
import run.ostrom.server.orgs.presentation.InvitationCommands
import run.ostrom.server.orgs.presentation.MemberCommands
import run.ostrom.server.orgs.presentation.OrgCommands
import run.ostrom.server.orgs.presentation.OrgsConfig
import run.ostrom.server.orgs.presentation.OrgsGovernanceChange
import run.ostrom.server.orgs.presentation.OrgsRoutesDependencies
import run.ostrom.server.orgs.presentation.myOrganizationsRoutes
import run.ostrom.server.orgs.presentation.orgsRoutes
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The orgs module: organizations, members and their roles, invitations, the free-plan guard, and
 * the server's [PrincipalOrgResolver]. Its worker finishes the deletions: it tells every bound
 * [OrgDeletionListener], then erases a deleted organization after 30 days.
 *
 * Other modules use it through the interfaces it binds: [PrincipalOrgResolver] (through the
 * kernel's `requireOrgPrincipal`), [MemberDirectory] (who a wait may be asked of), [MemberLogins] (the
 * people a shared account is lent to, by login), [MemberEmails] (the address of the person who opens a
 * Checkout), [CountMembersUseCase] for
 * per-seat billing. Until the billing
 * module binds its own [SeatPolicy] here, every organization has the free plan's limit
 * ([FreePlanSeats]).
 */
object OrgsModule : ServerModule {
    override val name: String = "orgs"

    override val koinModule: Module = module {
        single { OrgsConfig.from(get()) }
        single<OrgsRepository> { OrgsRepositoryImpl(get(), get()) }
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
        single<MemberLogins> { MemberLoginsImpl(get(), get()) }
        single<MemberEmails> { MemberEmailsImpl(get(), get()) }
        // Identity has no teams: the QA account (a person, their team, a token) is assembled here.
        single<TestAccountSeeder> { TestAccountSeederImpl(get(), get(), get(), get()) }
        single {
            InvitationServices(get(), get(), get(), get(), get(), get<OrgsConfig>()::invitationUrl)
        }

        single<FindOrgUseCase> { FindOrgUseCaseImpl(get()) }
        single<ListMyOrgsUseCase> { ListMyOrgsUseCaseImpl(get()) }
        single<OrgCreationGate> { SignUpOrgCreationGate(get()) }
        // An invitation's person may get an account on an invite-only instance (identity's SignUpPolicy).
        single<SignUpInvitations> { OrgSignUpInvitations(get(), get(), get()) }
        single<CreateOrgUseCase> {
            CreateOrgUseCaseImpl(get(), get(), OrgCreationGuards(get(), get()), get(), get(), get())
        }
        single<GetOrgUseCase> { GetOrgUseCaseImpl(get(), get()) }
        single<UpdateOrgUseCase> { UpdateOrgUseCaseImpl(get(), get(), get()) }
        single<DeleteOrgUseCase> { DeleteOrgUseCaseImpl(get(), get(), get()) }
        // Every module that keeps or bills something of an organization binds a listener (billing).
        single<OrgDeletionsRepository> { OrgDeletionsRepositoryImpl(get()) }
        single<SettleOrgDeletionsUseCase> {
            SettleOrgDeletionsUseCaseImpl(get(), getAll<OrgDeletionListener>(), get(), get())
        }
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
        single<CheckMemberWriteUseCase> { CheckMemberWriteUseCaseImpl(get(), get(), get()) }
        single { MemberCommands(get(), get(), get(), get(), get(), get()) }
        single { InvitationCommands(get(), get(), get(), get(), get(), get()) }
        single { OrgsRoutesDependencies(get(), get(), get(), get()) }
        // The member writes a person's API token asks for, held for the console (contract audit 2, F1).
        single<GovernanceChange>(named("orgs")) { OrgsGovernanceChange(get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = OrgsMigrations

    override fun routes(route: Route, koin: Koin) {
        val dependencies = koin.get<OrgsRoutesDependencies>()
        route.orgsRoutes(dependencies)
        route.myOrganizationsRoutes(dependencies)
    }

    override fun start(scope: CoroutineScope, koin: Koin) {
        val worker = OrgDeletionWorker(koin.get(), koin.get())
        scope.launch { worker.run() }
    }
}
