// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "budgets"

private const val SHARING_TAG = "sharing"

private const val LOGIN = "The member's login."

private const val MEMBER_NOT_FOUND = "No such member in the caller's organization (`not_found`)."

private const val BAD_OVERRIDE = "A field out of bounds, or past the grants a member or an organization may hold " +
    "(`invalid_budget_override`)."

private const val STALE = "The override named is no longer the one in force, or has expired " +
    "(`stale_budget_override`)."

/**
 * The budgets module's operations: the organization's spending limits, the caller's own budget, members' temporary
 * daily budgets, and the automatic pool size. The bodies are the module's own mirrors of ostrom-core's `Budget*` and
 * `PoolSize*` models.
 */
internal fun ApiCatalog.budgetsOperations() {
    tag(TAG, "The spending limits of the organization, of its members and of its workflows.")
    budgetOperations()
    myBudgetOperations()
    budgetOverrideOperations()
    budgetRevocationOperations()
    poolSizeOperations()
}

/** The organization's limits. */
private fun ApiCatalog.budgetOperations() {
    get("/api/v1/budgets") {
        operationId = "listBudgets"
        tag(TAG)
        summary = "Budgets."
        description = "Owners and admins only, read by a browser session or a token holding `budgets:read`: one made " +
            "on the console, or the token of `ostrom login` (with or without `--admin`), which holds it for " +
            "`getMyBudget` (SECURITY-AUDIT S2-03 kept every member's limits off the CLI's tokens until " +
            "ostrom-server#587; they now read them, never write them, and only for an owner or an admin). A service " +
            "key holding `team:read` reads them too. A member, whatever the token: 403. A device credential (a " +
            "machine's, not the CLI's token), a key without the scope or an agent token: 403."
        parameters {
            organizationHeader()
        }
        responses {
            ok<BudgetsPage>("The limits: the organization's, the default member's, members' own, then the " +
                "workflows', with what the organization spent this month.")
        }
    }
    put("/api/v1/budgets") {
        operationId = "setBudget"
        tag(TAG)
        summary = "Set a budget (the organization, every member, one member, or a workflow)."
        description = "Replaces the subject's limits whole: a monthly one (`usdPerMonth`) and a daily one " +
            "(`usdPerDay`), both stopping the calls (`hardStop` true) or both only warning (false); neither amount " +
            "removes the subject's limits. The subject is the organization (`scope` `organization`), every member " +
            "without limits of their own (`member` without `subject`), one member (`member`, `subject` = the " +
            "member's `id` from `/api/v1/orgs/{org}/members`), or a workflow (`workflow`, `subject` = the workflow's " +
            "id: the spend of the model calls of the launches that play it). Written by a browser session or a token " +
            "holding `budgets:write`; no other credential writes a budget, a service key included (403). Each change " +
            "is written to the organization's audit log (`budget.changed`)."
        parameters {
            organizationHeader()
        }
        body<BudgetDto>("The subject and its limits.")
        responses {
            ok<BudgetDto>("The limits written.")
            badRequest("An unknown scope, an amount out of bounds, or a subject that is not a member or a " +
                "workflow id (`invalid_request`).")
            notFound("No such workflow in the caller's organization (`not_found`).")
        }
    }
}

/** The caller's own budget. */
private fun ApiCatalog.myBudgetOperations() {
    get("/api/v1/budgets/me") {
        operationId = "getMyBudget"
        tag(TAG)
        summary = "The caller's own budget this month, for any member and for an agent session."
        description = "Read by a browser session, a token with `budgets:read`, or an agent session's token. The " +
            "token of `ostrom login`, with or without `--admin`, holds `budgets:read` (`ostrom status` shows this " +
            "budget): it reads the budget of the person it belongs to; the organization's list (`listBudgets`) only " +
            "when that person is an owner or an admin. A CLI token issued before it held `budgets:read`, " +
            "`accounts:read`, `inference:read` and `guidance` lacks them (403): `ostrom login` again."
        parameters {
            organizationHeader()
        }
        responses {
            ok<MyBudgetResponse>("The caller's limit, spend and what is left, with the override in force.")
        }
    }
}

/** Members' temporary daily budgets. */
private fun ApiCatalog.budgetOverrideOperations() {
    get("/api/v1/members/{login}/budget-overrides") {
        operationId = "listBudgetOverrides"
        tag(TAG)
        summary = "Budget overrides of a member (`capacity.budgetoverrides.v1`)."
        description = "TeamCode's temporary quota overrides: the member's grants, newest first, 50 at most a page " +
            "(`next` is the `before` of the following page), revoked and expired ones included, and `activeId`, the " +
            "one in force now. Owners and admins, a person signed in; a member (their own is in `getMyBudget`), a " +
            "key or an agent: 403."
        parameters {
            organizationHeader()
            pathId("login", LOGIN)
            queryText("before", "The `id` of the last item of the previous page.")
        }
        responses {
            ok<BudgetOverridesPage>("A page of the member's overrides, and the one in force.")
            badRequest("A malformed cursor (`invalid_budget_override`).")
            notFound(MEMBER_NOT_FOUND)
        }
    }
    post("/api/v1/members/{login}/budget-overrides") {
        operationId = "createBudgetOverride"
        tag(TAG)
        summary = "Grant a temporary daily budget (`capacity.budgetoverrides.v1`)."
        description = "While active, `micros` replaces the member's own daily limit that stops the calls (or adds " +
            "one when they have none): the total the member may spend today (UTC), not an addition, so a member who " +
            "spent 80 under a day limit of 100 and is granted 300 may spend 220 more, and an override never refunds " +
            "what was spent. `0` stops the member's calls; it does not mean unlimited. It ends at the earlier of " +
            "`durationMinutes` and the next midnight UTC, by the database's clock, with no job. The organization's " +
            "limits, the member's monthly limit, the workflow's, the launch's and the model policy still apply, and " +
            "so do calls in flight. `replacesId` must be the override in force when there is one, and null when " +
            "there is none, else 409 `stale_budget_override`: it replaces it, which stays in the history. At most 5 " +
            "000 grants a member and 100 000 an organization: past them, 400 `invalid_budget_override`. Audited " +
            "(`budget_override.granted`, `budget_override.superseded`). Owners and admins, a person signed in (a " +
            "sign-in less than 5 minutes old: 403 `step_up_required` otherwise)."
        parameters {
            organizationHeader()
            pathId("login", LOGIN)
        }
        body<GrantBudgetOverrideRequest>("The daily allowance, how long it lasts, why, and the override it " +
            "replaces.")
        responses {
            ok<BudgetOverrideDto>("The override granted.", HttpStatusCode.Created)
            badRequest(BAD_OVERRIDE)
            notFound(MEMBER_NOT_FOUND)
            conflict(STALE)
        }
    }
}

/** Revoking a temporary daily budget. */
private fun ApiCatalog.budgetRevocationOperations() {
    post("/api/v1/members/{login}/budget-overrides/{id}/revoke") {
        operationId = "revokeBudgetOverride"
        tag(TAG)
        summary = "Revoke a temporary daily budget (`capacity.budgetoverrides.v1`)."
        description = "The member's own limits apply again at once; calls in flight finish. Revoking one already " +
            "revoked answers it as it is (the first revocation is kept) and never revokes another grant. Audited " +
            "(`budget_override.revoked`). Same authority as `createBudgetOverride`: owners and admins, a person " +
            "signed in less than 5 minutes ago (403 `step_up_required` otherwise)."
        parameters {
            organizationHeader()
            pathId("login", LOGIN)
            pathId("id", "The override's id.")
        }
        body<RevokeBudgetOverrideRequest>("Why it is revoked.")
        responses {
            ok<BudgetOverrideDto>("The override, revoked.")
            badRequest(BAD_OVERRIDE)
            notFound("No such member, or no such override of theirs (`not_found`).")
            conflict("The override has expired (`stale_budget_override`).")
        }
    }
}

/** The automatic pool size, mounted when the sharing module and the pacing settings are there. */
private fun ApiCatalog.poolSizeOperations() {
    get("/api/v1/pools/size") {
        operationId = "getPoolSize"
        tag(SHARING_TAG)
        summary = "The organization's automatic daily budget sized from its shared accounts " +
            "(`capacity.poolsize.v1`)."
        description = "TeamCode's automatic shared pool size (ADR 0002 step 2), computed on demand from the same " +
            "data as the job that stores it every ten minutes. Each shared window of each account learns its full " +
            "capacity in API-equivalent (USD millionths at the gateway's prices): a sample pairs two readings of the " +
            "same window period at least five points and one hour apart and divides the cost the gateway settled on " +
            "the account between them by the rise; with at least three samples over seven days, the median sizes the " +
            "budget and the upper quartile is the reported capacity. An account's daily figure is the smaller of its " +
            "five-hour term (five-hour capacity × its ceiling × 24 / 5) and its weekly term (weekly points left " +
            "before its ceiling × weekly capacity / days left before the reset). Accounts that cannot serve count " +
            "for nothing (`unavailable`); accounts without a fresh reading or an estimate are left out " +
            "(`no_fresh_observation`, `no_estimate`). With `autoPoolSize` on (`PUT /api/v1/policy/pacing`), the " +
            "stored value, moving at most 20 % an hour and younger than an hour, replaces the organization's daily " +
            "budget (`manualDailyMicros`); otherwise, or without an estimate (`reason`), the typed budget applies. " +
            "Owners and admins (a person signed in, or a key holding `team:read`); anyone else: 403. Never cached."
        parameters {
            organizationHeader()
        }
        responses {
            ok<PoolSizeResponse>("The pool size, the estimate behind it, and each account's share.")
        }
    }
}
