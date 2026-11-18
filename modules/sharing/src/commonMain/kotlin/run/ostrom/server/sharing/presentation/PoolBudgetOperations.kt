// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import io.ktor.http.HttpStatusCode

private const val TAG = "sharing"

private const val OVERFLOW = "A shared overflow pool (`sharedOverflow`) lends its accounts to the members of other " +
    "pools allowed overflow (`PATCH /api/v1/members/{login}/policy`) while its `dailyMicros`, minus what its " +
    "accounts served since 00:00 UTC, covers what a call may cost; their home pool's accounts come first. A shared " +
    "pool is never a member's home pool, and an organization shares at most 16. The home pool's daily budget is " +
    "the organization's (`PUT /api/v1/budgets`). A pool that is not shared splits its `dailyMicros` among its " +
    "members by weight: each one's daily limit that stops is their share (`GET /api/v1/members/{login}/capacity`)."

/** The named pools' daily budgets and the shared overflow pools (TeamCode's pool budgets). */
internal fun ApiCatalog.poolBudgetOperations() {
    get("/api/v1/pools/budgets") {
        operationId = "listPoolBudgets"
        tag(TAG)
        summary = "List the pool budgets (admins)."
        description = "TeamCode's `GET /api/v1/pools/budgets`, in USD millionths: each named pool's daily budget " +
            "and whether it lends to other pools, by pool. An owner or an admin (`accounts:read`), or a key's " +
            "`team:read`. $OVERFLOW"
        parameters { organizationHeader() }
        responses {
            ok<List<PoolBudgetDto>>("The pools, by identifier.")
            error(HttpStatusCode.Forbidden, "Not an owner or an admin of the organization, or a credential that " +
                "may not read the accounts (`forbidden`).")
        }
    }
    put("/api/v1/pools/budgets") {
        operationId = "savePoolBudget"
        tag(TAG)
        summary = "Create or update a pool budget (admins)."
        description = "TeamCode's `PUT /api/v1/pools/budgets`, in USD millionths. An owner or an admin " +
            "(`accounts:write`, browser session) sets one named pool's `dailyMicros` (1 to 100,000,000,000) and " +
            "`sharedOverflow` (false when absent). Written to the audit log as `pool_budget.updated`. $OVERFLOW"
        parameters { organizationHeader() }
        body<PoolBudgetDto>("The pool and its budget.")
        responses {
            ok<PoolBudgetDto>("The saved pool.")
            badRequest("A pool identifier or a budget out of its form, or the home pool (`invalid_pool_budget`); " +
                "the home pool shared (`invalid_overflow_pool`); no `poolId` or `dailyMicros` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "Not an owner or an admin of the organization, or a credential that " +
                "may not write (`forbidden`).")
            conflict("A pool with primary members cannot be shared (`overflow_primary_pool`), or 16 pools are " +
                "shared already (`overflow_pool_limit`).")
        }
    }
}
