// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.presentation

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
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "secrets"

private const val A_403 = "Not an owner or an admin of the organization, not a browser session of a person, " +
    "or a credential without the scope (`forbidden`), or a sign-in older than 5 minutes (`step_up_required`); " +
    "sign in again, then send it again."

private const val BAD_VAR = "A variable name that is not uppercase letters, digits and underscores (at most " +
    "128) or that is reserved, a variant name outside 1 to 120 characters or with a control character, a value " +
    "outside 1 to 16384 characters or with a NUL, a shared variant without a value, or a value on a per-member " +
    "variant, or a binding that is neither `shared` nor `perMember` (`invalid_environment_variant`)."

private const val CREATE_RULES = "Names are unique per variable, case-insensitively. `shared` keeps one " +
    "encrypted value on the variant (required); `perMember` keeps none, each assignment carries the member's " +
    "own. The value is encrypted at rest with the secrets key (AES-256-GCM, bound to the organization and the " +
    "variant) and never returned. Names that steer the loader, a shell, a runtime, Git, SSH, a proxy or an " +
    "agent's tools are reserved and refused, as are the prefixes `LD_`, `DYLD_`, `OSTROM_`, `TEAMCODE_`, " +
    "`ANTHROPIC_`, `CLAUDE_`, `OPENAI_`, `CODEX_`, `XDG_`, `GIT_CONFIG_` and `BASH_FUNC_`; a variant stored " +
    "under a name reserved later is never served to an agent, and can still be unassigned or deleted."

private const val E_403 = "Not a browser session of a person, or a credential without the scope " +
    "(`forbidden`), or a sign-in older than 5 minutes (`step_up_required`); sign in again, then send it again."

private const val MEMBER_ID = "The member's id (`Member.id`)."

private const val MEMBER_NF = "No such organization or member for the caller (`not_found`)."

private const val ORG_NF = "The caller is not a member of an organization (`not_found`)."

private const val READ_ADMIN = "Not an owner or an admin of the organization, or a credential without the " +
    "scope `secrets:read` (`forbidden`)."

private const val STEP = "A write from the console: a browser session of a person (`forbidden`), signed in " +
    "less than 5 minutes ago (`step_up_required`); sign in again, then send it again."

private const val UPDATE_RULES = "`expectedVersion` is the version read: another version is refused, so two " +
    "writers never silently overwrite each other. Each change bumps `version` and reaches every assignee at " +
    "their next resolution (a running Claude Code within a minute, see `getEnvironment`). The variable and the " +
    "binding never change: create another variant. A value is for a shared variant only."

private const val VARIABLE = "The environment variable's name."

private const val VARIANT_ID = "The variant's id."

/**
 * The environment variables of the members' agents: the caller's own values, the organization's variants, their
 * assignments and exclusions for the owners and admins, and each member's personal variants.
 */
internal fun ApiCatalog.environmentOperations() {
    get("/api/v1/environment") {
        operationId = "getEnvironment"
        tag(TAG)
        summary = "The caller's own environment variables, with their values."
        description = "Every environment variant assigned to the caller, team or personal, that resolves " +
            "now. A variable that cannot be served (a value that does not decrypt) is withheld and listed in " +
            "`unavailable`, by name only; the others are still returned. `ostrom run claude` reads it before " +
            "starting the agent and again every 60 seconds while it runs. A person only: a browser session or a " +
            "token with `environment:read` (the CLI's); a service key, an agent or a device token is refused. A " +
            "member who is disabled gets none. `Cache-Control: no-store`; never journaled with its values."
        parameters {
            organizationHeader()
        }
        responses {
            ok<EnvironmentValuesResponse>("The values by variable name, and the names withheld.")
            error(HttpStatusCode.Forbidden, "A service key, an agent or a device token, or a credential " +
                "without `environment:read` (`forbidden`).")
            notFound(ORG_NF)
        }
    }
    variantListOperations()
    variantCreateOperations()
    variantChangeOperations()
    variantBulkOperations()
    assignmentListOperations()
    assignmentOperations()
    exclusionOperations()
    personalListOperations()
    personalChangeOperations()
}

/** Listing the organization's variants. */
private fun ApiCatalog.variantListOperations() {
    get("/api/v1/environment/variants") {
        operationId = "listEnvironmentVariants"
        tag(TAG)
        summary = "List the organization's environment variants."
        description = "Metadata only: never a value. At most 500, by variable then name. An owner or an " +
            "admin, scope `secrets:read`; personal variants are listed by `listPersonalEnvironmentVariants`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<EnvironmentVariantsPageResponse>("The team's variants.")
            error(HttpStatusCode.Forbidden, READ_ADMIN)
            notFound(ORG_NF)
        }
    }
    get("/api/v1/environment/variants/personal") {
        operationId = "listPersonalEnvironmentVariants"
        tag(TAG)
        summary = "List every member's personal environment variants."
        description = "By owner, at most 500, with `owner` and their definition (variable and name, none of " +
            "them secret) for review, and `hasValue`, never a value. An owner or an admin, scope " +
            "`secrets:read`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<EnvironmentVariantsPageResponse>("The members' personal variants.")
            error(HttpStatusCode.Forbidden, READ_ADMIN)
            notFound(ORG_NF)
        }
    }
}

/** Creating a team variant. */
private fun ApiCatalog.variantCreateOperations() {
    post("/api/v1/environment/variants") {
        operationId = "createEnvironmentVariant"
        tag(TAG)
        summary = "Create an environment variant."
        description = "A named value for one environment variable (its slot). Several variants can exist " +
            "for one variable (`org-a`, `org-b` for `GITHUB_TOKEN`). " +
            CREATE_RULES +
            " At most 500 team variants. An owner or an admin from the console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.variant.created`, without the value."
        parameters {
            organizationHeader()
        }
        body<EnvironmentVariantCreateRequest>("The variable, the variant's name, its binding (`shared` by " +
            "default) and, for a shared one, its value.")
        responses {
            ok<EnvironmentVariantResponse>("The variant, created, without its value.", HttpStatusCode.Created)
            badRequest(BAD_VAR)
            error(HttpStatusCode.Forbidden, A_403)
            notFound(ORG_NF)
            conflict("A variant with this name already exists for the variable (`variant_exists`), or the " +
                "organization has 500 team variants (`variant_limit`).")
        }
    }
}

/** Changing a team variant. */
private fun ApiCatalog.variantChangeOperations() {
    patch("/api/v1/environment/variants/{id}") {
        operationId = "updateEnvironmentVariant"
        tag(TAG)
        summary = "Rename a variant or replace its value."
        description = "Renames a team variant or replaces its shared value. " +
            UPDATE_RULES +
            " A personal variant is refused: only its owner edits it. An owner or an admin from the console, " +
            "scope `secrets:write`. " +
            STEP +
            " Journaled `environment.variant.updated`."
        parameters {
            organizationHeader()
            pathId("id", VARIANT_ID)
        }
        body<EnvironmentVariantUpdateRequest>("The version read, and the new name, the new value, or both.")
        responses {
            ok<EnvironmentVariantResponse>("The variant, changed, without its value.")
            badRequest("A name outside 1 to 120 characters or with a control character, or a value that is " +
                "out of its bounds or on a per-member variant (`invalid_environment_variant`).")
            error(HttpStatusCode.Forbidden, A_403)
            notFound("No such variant in the organization, or no organization for the caller (`not_found`).")
            conflict("`expectedVersion` is not the stored version (`variant_version_conflict`), the variant " +
                "is a member's personal one (`variant_personal`), or another variant of the variable has the " +
                "name (`variant_exists`).")
        }
    }
    delete("/api/v1/environment/variants/{id}") {
        operationId = "deleteEnvironmentVariant"
        tag(TAG)
        summary = "Delete a variant."
        description = "Team or personal (an administrator may remove a member's personal one), and its " +
            "assignments. An owner or an admin from the console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.variant.deleted`."
        parameters {
            organizationHeader()
            pathId("id", VARIANT_ID)
        }
        responses {
            empty("The variant is deleted.")
            error(HttpStatusCode.Forbidden, A_403)
            notFound("No such variant in the organization, or no organization for the caller (`not_found`).")
        }
    }
}

/** Assigning and promoting variants. */
private fun ApiCatalog.variantBulkOperations() {
    post("/api/v1/environment/variants/{id}/bulk-assign") {
        operationId = "bulkAssignEnvironmentVariant"
        tag(TAG)
        summary = "Assign a shared variant to every member."
        description = "Assigns a shared team variant to every member enabled now: rows marked `bulk`, " +
            "replacing earlier bulk selections of that variable, keeping direct ones and skipping excluded " +
            "members. An action, not a standing rule: members who join later get nothing until assigned. An " +
            "owner or an admin from the console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.variant.bulk_assigned`, and `environment.assigned` once per member changed."
        parameters {
            organizationHeader()
            pathId("id", VARIANT_ID)
        }
        responses {
            ok<EnvironmentBulkAssignmentResponse>("How many members were assigned, kept their direct " +
                "selection, or were excluded.")
            error(HttpStatusCode.Forbidden, A_403)
            notFound("No such variant in the organization, or no organization for the caller (`not_found`).")
            conflict("The variant is a member's personal one (`variant_personal`), or a per-member variant, " +
                "which needs each member's value (`variant_not_shared`).")
        }
    }
    post("/api/v1/environment/variants/{id}/promote") {
        operationId = "promoteEnvironmentVariant"
        tag(TAG)
        summary = "Make a personal variant a team variant."
        description = "The owner of the personal variant is cleared and keeps the selection; everyone else " +
            "gets it only by assignment. A team variant is returned as it is. An owner or an admin from the " +
            "console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.variant.promoted`, on a change only."
        parameters {
            organizationHeader()
            pathId("id", VARIANT_ID)
        }
        responses {
            ok<EnvironmentVariantResponse>("The variant, now a team variant.")
            error(HttpStatusCode.Forbidden, A_403)
            notFound("No such variant in the organization, or no organization for the caller (`not_found`).")
            conflict("A team variant has the same name for that variable (`variant_exists`).")
        }
    }
}

/** Reading the assignments and exclusions. */
private fun ApiCatalog.assignmentListOperations() {
    get("/api/v1/environment/assignments") {
        operationId = "listEnvironmentAssignments"
        tag(TAG)
        summary = "List the members' environment assignments and exclusions."
        description = "Every member's effective assignments and exclusions, metadata only, by member then " +
            "variable: at most 5000 of each, so a large organization asks one member at a time (`member`). An " +
            "owner or an admin, scope `secrets:read`."
        parameters {
            organizationHeader()
            queryText("member", "A member's id.")
        }
        responses {
            ok<EnvironmentAssignmentsPageResponse>("The assignments and the exclusions.")
            error(HttpStatusCode.Forbidden, READ_ADMIN)
            notFound("No such organization for the caller, or a `member` that is not an id (`not_found`).")
        }
    }
}

/** Assigning a variant to a member's variable. */
private fun ApiCatalog.assignmentOperations() {
    put("/api/v1/environment/assignments/{member}/{variable}") {
        operationId = "assignEnvironmentVariant"
        tag(TAG)
        summary = "Select a variant for a member's variable."
        description = "Selects a team variant for one member's variable, replacing any previous selection " +
            "of that variable (a member has at most one per variable). A per-member variant needs the member's " +
            "`value`; a shared one refuses it. A shared variant is never an implicit grant: a member receives a " +
            "variable only through an assignment. An owner or an admin from the console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.assigned` (subject: the member), never with the value."
        parameters {
            organizationHeader()
            pathId("member", MEMBER_ID)
            pathId("variable", VARIABLE)
        }
        body<EnvironmentAssignRequest>("The variant, and for a per-member variant the member's own value.")
        responses {
            ok<EnvironmentAssignmentResponse>("The assignment, without any value.")
            badRequest("A variant that fills another variable, a value on a shared variant, a value missing " +
                "or out of its bounds on a per-member one (`invalid_environment_variant`).")
            error(HttpStatusCode.Forbidden, A_403)
            notFound("No such variant, no such member in the organization, or no organization for the " +
                "caller (`not_found`).")
            conflict("The member is excluded from the variable (`variant_excluded`), or the variant is a " +
                "member's personal one (`variant_personal`).")
        }
    }
    delete("/api/v1/environment/assignments/{member}/{variable}") {
        operationId = "unassignEnvironmentVariant"
        tag(TAG)
        summary = "Remove a member's selection for a variable."
        description = "Nothing falls back to an earlier bulk value. 204 also when there was none. An owner " +
            "or an admin from the console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.unassigned`."
        parameters {
            organizationHeader()
            pathId("member", MEMBER_ID)
            pathId("variable", VARIABLE)
        }
        responses {
            empty("The selection is removed, or there was none.")
            badRequest("A variable name that is not uppercase letters, digits and underscores " +
                "(`invalid_environment_variant`).")
            error(HttpStatusCode.Forbidden, A_403)
            notFound(MEMBER_NF)
        }
    }
}

/** Excluding a member from a variable. */
private fun ApiCatalog.exclusionOperations() {
    put("/api/v1/environment/exclusions/{member}/{variable}") {
        operationId = "excludeEnvironmentVariable"
        tag(TAG)
        summary = "Exclude a member from a variable."
        description = "Removes their current selection, then refuses a direct assignment, and a bulk " +
            "assignment skips them, until the exclusion is removed. The member cannot declare a personal " +
            "variant for it either; a personal variant they already declared stays, unassigned, until they " +
            "delete it. An owner or an admin from the console, scope `secrets:write`. " +
            STEP +
            " Journaled `environment.excluded`."
        parameters {
            organizationHeader()
            pathId("member", MEMBER_ID)
            pathId("variable", VARIABLE)
        }
        responses {
            empty("The member is excluded from the variable.")
            badRequest("A variable name that is not uppercase letters, digits and underscores, or that is " +
                "reserved (`invalid_environment_variant`).")
            error(HttpStatusCode.Forbidden, A_403)
            notFound(MEMBER_NF)
        }
    }
    delete("/api/v1/environment/exclusions/{member}/{variable}") {
        operationId = "includeEnvironmentVariable"
        tag(TAG)
        summary = "Remove an exclusion."
        description = "It grants nothing back. An owner or an admin from the console, scope " +
            "`secrets:write`. " +
            STEP +
            " Journaled `environment.included`."
        parameters {
            organizationHeader()
            pathId("member", MEMBER_ID)
            pathId("variable", VARIABLE)
        }
        responses {
            empty("The exclusion is removed, or there was none.")
            badRequest("A variable name that is not uppercase letters, digits and underscores " +
                "(`invalid_environment_variant`).")
            error(HttpStatusCode.Forbidden, A_403)
            notFound(MEMBER_NF)
        }
    }
}

/** The caller's own personal variants. */
private fun ApiCatalog.personalListOperations() {
    get("/api/v1/me/environment/variants") {
        operationId = "listMyEnvironmentVariants"
        tag(TAG)
        summary = "List the caller's personal environment variants."
        description = "Metadata only. A person only: a service key, an agent or a device token is refused. " +
            "Scope `secrets:read`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<EnvironmentVariantsPageResponse>("The caller's personal variants.")
            error(HttpStatusCode.Forbidden, "A service key, an agent or a device token, or a credential " +
                "without `secrets:read` (`forbidden`).")
            notFound(ORG_NF)
        }
    }
    post("/api/v1/me/environment/variants") {
        operationId = "createMyEnvironmentVariant"
        tag(TAG)
        summary = "Declare a personal environment value."
        description = "A shared-binding variant owned by the caller and assigned to them alone, in one " +
            "transaction (`direct`). An administrator who later assigns a team variant for that variable " +
            "replaces it; the personal variant then stays unassigned until its owner deletes it. " +
            CREATE_RULES +
            " At most 20 personal variants per member; `binding` must be `shared`. A person from the console, " +
            "scope `secrets:write`. " +
            STEP +
            " Journaled `environment.variant.personal.created`."
        parameters {
            organizationHeader()
        }
        body<EnvironmentVariantCreateRequest>("The variable, the variant's name, its binding (`shared`) and " +
            "its value.")
        responses {
            ok<EnvironmentVariantResponse>("The variant, created, without its value.", HttpStatusCode.Created)
            badRequest(BAD_VAR)
            error(HttpStatusCode.Forbidden, E_403)
            notFound(ORG_NF)
            conflict("The caller already has a selection for the variable, an administrator's or their own " +
                "(`slot_taken`), an administrator excluded them from it (`variant_excluded`), they have 20 " +
                "personal variants (`variant_limit`), or they already have a variant of that name for the " +
                "variable (`variant_exists`).")
        }
    }
}

/** Changing the caller's own personal variants. */
private fun ApiCatalog.personalChangeOperations() {
    patch("/api/v1/me/environment/variants/{id}") {
        operationId = "updateMyEnvironmentVariant"
        tag(TAG)
        summary = "Rename a personal variant or replace its value."
        description = "One of the caller's own. " +
            UPDATE_RULES +
            " Another member's variant, or a team one, is not found. A person from the console, scope " +
            "`secrets:write`. " +
            STEP +
            " Journaled `environment.variant.personal.updated`."
        parameters {
            organizationHeader()
            pathId("id", VARIANT_ID)
        }
        body<EnvironmentVariantUpdateRequest>("The version read, and the new name, the new value, or both.")
        responses {
            ok<EnvironmentVariantResponse>("The variant, changed, without its value.")
            badRequest("A name outside 1 to 120 characters or with a control character, or a value outside " +
                "1 to 16384 characters or with a NUL (`invalid_environment_variant`).")
            error(HttpStatusCode.Forbidden, E_403)
            notFound("No such variant of the caller, or no organization for the caller (`not_found`).")
            conflict("`expectedVersion` is not the stored version (`variant_version_conflict`), or the " +
                "caller has another variant of that name for the variable (`variant_exists`).")
        }
    }
    delete("/api/v1/me/environment/variants/{id}") {
        operationId = "deleteMyEnvironmentVariant"
        tag(TAG)
        summary = "Delete a personal variant."
        description = "One of the caller's own, and its assignment. A person from the console, scope " +
            "`secrets:write`. " +
            STEP +
            " Journaled `environment.variant.personal.deleted`."
        parameters {
            organizationHeader()
            pathId("id", VARIANT_ID)
        }
        responses {
            empty("The variant is deleted.")
            error(HttpStatusCode.Forbidden, E_403)
            notFound("No such variant of the caller, or no organization for the caller (`not_found`).")
        }
    }
}
