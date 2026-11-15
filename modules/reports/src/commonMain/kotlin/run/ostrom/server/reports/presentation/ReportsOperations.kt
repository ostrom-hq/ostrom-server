// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.queryText
import run.ostrom.server.kernel.http.docs.rateLimited
import io.ktor.http.HttpStatusCode

/** The reports module's operations: the spending report and the activity feed. */
internal fun ApiCatalog.reportsOperations() {
    get("/api/v1/spending") {
        operationId = "getSpending"
        tag("budgets")
        summary = "Spending by agent, repository and person."
        description = "The organization's spending over the period, its totals by agent, repository and person, by " +
            "day, and the budgets it exceeded. Read with `usage:read` or `budgets:read`, or a service key's " +
            "`team:read`."
        parameters {
            organizationHeader()
            queryText("period", "`week` or `month` (the current one by default).")
        }
        responses {
            ok<SpendingDto>("The spending over the period.")
            badRequest("A period other than `week` or `month` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "This credential may not read this report (`forbidden`).")
            rateLimited()
        }
    }
    get("/api/v1/activity") {
        operationId = "listActivity"
        tag("events")
        summary = "Activity feed, newest first."
        description = "What happened in the organization: sessions, packages, decisions and their actors, newest first."
        parameters {
            organizationHeader()
            queryText("before", "The cursor of the next page (`next` of the previous one).")
            queryText("repo", "Only the activity of this repository (`owner/name`).")
        }
        responses {
            ok<ActivityPageDto>("A page of the feed, `next` set when another may follow.")
            badRequest("A cursor this server did not write (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "This credential may not read this report (`forbidden`).")
            rateLimited()
        }
    }
}
