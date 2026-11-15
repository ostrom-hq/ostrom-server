// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.presentation

import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.anyJson
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import io.ktor.http.HttpStatusCode

private const val TAG = "inference"
private const val BUDGETS_TAG = "budgets"

private const val STEP_UP = "A write from the console needs a sign-in less than 5 minutes old (`step_up_required`); " +
    "sign in again, then send it again. Otherwise the caller's role or key does not allow it (`forbidden`)."

private const val PENDING = "Not applied: made with a person's API token (a personal token, the CLI's included), " +
    "the write opened a confirmation that an owner or an admin confirms on the console within 15 minutes " +
    "(`getGovernanceConfirmation`)."

private const val WORKLOAD_HEADER = "The scheduling class of an inference call in the admission queue " +
    "(`inference.queue.v1`): `interactive` (the default) or `background` (unattended work, `ostrom run " +
    "--workload background`). A hint the caller sets, not a permission: it changes only the order of waiting " +
    "calls. Never sent to a provider. Another value, or the header twice, is refused with 400 `invalid_workload`."

private const val REQUEST_ID_HEADER = "The caller's own id of an inference call (`inference.cancel.v1`), a " +
    "lowercase UUID, fresh for each call: `cancelInferenceRequest` with it stops the call on whichever replica " +
    "runs it, queued or streaming. Never sent to a provider. Not a UUID in lowercase: 400 `invalid_request_id`."

private const val ADMISSION = "Admission (`inference.queue.v1`): a call that cannot start now waits in the " +
    "organization's admission queue, shared by every replica, instead of being refused: while every shared " +
    "account it may use is busy (and no provider key of the organization takes it), or while the member already " +
    "runs as many calls as their `maxConcurrent` in `InferenceSettings.memberConcurrency` allows them (0, the " +
    "default, never makes a call wait). Ready members take turns, one member's calls go in arrival order, " +
    "`interactive` calls go before `background` ones, which join them after 30 seconds of waiting. At most 128 " +
    "calls wait per organization and 8 per member who has a concurrency limit; a call waits at most 90 " +
    "seconds. Past a bound: 503 `queue_full`; past the wait: 503 `queue_timeout`; a wait lost (its replica " +
    "stopped renewing it): 503 `queue_expired`; each with `Retry-After: 15`. Waiting holds no budget and no " +
    "account. Budget and policy refusals stay immediate. A refusal of the gateway carries `x-ostrom-error` with " +
    "its code (`no_capacity`, `queue_full`, `budget_exhausted`...)."

private const val CALLERS = "The member's Ostrom token as `Authorization: Bearer` or `x-api-key`: an agent session " +
    "or a personal token with `inference:use`. A call is held for as long as the provider streams, up to 32 MiB " +
    "of request."

private const val PROXIED = "The request is passed to the provider as the agent sent it, and the answer comes back " +
    "as the provider sent it, so no schema describes either: JSON when the request does not stream, server-sent " +
    "events when it asks for `\"stream\": true`."

private const val REFUSED_400 = "A request the gateway refuses, in the provider's own error shape: not a valid " +
    "request, `x-ostrom-workload` neither `interactive` nor `background`, or `x-ostrom-client-request-id` not one " +
    "lowercase UUID."

private const val REFUSED_401 = "No valid Ostrom token, in the provider's own error shape."

private const val REFUSED_403 = "The credential may not call models (a device, a token without the scope), or the " +
    "model is not allowed by the organization's policy, or the launch is paused, in the provider's own error shape."

private const val REFUSED_429 = "Refused by the budgets before any provider is called (`rate_limit_error` on the " +
    "Messages endpoint, `insufficient_quota` on the OpenAI-compatible ones, which also carry `error.code` " +
    "`budget_exhausted` or `budget_insufficient`): a monthly or daily limit is spent (`Retry-After` gives the " +
    "seconds until it resets), or what is left cannot cover what this call may cost. Or every shared account is " +
    "busy or at its limit (`rate_limit_error`, or `rate_limit_exceeded` with `error.code` `no_capacity`), with " +
    "`Retry-After` when the gateway knows when one frees up. A provider's own 429, once no other account can " +
    "take the call, is relayed as the provider sent it."

private const val PROVIDER_ANSWER = "The provider's answer, as it sent it: JSON, or server-sent events when the " +
    "request streams."

private const val UNREACHABLE = "The provider cannot be reached, in the provider's own error shape."

private const val UNAVAILABLE = "The organization has no usable account or key for this provider, the replica " +
    "holds as many calls as it can (`overloaded_error`), or the admission queue is full or timed out " +
    "(`queue_full`, `queue_timeout`, `queue_expired`), with `Retry-After: 15`."

/**
 * The gateway module's operations: the endpoints agents point their base URL at, the organization's model policy,
 * provider keys and price table, the admission queue, and the capacity controller's settings and advice. The
 * answers are the module's own types; a model call is proxied and has no schema.
 */
internal fun ApiCatalog.gatewayOperations() {
    tag(
        TAG,
        "The model calls an organization's agents make through Ostrom: the provider-compatible endpoints, the " +
            "model policy, the provider keys and the capacity controller's advice.",
    )
    modelCallOperations()
    openAiCallOperations()
    queueOperations()
    policyOperations()
    settingsOperations()
    priceOperations()
    pacingOperations()
    directiveOperations()
    directiveDeliveryOperations()
}

/** The Anthropic-compatible endpoint an agent calls, and the models it may name. */
private fun ApiCatalog.modelCallOperations() {
    post("/api/v1/inference/v1/messages") {
        operationId = "inferenceMessages"
        tag(TAG)
        summary = "The Anthropic-compatible Messages endpoint, metered against budgets."
        description = "Agents set `ANTHROPIC_BASE_URL` to `<server>/api/v1/inference`. $CALLERS $PROXIED $ADMISSION"
        parameters {
            organizationHeader()
            headerText("x-ostrom-workload", WORKLOAD_HEADER)
            headerText("x-ostrom-client-request-id", REQUEST_ID_HEADER)
        }
        responses {
            anyJson(HttpStatusCode.OK, PROVIDER_ANSWER)
            anyJson(HttpStatusCode.BadRequest, REFUSED_400)
            anyJson(HttpStatusCode.Unauthorized, REFUSED_401)
            anyJson(HttpStatusCode.Forbidden, REFUSED_403)
            anyJson(HttpStatusCode.TooManyRequests, REFUSED_429)
            anyJson(HttpStatusCode.ServiceUnavailable, UNAVAILABLE)
            anyJson(HttpStatusCode.BadGateway, UNREACHABLE)
        }
    }
    get("/api/v1/inference/v1/models") {
        operationId = "inferenceModels"
        tag(TAG)
        summary = "The models the caller may use."
        description = "$CALLERS The answer is the OpenAI list, `{\"object\": \"list\", \"data\": [...]}`, each " +
            "model with its `id`, `object` and `type`: the policy's default first, then the models the member's " +
            "list allows, or every priced model when the policy names none."
        parameters { organizationHeader() }
        responses {
            anyJson(HttpStatusCode.OK, "The list of models, in the provider's shape.")
            anyJson(HttpStatusCode.Unauthorized, REFUSED_401)
            anyJson(HttpStatusCode.Forbidden, REFUSED_403)
        }
    }
}

/** The OpenAI-compatible endpoints an agent calls. */
private fun ApiCatalog.openAiCallOperations() {
    post("/api/v1/inference/v1/responses") {
        operationId = "inferenceResponses"
        tag(TAG)
        summary = "The OpenAI-compatible Responses endpoint, metered against budgets."
        description = "Agents set `OPENAI_BASE_URL` to `<server>/api/v1/inference/v1`. $CALLERS $PROXIED $ADMISSION"
        parameters {
            organizationHeader()
            headerText("x-ostrom-workload", WORKLOAD_HEADER)
            headerText("x-ostrom-client-request-id", REQUEST_ID_HEADER)
        }
        responses {
            anyJson(HttpStatusCode.OK, PROVIDER_ANSWER)
            anyJson(HttpStatusCode.BadRequest, REFUSED_400)
            anyJson(HttpStatusCode.Unauthorized, REFUSED_401)
            anyJson(HttpStatusCode.Forbidden, REFUSED_403)
            anyJson(HttpStatusCode.TooManyRequests, REFUSED_429)
            anyJson(HttpStatusCode.ServiceUnavailable, UNAVAILABLE)
            anyJson(HttpStatusCode.BadGateway, UNREACHABLE)
        }
    }
    post("/api/v1/inference/v1/chat/completions") {
        operationId = "inferenceChatCompletions"
        tag(TAG)
        summary = "The OpenAI-compatible chat completions endpoint, metered against budgets."
        description = "Agents set `OPENAI_BASE_URL` to `<server>/api/v1/inference/v1`. $CALLERS $PROXIED $ADMISSION"
        parameters {
            organizationHeader()
            headerText("x-ostrom-workload", WORKLOAD_HEADER)
            headerText("x-ostrom-client-request-id", REQUEST_ID_HEADER)
        }
        responses {
            anyJson(HttpStatusCode.OK, PROVIDER_ANSWER)
            anyJson(HttpStatusCode.BadRequest, REFUSED_400)
            anyJson(HttpStatusCode.Unauthorized, REFUSED_401)
            anyJson(HttpStatusCode.Forbidden, REFUSED_403)
            anyJson(HttpStatusCode.TooManyRequests, REFUSED_429)
            anyJson(HttpStatusCode.ServiceUnavailable, UNAVAILABLE)
            anyJson(HttpStatusCode.BadGateway, UNREACHABLE)
        }
    }
}

/** The admission queue's counts, and the cancellation of a call. */
private fun ApiCatalog.queueOperations() {
    get("/api/v1/inference/queue") {
        operationId = "getInferenceQueue"
        tag(TAG)
        summary = "The inference calls waiting in the admission queue."
        description = "(`inference.queue.v1`) Any member reads the counts (`inference:use`, `inference:read` or " +
            "`usage:read` on a key; the token of `ostrom login` holds `inference:read`, for `ostrom status`); no " +
            "call, prompt or other member is named. Counts only waits still renewed by their replica."
        parameters { organizationHeader() }
        responses {
            ok<InferenceQueueDto>("The calls waiting in the organization, and the caller's among them.")
        }
    }
    post("/api/v1/inference/requests/{id}/cancel") {
        operationId = "cancelInferenceRequest"
        tag(TAG)
        summary = "Stop one of the caller's inference calls."
        description = "(`inference.cancel.v1`) By its `x-ostrom-client-request-id`. Leaves a cancellation marker " +
            "every replica reads: the call with that id, of the same member, stops within about half a second " +
            "wherever it runs (waiting in the queue, between accounts, or streaming), and a call that arrives " +
            "after the marker stops before it starts. 204 acknowledges the marker, also for an id no call " +
            "carries (it does not tell whether a call exists), never that the provider stopped or that anything " +
            "is refunded. Idempotent. A marker lasts 10 minutes; at most 256 per member and 4,096 per " +
            "organization. The same credentials as the inference endpoints (an agent session, `inference:use`); " +
            "a call of another member is never touched."
        parameters {
            organizationHeader()
            pathId("id", "The call's `x-ostrom-client-request-id`, a lowercase UUID.")
        }
        responses {
            empty("The marker is stored.")
            badRequest("The id is not a lowercase UUID (`invalid_request_id`).")
            error(
                HttpStatusCode.ServiceUnavailable,
                "Too many calls were cancelled lately: 256 per member or 4,096 per organization " +
                    "(`cancellation_limit`).",
            )
        }
    }
}

/** The organization's model policy. */
private fun ApiCatalog.policyOperations() {
    get("/api/v1/policy/models") {
        operationId = "getModelPolicy"
        tag(TAG)
        summary = "The model policy."
        description = "The models every member may use, the default one, and each member's own list. Read by an " +
            "agent session, or with `inference:read`."
        parameters { organizationHeader() }
        responses {
            ok<ModelPolicyDto>("The policy.")
        }
    }
    put("/api/v1/policy/models") {
        operationId = "setModelPolicy"
        tag(TAG)
        summary = "Set the model policy."
        description = "Applied at once for a browser session and for an `inference:admin` service key. Made with a " +
            "person's API token (a personal token, the CLI's included), the write is held: an owner or an admin " +
            "confirms it on the console. Only an owner or an admin changes it."
        parameters { organizationHeader() }
        body<ModelPolicyDto>("The models allowed, the default one and the members' own lists.")
        responses {
            ok<ModelPolicyDto>("The policy as saved.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest("An invalid model list or default (`invalid_request`).")
            error(
                HttpStatusCode.Forbidden,
                "Not an owner or an admin, or a key without `inference:admin` (`forbidden`).",
            )
        }
    }
}

/** The provider keys and the shared accounts. */
private fun ApiCatalog.settingsOperations() {
    get("/api/v1/inference/settings") {
        operationId = "getInferenceSettings"
        tag(TAG)
        summary = "The organization's inference settings."
        description = "The provider keys, each named by the organization secret holding it and never sent, whether " +
            "shared accounts serve first, and the most calls each member runs at once. Scope `inference:admin`."
        parameters { organizationHeader() }
        responses {
            ok<InferenceSettingsDto>("The settings.")
        }
    }
    put("/api/v1/inference/settings") {
        operationId = "setInferenceSettings"
        tag(TAG)
        summary = "Replace the organization's inference settings."
        description = "Owners and admins, with `inference:admin`, from the console with a recent sign-in; a service " +
            "key with the scope too. A key is named by the secret that holds it. Audited " +
            "(`inference.settings.changed`), with the names of the secrets, never a key."
        parameters { organizationHeader() }
        body<InferenceSettingsDto>("The secrets holding the keys, the shared accounts' use and the concurrency.")
        responses {
            ok<InferenceSettingsDto>("The settings as saved.")
            badRequest("A key that is not the name of an organization secret, or an invalid limit (`invalid_request`).")
            error(HttpStatusCode.Forbidden, STEP_UP)
        }
    }
}

/** The organization's API price table. */
private fun ApiCatalog.priceOperations() {
    get("/api/v1/policy/api-prices") {
        operationId = "getApiPrices"
        tag(BUDGETS_TAG)
        summary = "The organization's API price table."
        description = "(`capacity.apiprices.v1`) TeamCode's editable price table: what the provider's " +
            "pay-as-you-go API charges, per million tokens, for each model from a date on. It prices the model " +
            "calls of the organization (their cost in the ledger, the budgets, the automatic pool size). With no " +
            "row at all the gateway's built-in Anthropic list prices apply (`defaults: true`); with any row, only " +
            "the rows do, and a model with no row is unpriced (cost 0, flagged). Any member, or a key holding " +
            "`usage:read`."
        parameters { organizationHeader() }
        responses {
            ok<ApiPriceTableDto>("The rows, and whether the built-in prices apply.")
        }
    }
    put("/api/v1/policy/api-prices") {
        operationId = "setApiPrices"
        tag(BUDGETS_TAG)
        summary = "Replace the organization's API price table."
        description = "Owners and admins, a person signed in. Replaces every row (an empty list goes back to the " +
            "built-in prices). At most 200 rows; a provider (`anthropic`, `openai`), a lowercase model or family " +
            "prefix, a date, four prices from 0 to 1 000 000 000 000 with input and output above 0; one row a " +
            "provider, model and date. A row prices a call from its date until a later row of the same model; an " +
            "exact model wins over the longest prefix. Audited (`capacity.api_price_changed`, with the number of " +
            "rows before and after)."
        parameters { organizationHeader() }
        body<ApiPriceTableDto>("The rows of the table.")
        responses {
            ok<ApiPriceTableDto>("The table as saved.")
            badRequest("An invalid row, or more than 200 (`invalid_api_price`).")
            error(HttpStatusCode.Forbidden, STEP_UP)
        }
    }
    post("/api/v1/policy/api-prices/load-defaults") {
        operationId = "loadApiPriceDefaults"
        tag(BUDGETS_TAG)
        summary = "Add Anthropic's current list prices to the table."
        description = "Owners and admins, a person signed in. Adds the built-in rows the table lacks, dated that " +
            "day; a row already present for the same model and date is kept as typed, so loading twice changes " +
            "nothing. Audited (`capacity.api_prices_defaults_loaded`)."
        parameters { organizationHeader() }
        responses {
            ok<ApiPriceTableDto>("The whole table.")
            error(HttpStatusCode.Forbidden, STEP_UP)
        }
    }
}

/** The capacity controller's settings. */
private fun ApiCatalog.pacingOperations() {
    get("/api/v1/policy/pacing") {
        operationId = "getPacingSettings"
        tag(TAG)
        summary = "The capacity controller's settings."
        description = "(`capacity.pacing.v1`) Any member reads them (`inference:use` or `guidance` on a key; the " +
            "token of `ostrom login` holds `guidance`), and an agent token. Off by default: `observationOnly` " +
            "false (the controller spaces admissions and sends its advice to agents) and `autoPoolSize` false " +
            "(the typed daily budget applies)."
        parameters { organizationHeader() }
        responses {
            ok<PacingSettingsDto>("The settings.")
        }
    }
    put("/api/v1/policy/pacing") {
        operationId = "setPacingSettings"
        tag(TAG)
        summary = "Change the capacity controller's settings."
        description = "Owners and admins, a person signed in. A field omitted keeps its stored value. Recorded in " +
            "the organization's audit log with the names of the fields changed. A key, an agent, a device " +
            "credential or the token of `ostrom login`, even an administrator's (it holds `guidance`, which " +
            "reads, never `inference:use`), is refused."
        parameters { organizationHeader() }
        body<PacingSettingsDto>("The settings to change; a field left out keeps its value.")
        responses {
            ok<PacingSettingsDto>("The settings as saved.")
            error(HttpStatusCode.Forbidden, STEP_UP)
        }
    }
}

/** The advice the controller gives an agent. */
private fun ApiCatalog.directiveOperations() {
    get("/api/v1/directives/preview") {
        operationId = "previewDirective"
        tag(TAG)
        summary = "The controller's current advice for the caller, also in observation mode."
        description = "(`capacity.pacing.v1`) Informational: the settings and the advice the controller would give " +
            "the caller now, whether or not it is delivered. Any member (`inference:use` or `guidance` on a key; " +
            "the token of `ostrom login` holds `guidance`), and an agent token. Answered with `Cache-Control: " +
            "no-store`."
        parameters { organizationHeader() }
        responses {
            ok<PacingPreviewDto>("The settings, and the advice when there is one.")
        }
    }
    get("/api/v1/directives/current") {
        operationId = "getCurrentDirective"
        tag(TAG)
        summary = "The caller's current capacity advice, member-wide."
        description = "(`capacity.pacing.v1`) TeamCode's agent guidance: `directive` is absent in observation " +
            "mode, before the controller's first pass, and once the advice expires (3 minutes after it was made; " +
            "the controller renews it every minute). The most constrained target the member's recent calls used " +
            "governs. A person, an agent token, or a key holding `guidance` (the token of `ostrom login` holds " +
            "it, for `ostrom agents hint|watch`). Answered with `Cache-Control: no-store`."
        parameters { organizationHeader() }
        responses {
            ok<DirectiveResponseDto>("The advice, absent when there is none.")
        }
    }
    post("/api/v1/directives/current") {
        operationId = "getSessionDirective"
        tag(TAG)
        summary = "The current capacity advice of one of the caller's agent sessions."
        description = "(`capacity.pacing.v1`) As `getCurrentDirective`, scoped to the session named (its own calls' " +
            "targets); an agent token only names its own session; a session id of another member reads nothing " +
            "of theirs. Answered with `Cache-Control: no-store`."
        parameters { organizationHeader() }
        body<DirectiveScopeDto>("The session.")
        responses {
            ok<DirectiveResponseDto>("The advice, absent when there is none.")
            badRequest("A malformed session id (`invalid_session`).")
            error(HttpStatusCode.Forbidden, "An agent token naming a session that is not its own (`forbidden`).")
        }
    }
}

/** The claim and the receipt of an advice delivered to an agent. */
private fun ApiCatalog.directiveDeliveryOperations() {
    post("/api/v1/directives/claim") {
        operationId = "claimDirective"
        tag(TAG)
        summary = "Claim the delivery of a session's new advice to its agent."
        description = "(`capacity.pacing.v1`) The CLI's hook asks before each prompt and tool call: the advice comes " +
            "back only when it changed since the last one delivered to that session at that stage, under a " +
            "claim of 15 seconds (two hooks at once never both deliver it); the hook then confirms with " +
            "`acknowledgeDirective`. Without a new advice, or in observation mode, `directive` is absent. " +
            "Stages: `received_by_cli`, `emitted_to_hook`. At most 2,000 deliveries remembered per member. " +
            "Answered with `Cache-Control: no-store`."
        parameters { organizationHeader() }
        body<DirectiveClaimRequestDto>("The caller's session and the stage of the delivery.")
        responses {
            ok<DirectiveClaimDto>("The advice and the claim on it, both absent when there is nothing new.")
            badRequest("An unknown stage (`invalid_stage`) or a malformed session id (`invalid_session`).")
            error(HttpStatusCode.Forbidden, "An agent token naming a session that is not its own (`forbidden`).")
        }
    }
    post("/api/v1/directives/receipt") {
        operationId = "acknowledgeDirective"
        tag(TAG)
        summary = "Record that an advice reached a stage of its delivery."
        description = "(`capacity.pacing.v1`) A receipt records the emission by the local bridge, never that the " +
            "agent followed the advice. Idempotent. With `claimId`, it settles that claim; an expired claim, or " +
            "one of another member or session, changes nothing. Without `sessionId`, the member-wide advice."
        parameters { organizationHeader() }
        body<DirectiveReceiptDto>("The advice, the stage reached, and the session and claim when there are some.")
        responses {
            empty("Recorded.")
            badRequest(
                "An unknown stage (`invalid_stage`), a malformed session id (`invalid_session`), or a session " +
                    "without its claim or a claim without its session (`invalid_claim`).",
            )
            error(HttpStatusCode.Forbidden, "An agent token naming a session that is not its own (`forbidden`).")
            conflict(
                "The advice has expired or been replaced (`directive_expired`), or the claim has " +
                    "(`claim_expired`).",
            )
        }
    }
}
