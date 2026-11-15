// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.presentation

import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val RULES_TAG = "rules"
private const val ANALYSIS_TAG = "analysis"
private const val APPROVALS_TAG = "approvals"
private const val GATES_TAG = "gates"

private const val BAD_RULE = "A rule that breaks a bound, or an unknown strength or status " +
    "(`invalid_request`)."

private const val BUSY = "A run of the same kind is already going, or a replay ran less than a minute ago " +
    "(`conflict`)."

private const val CONF = "The person already has 10 governance confirmations waiting " +
    "(`confirmation_limit`); expired, refused and answered ones do not count."

private const val CONSOLE = "Not an owner or an admin signed in to the console, or holding the elevated " +
    "token of `ostrom login --admin` (`forbidden`)."

private const val PENDING = "Not applied: made with a person's API token (a personal token, the CLI's " +
    "included), the write opened a confirmation that an owner or an admin confirms on the console within 15 " +
    "minutes (`getGovernanceConfirmation`)."

private const val RULE_ID = "The rule's id."

private const val RULE_NF = "No such rule in the caller's organization (`not_found`)."

private const val STARTED = "The run is started: its id, to follow with `getAnalysisState`."

private const val WRITER = "Not an owner or an admin of the organization, or an agent or a device, or a " +
    "token the write is not open to (`forbidden`)."

/**
 * The rules module's operations: the rules, the decisions that signed them, the self-approval policy, and the
 * analysis that finds rules. The answers are the module's own types.
 */
internal fun ApiCatalog.rulesOperations() {
    tag(RULES_TAG, "The rules of an organization, found by the analysis or written by its people, and the " +
        "signed decisions that accept, edit or reject them.")
    tag(ANALYSIS_TAG, "The analysis of an organization's repositories that finds rules and workflows, and " +
        "the replay of its pull requests under them.")
    ruleOperations()
    ruleOperations2()
    ruleOperations3()
    decisionOperations()
    analysisOperations()
    analysisOperations2()
}

/** Reading, writing and deciding rules. */
private fun ApiCatalog.ruleOperations() {
    get("/api/v1/rules") {
        operationId = "listRules"
        tag(RULES_TAG)
        summary = "Rules, by group then id, a page at a time."
        description = "Every filter applies before the page, so a repository's rules are never cut by the " +
            "others'. Callers: a member, a token with `rules:read`, or a service key of the organization with " +
            "`rules:read` (its CI), which reads its own organization's rules whatever `Ostrom-Org` names. Each " +
            "call of a key is recorded in the organization's audit log, in the key's name (`service_key.used`)."
        parameters {
            organizationHeader()
            queryText("repo", "Only the rules that cover this repository.")
            queryText("status", "Only the rules of this status.")
            queryText("group", "Only the rules of this group.")
            queryText("after", "The `next` of the previous page (opaque); 400 when no page gave it.")
            queryLimit("The page size, 1 to 1000 (1000 by default); a value outside is brought " +
                "into it.", maximum = 1000)
        }
        responses {
            ok<RulesPage>("A page of rules, `next` set when another follows.")
            badRequest("An unknown status, or a cursor no page gave (`invalid_request`).")
        }
    }
    post("/api/v1/rules") {
        operationId = "createRule"
        tag(RULES_TAG)
        summary = "Write a rule."
        description = "A signed decision. An owner or an admin only; with a person's token the write is " +
            "held until it is confirmed on the console."
        parameters {
            organizationHeader()
        }
        body<NewRuleRequest>("The rule: its statement and group, and optionally its output, scope, " +
            "strength, reason, status, review date and gate.")
        responses {
            ok<RuleDto>("The rule, written.", HttpStatusCode.Created)
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest(BAD_RULE)
            error(HttpStatusCode.Forbidden, WRITER)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
}

/** Reading, writing and deciding rules. */
private fun ApiCatalog.ruleOperations2() {
    get("/api/v1/rules/{id}") {
        operationId = "getRule"
        tag(RULES_TAG)
        summary = "A rule."
        description = "Callers: a member, a token with `rules:read`, or a service key of the organization " +
            "with `rules:read` (its CI), which reads its own organization's rules whatever `Ostrom-Org` names. " +
            "Each call of a key is recorded in the organization's audit log, in the key's name " +
            "(`service_key.used`)."
        parameters {
            organizationHeader()
            pathId("id", RULE_ID)
        }
        responses {
            ok<RuleDto>("The rule.")
            notFound(RULE_NF)
        }
    }
    patch("/api/v1/rules/{id}") {
        operationId = "updateRule"
        tag(RULES_TAG)
        summary = "Decide or edit a rule."
        description = "A signed decision. A field left out stays as it is; `gate` set to JSON null removes " +
            "the rule's gate. An owner or an admin only; with a person's token the write is held until it is " +
            "confirmed on the console."
        parameters {
            organizationHeader()
            pathId("id", RULE_ID)
        }
        body<RulePatchRequest>("The fields to change, each only when present; `gate` set to null removes the gate.")
        responses {
            ok<RuleDto>("The rule, changed.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest(BAD_RULE)
            error(HttpStatusCode.Forbidden, WRITER)
            notFound(RULE_NF)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
}

/** Reading, writing and deciding rules. */
private fun ApiCatalog.ruleOperations3() {
    delete("/api/v1/rules/{id}") {
        operationId = "deleteRule"
        tag(RULES_TAG)
        summary = "Delete a rule."
        description = "A signed decision. An owner or an admin only; with a person's token the write is " +
            "held until it is confirmed on the console."
        parameters {
            organizationHeader()
            pathId("id", RULE_ID)
        }
        responses {
            empty("The rule is deleted.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            error(HttpStatusCode.Forbidden, WRITER)
            notFound(RULE_NF)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
    post("/api/v1/rules/bulk") {
        operationId = "bulkUpdateRules"
        tag(RULES_TAG)
        summary = "Decide several rules."
        description = "Sets one status on up to 200 rules at once; a rule is edited one at a time. An owner " +
            "or an admin only; with a person's token the write is held until it is confirmed on the console."
        parameters {
            organizationHeader()
        }
        body<BulkRulesRequest>("The ids of the rules, and the status to give them.")
        responses {
            ok<BulkRulesResponse>("How many rules were updated.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest("More than 200 ids, or an unknown status, or `edited` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, WRITER)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
}

/** The signed decisions and the self-approval policy. */
private fun ApiCatalog.decisionOperations() {
    get("/api/v1/decisions") {
        operationId = "listDecisions"
        tag(APPROVALS_TAG)
        summary = "Signed decisions."
        description = "The organization's signed decisions on its rules and its policy, newest first. Scope " +
            "`rules:read`."
        parameters {
            organizationHeader()
            queryText("subjectKind", "Only decisions on this kind of subject: `rule` or `policy`.")
            queryText("subjectId", "Only decisions on this subject.")
            queryText("before", "The cursor of the next page (`next` of the previous one).")
        }
        responses {
            ok<DecisionsPage>("A page of decisions, `next` set when another follows.")
            badRequest("An unknown subject kind or cursor (`invalid_request`).")
        }
    }
    get("/api/v1/gates/policy") {
        operationId = "getGatePolicy"
        tag(GATES_TAG)
        summary = "Self-approval policy."
        description = "`never` for an organization that never chose: a yes always comes from another person. " +
            "`sole-approver`, which lets the gate's only approver approve their own action, marked " +
            "self-approved in the receipt, applies only once an owner or an admin sets it. Scope `rules:read` or " +
            "`gates:read`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<GatePolicyDto>("The policy.")
        }
    }
    put("/api/v1/gates/policy") {
        operationId = "setGatePolicy"
        tag(GATES_TAG)
        summary = "Set the self-approval policy."
        description = "`sole-approver` or `never`, as a signed decision. An owner or an admin only; with a " +
            "person's token the write is held until it is confirmed on the console."
        parameters {
            organizationHeader()
        }
        body<GatePolicyDto>("The policy: `sole-approver` or `never`.")
        responses {
            ok<GatePolicyDto>("The policy, set.")
            ok<GovernanceConfirmationPending>(PENDING, HttpStatusCode.Accepted)
            badRequest("A policy that is neither `sole-approver` nor `never` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, WRITER)
            error(HttpStatusCode.TooManyRequests, CONF)
        }
    }
}

/** Starting, reading and replaying the analysis. */
private fun ApiCatalog.analysisOperations() {
    post("/api/v1/analysis/start") {
        operationId = "startAnalysis"
        tag(ANALYSIS_TAG)
        summary = "Analyse the organization."
        description = "Finds its rules and workflows. A browser session of an owner or an admin only (403 " +
            "otherwise); 202 with the id of the run. 409 when a run of the same kind is already going. It reads " +
            "every repository and spends on the organization's model."
        parameters {
            organizationHeader()
        }
        responses {
            ok<AnalysisStarted>(STARTED, HttpStatusCode.Accepted)
            error(HttpStatusCode.Forbidden, CONSOLE)
            conflict(BUSY)
        }
    }
    post("/api/v1/analysis/restart") {
        operationId = "restartAnalysis"
        tag(ANALYSIS_TAG)
        summary = "Start the analysis again, from a fresh run."
        description = "A browser session of an owner or an admin only (403 otherwise); 202 with a fresh " +
            "run, whether or not the last one failed; its id. 409 when a run of the same kind is already going. " +
            "It reads every repository and spends on the organization's model."
        parameters {
            organizationHeader()
        }
        responses {
            ok<AnalysisStarted>(STARTED, HttpStatusCode.Accepted)
            error(HttpStatusCode.Forbidden, CONSOLE)
            conflict(BUSY)
        }
    }
    get("/api/v1/analysis/state") {
        operationId = "getAnalysisState"
        tag(ANALYSIS_TAG)
        summary = "Snapshot of the analysis."
        description = "Scope `rules:read`. `phase` and `progress` are absent before the first analysis; " +
            "`failure` is `{stage, reason, at, retryPath}` when the last one failed."
        parameters {
            organizationHeader()
        }
        responses {
            ok<AnalysisStateDto>("The last analysis: its phase and progress, the repositories it read, the " +
                "rules and workflows.")
        }
    }
}

/** Starting, reading and replaying the analysis. */
private fun ApiCatalog.analysisOperations2() {
    post("/api/v1/analysis/replay") {
        operationId = "startReplay"
        tag(ANALYSIS_TAG)
        summary = "Replay the last 30 days of pull requests under the rules."
        description = "A simulation. A browser session of an owner or an admin only (403 otherwise); 202 " +
            "with the id of the run. 409 when a run of the same kind is already going. It spends on the " +
            "organization's model."
        parameters {
            organizationHeader()
        }
        responses {
            ok<AnalysisStarted>(STARTED, HttpStatusCode.Accepted)
            error(HttpStatusCode.Forbidden, CONSOLE)
            conflict(BUSY)
        }
    }
    get("/api/v1/analysis/replay") {
        operationId = "getReplay"
        tag(ANALYSIS_TAG)
        summary = "Replay results."
        description = "Scope `rules:read`: what the last replay found, one object per replayed pull " +
            "request."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ReplayPage>("What the last replay found, one object per replayed pull request.")
        }
    }
}
