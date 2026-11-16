// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import io.ktor.http.HttpStatusCode

private const val TAG = "sharing"

private const val POLICY_LOGIN = "The login of a member of the organization."

private const val POOL_ROUTING = "A member is served only by the shared accounts whose `poolId` is their home pool, " +
    "and a member in no pool only by the accounts in none (TeamCode's placement): an account in another pool " +
    "never serves them, and with none left in their pool a call is refused as when no account is available. A " +
    "member with `allowSharedOverflow` may also borrow the accounts of the shared overflow pools " +
    "(`PUT /api/v1/pools/budgets`) while each pool's daily budget, minus what its accounts served today (UTC), " +
    "covers what the call may cost; their own pool's accounts come first."

private const val MEMBER_NOT_FOUND = "No member of the organization has this login (`not_found`)."

/** A member's routing policy, their home pool (TeamCode's member policy, its pool only). */
internal fun ApiCatalog.memberPolicyOperations() {
    get("/api/v1/members/{login}/policy") {
        operationId = "getMemberPolicy"
        tag(TAG)
        summary = "A member's home pool."
        description = "An owner or an admin reads any member's policy, a member their own (`accounts:read`, or a " +
            "key's `team:read`; another login is 403 for a member who is no admin, whether it names a member or " +
            "not). $POOL_ROUTING"
        parameters {
            organizationHeader()
            pathId("login", POLICY_LOGIN)
        }
        responses {
            ok<MemberPolicyResponse>("The member's policy; `poolId` is absent for a member in no pool.")
            error(HttpStatusCode.Forbidden, "A member asks for another member's policy, or a credential that may " +
                "not read the accounts (`forbidden`).")
            notFound("No member of the organization has this login, asked by an owner or an admin (`not_found`).")
        }
    }
    patch("/api/v1/members/{login}/policy") {
        operationId = "updateMemberPolicy"
        tag(TAG)
        summary = "Set a member's home pool (admins)."
        description = "TeamCode's `PATCH /api/v1/members/{id}/policy`, its pool only. An owner or an admin " +
            "(`accounts:write`, browser session) puts the member in `poolId`, 1 to 36 letters, digits, " +
            "underscores or hyphens (else 400 `invalid_policy`); an empty string takes them out of their pool. " +
            "`allowSharedOverflow` lets them borrow the shared overflow pools (absent, it is kept). A shared " +
            "overflow pool is never a member's home pool (409 `overflow_primary_pool`). Written to the audit log " +
            "as `policy.updated`. $POOL_ROUTING"
        parameters {
            organizationHeader()
            pathId("login", POLICY_LOGIN)
        }
        body<UpdateMemberPolicyRequest>("The member's home pool, or an empty string for none, and their overflow.")
        responses {
            ok<MemberPolicyResponse>("The member's policy after the change.")
            badRequest("A pool identifier out of its form (`invalid_policy`), or no `poolId` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "Not an owner or an admin of the organization, or a credential that " +
                "may not write (`forbidden`).")
            notFound(MEMBER_NOT_FOUND)
            conflict("The pool is a shared overflow pool (`overflow_primary_pool`).")
        }
    }
}
