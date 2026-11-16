// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import run.ostrom.server.sharing.domain.MemberPolicy
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The member policy routes (TeamCode's `PATCH /api/v1/members/{id}/policy`): an owner or an admin sets a member's home
 * pool (`accounts:write`), and reads anyone's; a member reads their own (`accounts:read`). Each change is written to
 * the organization's audit log (`policy.updated`, its subject the member's login).
 *
 * @param dependencies The use cases.
 */
fun Route.memberPolicyRoutes(dependencies: MemberPolicyRoutesDependencies) = with(dependencies) {
    authenticated {
        get<MemberPolicyApi> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ, ServiceKeyScopes.TEAM_READ)
            call.respond(policies.get(caller, params.login).toResponse())
        }
        patch<MemberPolicyApi> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val request = call.receive<UpdateMemberPolicyRequest>()
            val policy = policies.setPolicy(caller, params.login, request.poolId, request.allowSharedOverflow)
            audit.record(
                OrgAuditRecord(
                    caller.orgId,
                    call.requirePrincipal(),
                    "policy.updated",
                    "member",
                    policy.login,
                    auditDetails(
                        "pool_id" to policy.poolId,
                        "allow_shared_overflow" to policy.allowSharedOverflow.takeIf { it },
                    ),
                ),
            )
            call.respond(policy.toResponse())
        }
    }
}

private fun MemberPolicy.toResponse() = MemberPolicyResponse(login, poolId, allowSharedOverflow)
