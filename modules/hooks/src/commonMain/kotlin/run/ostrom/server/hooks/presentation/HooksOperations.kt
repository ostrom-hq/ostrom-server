// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.presentation

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
import run.ostrom.server.skills.presentation.AssignmentDto
import run.ostrom.server.skills.presentation.AssignmentListDto
import run.ostrom.server.skills.presentation.PinSkillRequest
import run.ostrom.server.skills.presentation.RunnerArtifactRequestDto
import run.ostrom.server.skills.presentation.RunnerExecutionDto
import run.ostrom.server.skills.presentation.SetAssignmentRequest
import run.ostrom.server.skills.presentation.SetAudienceRequest
import io.ktor.http.HttpStatusCode

private const val TAG = "delivery"
private const val RUNNERS_TAG = "runners"

private const val ADMIN = "Not an owner or an admin of the organization signed in to the console: a token " +
    "or a service key is refused (`forbidden`)."

private const val BAD_VERSION = "An invalid hook name (`invalid_hook_name`), version label " +
    "(`invalid_hook_version`), definition (`invalid_hook_definition`), file path (`invalid_hook_path`) or " +
    "artifact (`invalid_hook_artifact`)."

private const val HK_NAME = "The hook's name: 1 to 64 lowercase letters, digits and single hyphens."

private const val HK_NF = "No such hook, or version of it, in the caller's organization (`not_found`)."

private const val HK_VER = "A version label: 1 to 64 characters `[0-9A-Za-z][0-9A-Za-z.+_-]*`, " +
    "case-sensitive."

private const val INTEGRITY = "The stored files no longer match the published digest (`hook_integrity`)."

private const val MEMBER_NF = "No such member in the caller's organization (`not_found`)."

/**
 * The hooks module's operations: the team hooks an organization publishes, pins and assigns, what each member
 * receives, and what a cloud session's runner installs for its owner. The answers are the module's own types.
 */
internal fun ApiCatalog.hooksOperations() {
    tag(TAG, "The hooks, skills and other artifacts an organization delivers to its members' computers: " +
        "publishing, pinning, assigning and downloading them.")
    catalogOperations()
    catalogOperations2()
    pinOperations()
    pinOperations2()
    deliveryOperations()
    deliveryOperations2()
}

/** Publishing hook versions and reading the catalog. */
private fun ApiCatalog.catalogOperations() {
    get("/api/v1/hooks") {
        operationId = "listHooks"
        tag(TAG)
        summary = "The organization's team hooks."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`). At most 100, newest change first."
        parameters {
            organizationHeader()
        }
        responses {
            ok<HookCatalogDto>("The hooks, newest change first.")
            error(HttpStatusCode.Forbidden, ADMIN)
        }
    }
    get("/api/v1/hooks/{name}/versions") {
        operationId = "listHookVersions"
        tag(TAG)
        summary = "A hook's versions with their definitions, newest first."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`)."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
        }
        responses {
            ok<HookDetailDto>("The hook and its versions.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
}

/** Publishing hook versions and reading the catalog. */
private fun ApiCatalog.catalogOperations2() {
    post("/api/v1/hooks/{name}/versions") {
        operationId = "publishHookVersion"
        tag(TAG)
        summary = "Publish an immutable hook version."
        description = "From CI or by an administrator; it never pins. An owner or an admin from the " +
            "console, or a service key with `hooks:publish` created by an owner or an admin (checked again at " +
            "every call). A hook executes code on members' computers, so publication never delivers: only an " +
            "administrator pins, from the console. 201 for a new version, 200 with `created: false` for the " +
            "same label and digest. `allMembers` sets the audience to all members: always for an administrator, " +
            "and for a key only while the hook has no pinned version or is already for all members (403 " +
            "`hook_audience_requires_administrator` otherwise, nothing stored). 409 `hook_version_conflict`, " +
            "`hook_limit` (100 hooks), `hook_version_limit` (200 versions). 400 `invalid_hook_name`, " +
            "`invalid_hook_version`, `invalid_hook_definition` (events among PreToolUse, PostToolUse, " +
            "UserPromptSubmit, Stop, SubagentStop, SessionStart, SessionEnd, PreCompact, Notification, each " +
            "once; a matcher of 1 to 256 characters without control characters; a timeout of 1 to 600 seconds; " +
            "`command` an executable file of the artifact), `invalid_hook_path`, `invalid_hook_artifact` (1 to " +
            "50 files). 413 `hook_artifact_too_large` above 1 MiB (the body is bounded at 2 MiB). 403 " +
            "`credential_scope` for a key without `hooks:publish`."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
        }
        body<PublishHookVersionRequest>("The version label, the hook's definition, its files (1 to 50, 1 " +
            "MiB in all, base64) and whether it is for all members.")
        responses {
            ok<HookPublicationDto>("Published.", HttpStatusCode.Created)
            ok<HookPublicationDto>("Already published with the same label and digest (`created` is false).")
            badRequest(BAD_VERSION)
            error(HttpStatusCode.Forbidden, "A key without `hooks:publish` (`credential_scope`), or one " +
                "that asks for all members of a pinned hook (`hook_audience_requires_administrator`).")
            conflict("The label is published with other files (`hook_version_conflict`), or the " +
                "organization holds 100 hooks (`hook_limit`) or the hook 200 versions (`hook_version_limit`).")
            error(HttpStatusCode.PayloadTooLarge, "The files hold more than 1 MiB " +
                "(`hook_artifact_too_large`).")
        }
    }
    get("/api/v1/hooks/{name}/versions/{version}") {
        operationId = "readHookVersion"
        tag(TAG)
        summary = "Read a version's definition and files, pinned or not."
        description = "Verified against its digest. An owner or an admin of the organization, from the " +
            "console (a browser session); every token and service key is refused (403 `forbidden`). So an " +
            "administrator reads the code before pinning it. 500 `hook_integrity` on a mismatch."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
            pathId("version", HK_VER)
        }
        responses {
            ok<HookArtifactDto>("The version, its definition and its files.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
            error(HttpStatusCode.InternalServerError, INTEGRITY)
        }
    }
}

/** Pinning a hook, its audience and its members' decisions. */
private fun ApiCatalog.pinOperations() {
    put("/api/v1/hooks/{name}/pin") {
        operationId = "pinHook"
        tag(TAG)
        summary = "Pin the delivered version."
        description = "An update or a rollback. An owner or an admin of the organization, from the console " +
            "(a browser session); every token and service key is refused (403 `forbidden`). No key ever pins a " +
            "hook. Idempotent; audited `hook.pinned`."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
        }
        body<PinSkillRequest>("The version to deliver.")
        responses {
            ok<HookEntryDto>("The hook, with its pinned version.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
    delete("/api/v1/hooks/{name}/pin") {
        operationId = "unpinHook"
        tag(TAG)
        summary = "Unpin the hook: stop delivering it."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`). Idempotent."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
        }
        responses {
            ok<HookEntryDto>("The hook, without a pinned version.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
    put("/api/v1/hooks/{name}/audience") {
        operationId = "setHookAudience"
        tag(TAG)
        summary = "Deliver to all members, or only to members set to yes."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`). An explicit `deny` still excludes; each " +
            "member still approves each version on their computer."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
        }
        body<SetAudienceRequest>("Whether the hook is for all members.")
        responses {
            ok<HookEntryDto>("The hook, with its audience.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
}

/** Pinning a hook, its audience and its members' decisions. */
private fun ApiCatalog.pinOperations2() {
    get("/api/v1/hooks/{name}/assignments") {
        operationId = "listHookAssignments"
        tag(TAG)
        summary = "The members' explicit decisions on a hook."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`)."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
        }
        responses {
            ok<AssignmentListDto>("The decisions.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
    put("/api/v1/hooks/{name}/assignments/{memberId}") {
        operationId = "setHookAssignment"
        tag(TAG)
        summary = "Set a member's decision: allow or deny."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`). Idempotent; audited " +
            "`hook.access.allowed` or `hook.access.denied`."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
            pathId("memberId", "The member's id.")
        }
        body<SetAssignmentRequest>("The decision: `allow` or `deny`.")
        responses {
            ok<AssignmentDto>("The member's decision.")
            badRequest("A decision that is neither `allow` nor `deny` (`invalid_hook_assignment`).")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
    delete("/api/v1/hooks/{name}/assignments/{memberId}") {
        operationId = "clearHookAssignment"
        tag(TAG)
        summary = "Clear a member's decision."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`). Idempotent."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
            pathId("memberId", "The member's id.")
        }
        responses {
            empty("The member's decision is cleared.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(HK_NF)
        }
    }
}

/** What a member receives, and what a cloud session's runner installs. */
private fun ApiCatalog.deliveryOperations() {
    get("/api/v1/hook-delivery") {
        operationId = "getHookDelivery"
        tag(TAG)
        summary = "The hooks the caller receives."
        description = "The pinned versions the caller's decisions or the audience allow. The member " +
            "themself with a person's credential (the console, the CLI's token) or a service key holding " +
            "`hooks:read` created by them; the CLI installs only the versions the member approved on their " +
            "computer."
        parameters {
            organizationHeader()
        }
        responses {
            ok<HookManifestDto>("The caller's member id and the hooks they receive.")
        }
    }
    get("/api/v1/members/{memberId}/hook-delivery") {
        operationId = "getMemberHookDelivery"
        tag(TAG)
        summary = "The hooks a member receives."
        description = "An owner or an admin of the organization, from the console (a browser session); " +
            "every token and service key is refused (403 `forbidden`). 404 for a member of another " +
            "organization."
        parameters {
            organizationHeader()
            pathId("memberId", "The member's id.")
        }
        responses {
            ok<HookManifestDto>("The member's id and the hooks they receive.")
            error(HttpStatusCode.Forbidden, ADMIN)
            notFound(MEMBER_NF)
        }
    }
    get("/api/v1/hook-delivery/{name}/{version}") {
        operationId = "downloadHook"
        tag(TAG)
        summary = "The definition and files of a hook version the caller receives."
        description = "The member themself with a person's credential (the console, the CLI's token) or a " +
            "service key holding `hooks:read` created by them; only the currently effective pinned version; 404 " +
            "otherwise. 500 `hook_integrity` on a mismatch."
        parameters {
            organizationHeader()
            pathId("name", HK_NAME)
            pathId("version", HK_VER)
        }
        responses {
            ok<HookArtifactDto>("The version, its definition and its files.")
            notFound("No such hook, or a version that is not the one currently delivered to the caller " +
                "(`not_found`).")
            error(HttpStatusCode.InternalServerError, INTEGRITY)
        }
    }
}

/** What a member receives, and what a cloud session's runner installs. */
private fun ApiCatalog.deliveryOperations2() {
    post("/api/v1/cloud/runner/hooks") {
        operationId = "fetchRunnerHooks"
        tag(RUNNERS_TAG)
        summary = "The runner of a cloud session reads the team hooks its owner receives."
        parameters {
            organizationHeader()
        }
        body<RunnerExecutionDto>("The session and the epoch of the execution the runner belongs to.")
        responses {
            ok<HookManifestDto>("The owner's member id and the hooks they receive; `Cache-Control: " +
                "no-store`.")
            conflict("Another execution (`execution_fenced`), or the delivery is unavailable " +
                "(`delivery_unavailable`).")
        }
    }
    post("/api/v1/cloud/runner/hooks/artifact") {
        operationId = "downloadRunnerHook"
        tag(RUNNERS_TAG)
        summary = "The files and definition of a hook version a cloud session's owner receives."
        parameters {
            organizationHeader()
        }
        body<RunnerArtifactRequestDto>("The session and epoch of the execution, and the hook and version " +
            "wanted.")
        responses {
            ok<HookArtifactDto>("The version, its definition and its files; `Cache-Control: no-store`.")
            notFound("Not the currently effective pinned version of a hook the owner receives " +
                "(`not_found`).")
            conflict("Another execution (`execution_fenced`), or the delivery is unavailable " +
                "(`delivery_unavailable`).")
            error(HttpStatusCode.InternalServerError, INTEGRITY)
        }
    }
}
