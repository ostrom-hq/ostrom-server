// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.orgs.domain.AcceptInvitationUseCase
import dev.ostrom.server.orgs.domain.ChangeRoleUseCase
import dev.ostrom.server.orgs.domain.CreateInviteLinkUseCase
import dev.ostrom.server.orgs.domain.CreateOrgUseCase
import dev.ostrom.server.orgs.domain.DeleteOrgUseCase
import dev.ostrom.server.orgs.domain.FindOrgUseCase
import dev.ostrom.server.orgs.domain.GetMemberUseCase
import dev.ostrom.server.orgs.domain.GetOrgUseCase
import dev.ostrom.server.orgs.domain.InviteByEmailUseCase
import dev.ostrom.server.orgs.domain.LeaveOrgUseCase
import dev.ostrom.server.orgs.domain.ListInvitationsUseCase
import dev.ostrom.server.orgs.domain.ListMembersUseCase
import dev.ostrom.server.orgs.domain.ListMyOrgsUseCase
import dev.ostrom.server.orgs.domain.PreviewInvitationUseCase
import dev.ostrom.server.orgs.domain.RemoveMemberUseCase
import dev.ostrom.server.orgs.domain.RenameOrgUseCase
import dev.ostrom.server.orgs.domain.RevokeInvitationUseCase
import dev.ostrom.server.orgs.domain.TransferOwnershipUseCase

/**
 * What the organization routes need.
 *
 * @property resolver Resolves the caller as a member of the organization a route names.
 * @property orgs The organization use cases.
 * @property members The member use cases.
 * @property invitations The invitation use cases.
 */
data class OrgsRoutesDependencies(
    val resolver: PrincipalOrgResolver,
    val orgs: OrgCommands,
    val members: MemberCommands,
    val invitations: InvitationCommands,
)

/**
 * The organization use cases.
 *
 * @property find Turns an id or a short name into an id.
 * @property listMine The caller's organizations.
 * @property create Creates one.
 * @property get Reads one.
 * @property rename Renames one.
 * @property delete Deletes one.
 * @property transfer Transfers its ownership.
 */
data class OrgCommands(
    val find: FindOrgUseCase,
    val listMine: ListMyOrgsUseCase,
    val create: CreateOrgUseCase,
    val get: GetOrgUseCase,
    val rename: RenameOrgUseCase,
    val delete: DeleteOrgUseCase,
    val transfer: TransferOwnershipUseCase,
)

/**
 * The member use cases.
 *
 * @property list Lists the members.
 * @property get Reads one.
 * @property changeRole Changes a role.
 * @property remove Removes one.
 * @property leave The caller leaves.
 */
data class MemberCommands(
    val list: ListMembersUseCase,
    val get: GetMemberUseCase,
    val changeRole: ChangeRoleUseCase,
    val remove: RemoveMemberUseCase,
    val leave: LeaveOrgUseCase,
)

/**
 * The invitation use cases.
 *
 * @property byEmail Invites by e-mail.
 * @property link Creates a link.
 * @property list Lists the live invitations.
 * @property revoke Revokes one.
 * @property preview Shows what a link leads to.
 * @property accept Accepts one.
 */
data class InvitationCommands(
    val byEmail: InviteByEmailUseCase,
    val link: CreateInviteLinkUseCase,
    val list: ListInvitationsUseCase,
    val revoke: RevokeInvitationUseCase,
    val preview: PreviewInvitationUseCase,
    val accept: AcceptInvitationUseCase,
)
