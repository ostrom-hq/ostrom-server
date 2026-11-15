// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import kotlinx.serialization.json.JsonObject
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText

private const val TAG = "automations"

private const val WAITS_TAG = "waits"

private const val AUTOMATION_ID = "The automation's id."

private const val NOT_FOUND = "No such automation in the caller's organization, or one the caller may not read " +
    "(`not_found`)."

private const val SIGNATURE = "A wrong or missing signature, or an automation that does not exist (`unauthorized`)."

private const val SIGNATURE_RATE = "Too many calls for this automation lately (`rate_limited`): nothing was done."

/**
 * The automations module's operations: the automations of an organization, their signed webhook, and the waits
 * ("Waiting for you") that agents ask and people answer. The bodies are the module's own mirrors of ostrom-core's
 * `Automation*` and `Wait*` models.
 */
internal fun ApiCatalog.automationsOperations() {
    tag(TAG, "Automations: a cloud agent session or a workflow run started by a schedule or a signed webhook.")
    tag(WAITS_TAG, "The waits of agents: a question put to a person, a time or an event, and what answers it.")
    automationDefinitionOperations()
    automationItemOperations()
    automationStateOperations()
    automationRunOperations()
    automationTriggerOperations()
    waitsOperations()
}

/** Listing, creating, reading, changing and deleting. */
private fun ApiCatalog.automationDefinitionOperations() {
    get("/api/v1/automations") {
        operationId = "listAutomations"
        tag(TAG)
        summary = "Automations."
        description = "The automations the caller may read, newest first. A member lists their own; an owner or an " +
            "administrator lists another member's with `owner`, or everyone's with `all`."
        parameters {
            organizationHeader()
            queryText("owner", "`all`, or a member id: the automations of that member, for an administrator only.")
            queryText("before", "The `next` of the previous page: the id of its last automation.")
            queryLimit("The page size, 1 to 100.", maximum = 100)
        }
        responses {
            ok<AutomationsResponse>("A page of automations, `next` set when another may follow.")
            badRequest("A malformed cursor, or one that names no automation of the organization's (`invalid_request`).")
        }
    }
    post("/api/v1/automations") {
        operationId = "createAutomation"
        tag(TAG)
        summary = "Create an automation."
        description = "The answer carries the webhook's secret, once and never again: `rotateAutomationSecret` " +
            "issues another."
        parameters {
            organizationHeader()
        }
        body<AutomationRequest>("The automation: its name, trigger, action, variables and limits.")
        responses {
            ok<AutomationResponse>("The automation created, with its secret.", HttpStatusCode.Created)
            badRequest("A field out of bounds, or a definition that cannot run (`invalid_request`).")
            conflict("A limit of the organization is reached (`conflict`).")
        }
    }
}

/** One automation. */
private fun ApiCatalog.automationItemOperations() {
    get("/api/v1/automations/{id}") {
        operationId = "getAutomation"
        tag(TAG)
        summary = "An automation."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
        }
        responses {
            ok<AutomationResponse>("The automation.")
            notFound(NOT_FOUND)
        }
    }
    put("/api/v1/automations/{id}") {
        operationId = "saveAutomation"
        tag(TAG)
        summary = "Save an automation."
        description = "Replaces the definition whole. The body gives the `version` it read: a newer one is refused " +
            "and nothing is changed."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
        }
        body<AutomationRequest>("The automation, with the `version` read.")
        responses {
            ok<AutomationResponse>("The automation saved, at its next version.")
            badRequest("A field out of bounds, a definition that cannot run, or no `version` (`invalid_request`).")
            notFound(NOT_FOUND)
            conflict("The automation changed since the `version` read (`conflict`).")
        }
    }
    delete("/api/v1/automations/{id}") {
        operationId = "deleteAutomation"
        tag(TAG)
        summary = "Delete an automation."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
        }
        responses {
            empty("The automation is deleted.")
            notFound(NOT_FOUND)
        }
    }
}

/** Arming, pausing and rotating the secret. */
private fun ApiCatalog.automationStateOperations() {
    post("/api/v1/automations/{id}/enable") {
        operationId = "enableAutomation"
        tag(TAG)
        summary = "Enable."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
        }
        responses {
            ok<AutomationResponse>("The automation, armed.")
            notFound(NOT_FOUND)
        }
    }
    post("/api/v1/automations/{id}/disable") {
        operationId = "disableAutomation"
        tag(TAG)
        summary = "Disable."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
        }
        responses {
            ok<AutomationResponse>("The automation, paused.")
            notFound(NOT_FOUND)
        }
    }
    post("/api/v1/automations/{id}/webhook/rotate-secret") {
        operationId = "rotateAutomationSecret"
        tag(TAG)
        summary = "Rotate the webhook secret (the old one stays valid 10 minutes); returned once."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
        }
        responses {
            ok<SecretResponse>("The new secret, and until when the previous one still signs.")
            notFound(NOT_FOUND)
        }
    }
}

/** Tests, cancelling and the history. */
private fun ApiCatalog.automationRunOperations() {
    post("/api/v1/automations/{id}/run") {
        operationId = "testAutomation"
        tag(TAG)
        summary = "Test run, optionally dry."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
            headerText("Idempotency-Key", "One key per test, needed unless `dryRun`: the same key returns the " +
                "first run, answered with `Idempotency-Replayed: true`.")
        }
        body<TestRunRequest>("A sample payload, and `dryRun` to render without starting anything.")
        responses {
            ok<DryRunResponse>("A dry run (`dryRun` true): nothing starts.")
            ok<RunResponse>("A test run, started with the saved definition.", HttpStatusCode.Accepted)
            badRequest("A test run without a well-formed `Idempotency-Key`, or a sample payload that is not a JSON " +
                "object (`invalid_request`).")
            notFound(NOT_FOUND)
        }
    }
    get("/api/v1/automations/{id}/runs") {
        operationId = "listAutomationRuns"
        tag(TAG)
        summary = "The runs of an automation, newest first, kept 14 days."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
            queryText("before", "The `next` of the previous page: the id of its last run.")
            queryLimit("The page size, 1 to 100.", maximum = 100)
        }
        responses {
            ok<RunsResponse>("A page of runs, `next` set when another may follow.")
            badRequest("A malformed cursor, or a `limit` outside 1 to 100 (`invalid_request`).")
            notFound(NOT_FOUND)
        }
    }
    post("/api/v1/automations/{id}/runs/{runId}/cancel") {
        operationId = "cancelAutomationRun"
        tag(TAG)
        summary = "Cancel a run."
        description = "A queued one ends `cancelled` at once; a started one has its session paused, or its workflow " +
            "run cancelled. The owner, or an administrator."
        parameters {
            organizationHeader()
            pathId("id", AUTOMATION_ID)
            pathId("runId", "The run's id.")
        }
        responses {
            ok<RunResponse>("The run.")
            notFound("No such automation or run in the caller's organization (`not_found`).")
            conflict("The run has ended (`run_ended`).")
        }
    }
}

private const val TRIGGER_INVALID = "A body over 64 KiB or not a JSON object, or a delivery key outside " +
    "`A-Za-z0-9._:-` (1 to 128 characters) (`invalid_request`)."

/** The signed webhook, which takes no login. */
private fun ApiCatalog.automationTriggerOperations() {
    post("/api/v1/automations/{id}/trigger") {
        operationId = "triggerAutomation"
        tag(TAG)
        summary = "Signed webhook trigger."
        description = "No sign-in: the request is signed with the automation's secret (`Ostrom-Signature`, " +
            "`v1=` and the HMAC-SHA256 of the timestamp and the body; `Ostrom-Timestamp`), and a wrong signature, " +
            "and an automation that does not exist answer 401 alike. A delivery key (`Idempotency-Key`, or " +
            "`Ostrom-Delivery`) makes a retry return the first run. The body is at most 64 KiB."
        parameters {
            pathId("id", AUTOMATION_ID)
            webhookHeaders()
        }
        body<JsonObject>("The event's JSON payload, which the automation's variables read.")
        responses {
            ok<TriggerResponse>("The run, queued.", HttpStatusCode.Accepted)
            badRequest(TRIGGER_INVALID)
            error(HttpStatusCode.Unauthorized, SIGNATURE)
            conflict("The automation is paused, or this delivery was already received (`conflict`).")
            error(HttpStatusCode.TooManyRequests, SIGNATURE_RATE)
        }
    }
    post("/api/v1/automations/{id}/v/{version}/trigger") {
        operationId = "triggerAutomationVersion"
        tag(TAG)
        summary = "The signed webhook trigger, pinned to a version of the definition."
        description = "Runs only while the automation is at `version`, otherwise 409 `automation_version_changed` " +
            "and nothing is recorded; `latest` follows the current version, as `triggerAutomation` does. Enabling, " +
            "pausing and rotating the secret do not change the version. Signed as `triggerAutomation` is."
        parameters {
            pathId("id", AUTOMATION_ID)
            pathId("version", "A version number, or `latest`.")
            webhookHeaders()
        }
        body<JsonObject>("The event's JSON payload, which the automation's variables read.")
        responses {
            ok<TriggerResponse>("The run, queued.", HttpStatusCode.Accepted)
            badRequest(TRIGGER_INVALID)
            error(HttpStatusCode.Unauthorized, SIGNATURE)
            conflict("The automation is no longer at that version (`automation_version_changed`), is paused, or " +
                "this delivery was already received.")
            error(HttpStatusCode.TooManyRequests, SIGNATURE_RATE)
        }
    }
}

/** The headers that sign a delivery. */
private fun Parameters.Builder.webhookHeaders() {
    headerText("Ostrom-Timestamp", "The time of the delivery, in seconds since the epoch.", required = true)
    headerText("Ostrom-Signature", "`v1=` and the hex HMAC-SHA256 of the timestamp and the body.", required = true)
    headerText("Ostrom-Delivery", "A key of the delivery: a retry with the same key returns the first run.")
}
