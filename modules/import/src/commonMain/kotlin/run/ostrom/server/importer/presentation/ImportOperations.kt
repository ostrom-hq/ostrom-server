// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.queryFlag
import io.ktor.http.HttpStatusCode

private const val TAG = "import"

/** The import module's operation: a TeamCode team, read by `ostrom import teamcode`. */
internal fun ApiCatalog.importOperations() {
    tag(TAG, "Moving a TeamCode team to Ostrom.")
    post("/api/v1/import/teamcode") {
        operationId = "importTeamCode"
        tag(TAG)
        summary = "Import a TeamCode team read by `ostrom import teamcode`."
        description = "Owners and administrators, with the scope `import:write`. Idempotent: a second run with the " +
            "same team changes nothing, a run after a change in TeamCode carries only that change."
        parameters {
            organizationHeader()
            queryFlag("dryRun", "Report the plan without writing anything.")
            queryFlag(
                "cutover",
                "The final run, once TeamCode is stopped: owners only (403 `cutover_owner_required` otherwise), " +
                    "recorded in the audit log in the name of the person who runs it, before anything is written " +
                    "(503 `cutover_audit_unavailable` on a server without an audit log, 503 " +
                    "`cutover_sharing_unavailable` on a server without shared accounts; the key is `error.type`). " +
                    "It also revives the shared accounts waiting for a reconnection, testing TeamCode's tokens " +
                    "first (renewing rotates them, so a normal run never does).",
            )
            queryFlag(
                "accessOnly",
                "TeamCode's `--access-only` trial (`sharing.handover.v1`): the accounts the import creates, and " +
                    "those still waiting for their handover, serve with TeamCode's access tokens and are never " +
                    "renewed by Ostrom (`refreshOwnership` `access_only`), so TeamCode keeps renewing its own copy; " +
                    "run the import again when they expire. Accounts Ostrom already renews are left alone. Not with " +
                    "`cutover` (400). Without it, a normal run leaves the accounts it creates waiting for a handover " +
                    "(`pending_handover`), refreshes the tokens of the accounts still waiting with TeamCode's " +
                    "current ones, and the cutover run hands every waiting account over to Ostrom with TeamCode's " +
                    "tokens.",
            )
        }
        body<TeamCodeImportRequest>(
            "The team as `ostrom import teamcode` read it: members, MCP servers, accounts, workflows, automations, " +
                "profiles and catalogue, with the mapping of TeamCode members to members of this organization.",
        )
        responses {
            ok<ImportReportDto>("What was (or, on a dry run, would be) created, updated or left alone.")
            badRequest(
                "`cutover` with `accessOnly`, members mapped to ids that are not members of the organization, or " +
                    "an item this server refuses (`invalid_request`); nothing was imported.",
            )
            error(HttpStatusCode.Forbidden, "The cutover run by anyone but an owner (`cutover_owner_required`).")
            conflict(
                "The provider refuses TeamCode's tokens of a waiting account, or they are another account's " +
                    "(`conflict`).",
            )
            error(
                HttpStatusCode.ServiceUnavailable,
                "A cutover on a server without an audit log (`cutover_audit_unavailable`) or without shared " +
                    "accounts (`cutover_sharing_unavailable`), or the provider could not be reached to test " +
                    "TeamCode's tokens (`unavailable`).",
            )
        }
    }
}
