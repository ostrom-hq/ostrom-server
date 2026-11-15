// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.presentation

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
import io.ktor.http.HttpStatusCode

private const val TAG = "delivery"
private const val RUNNERS_TAG = "runners"

private const val MINE = "The member themself, with a person's credential (the console, or the CLI's token, which " +
    "holds `skills:write` for writes and `skills:read` for reads); a service key or an agent token is refused " +
    "(403 `forbidden`). A skill that is not the caller's is 404."

private const val NOT_PERSON = "Not a person's credential: a service key or an agent token is refused (`forbidden`)."

private const val MY_SKILL_NF = "No such skill of the caller (`not_found`)."

private const val RUNNER_CONFLICT = "Another execution than the current one (`execution_fenced`), or the delivery " +
    "is unavailable (`delivery_unavailable`)."

private const val RUNNER_CHANNEL = "Only the runner of the current execution of a session that runs (`creating`, " +
    "`resuming`, `running`, `pausing`), with the execution's runner token as `Authorization: Bearer`, checked " +
    "before the body is read. `Cache-Control: no-store`."

/**
 * The skills module's operations for the members: their personal skills, what they receive and report installed,
 * and what a cloud session's runner installs for its owner.
 */
internal fun ApiCatalog.skillsMemberOperations() {
    personalSkillOperations()
    personalPinOperations()
    personalVersionOperations()
    deliveryOperations()
    installationOperations()
    runnerOperations()
}

/** The caller's personal skills: reading them, retiring and restoring. */
private fun ApiCatalog.personalSkillOperations() {
    get("/api/v1/me/skills") {
        operationId = "listMySkills"
        tag(TAG)
        summary = "The caller's personal skills."
        description = "$MINE At most 50, newest change first."
        parameters { organizationHeader() }
        responses {
            ok<SkillCatalogDto>("The caller's skills, newest change first.")
            error(HttpStatusCode.Forbidden, NOT_PERSON)
        }
    }
    delete("/api/v1/me/skills/{name}") {
        operationId = "retireMySkill"
        tag(TAG)
        summary = "Retire one of the caller's personal skills."
        description = "It is also unpinned. $MINE Idempotent."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The skill, retired.")
            error(HttpStatusCode.Forbidden, NOT_PERSON)
            notFound(MY_SKILL_NF)
        }
    }
    post("/api/v1/me/skills/{name}/restore") {
        operationId = "restoreMySkill"
        tag(TAG)
        summary = "Restore one of the caller's personal skills."
        description = "Nothing is pinned. $MINE Idempotent."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The skill, restored.")
            error(HttpStatusCode.Forbidden, NOT_PERSON)
            notFound(MY_SKILL_NF)
        }
    }
}

/** The version of a personal skill the caller receives. */
private fun ApiCatalog.personalPinOperations() {
    put("/api/v1/me/skills/{name}/pin") {
        operationId = "pinMySkill"
        tag(TAG)
        summary = "Pin the version of a personal skill the caller receives."
        description = MINE
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        body<PinSkillRequest>("The version to receive.")
        responses {
            ok<SkillEntryDto>("The skill, with its pin.")
            error(HttpStatusCode.Forbidden, NOT_PERSON)
            notFound("No such skill of the caller, or version of it (`not_found`).")
            conflict("The skill or the version is retired (`skill_retired`, `skill_version_retired`).")
        }
    }
    delete("/api/v1/me/skills/{name}/pin") {
        operationId = "unpinMySkill"
        tag(TAG)
        summary = "Unpin a personal skill."
        description = MINE
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The skill, with no pin.")
            error(HttpStatusCode.Forbidden, NOT_PERSON)
            notFound(MY_SKILL_NF)
        }
    }
}

/** The versions of a personal skill. */
private fun ApiCatalog.personalVersionOperations() {
    get("/api/v1/me/skills/{name}/versions") {
        operationId = "listMySkillVersions"
        tag(TAG)
        summary = "A personal skill's versions, newest first."
        description = MINE
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillDetailDto>("The skill and its versions.")
            error(HttpStatusCode.Forbidden, NOT_PERSON)
            notFound(MY_SKILL_NF)
        }
    }
    post("/api/v1/me/skills/{name}/versions") {
        operationId = "publishMySkillVersion"
        tag(TAG)
        summary = "Publish a version of a personal skill, delivered to the caller only."
        description = "$MINE Same body and answers as `publishSkillVersion`, without `allMembers` (400 " +
            "`invalid_skill_audience`). One namespace per organization: a name the team or another member uses " +
            "is 409 `name_taken`, naming nobody. At most 50 personal skills per member (409 `skill_limit`); " +
            "they do not count toward the team's 500. With `pin`, the owner approves the version for " +
            "themself. The body is bounded at 8 MiB, 5 MiB of content."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        body<PublishSkillVersionRequest>("The version label, its files (base64) and whether to pin it.")
        responses {
            ok<SkillPublicationDto>("Published.", HttpStatusCode.Created)
            ok<SkillPublicationDto>("Already published with the same digest (`created` is false).")
            badRequest(PUBLISH)
            error(HttpStatusCode.Forbidden, NOT_PERSON)
            conflict(PUBLISH_CONFLICT)
            error(HttpStatusCode.PayloadTooLarge, "The files hold more than 5 MiB (`skill_artifact_too_large`).")
        }
    }
}

/** What a member receives, and the files of a version. */
private fun ApiCatalog.deliveryOperations() {
    get("/api/v1/skill-delivery") {
        operationId = "getSkillDelivery"
        tag(TAG)
        summary = "The skills the caller receives."
        description = "The pinned versions their decisions or the audience allow. The member themself with a " +
            "person's credential (the console, the CLI's token) or a service key holding `skills:read` created " +
            "by them; evaluated at every request on the shared state. A skill is effective when it is active, " +
            "its pinned version is not retired, and the member's decision allows it (an explicit decision wins; " +
            "with none, the audience decides). A personal skill reaches its owner only."
        parameters { organizationHeader() }
        responses {
            ok<SkillManifestDto>("The member and the skills they receive.")
        }
    }
    get("/api/v1/members/{memberId}/skill-delivery") {
        operationId = "getMemberSkillDelivery"
        tag(TAG)
        summary = "The skills a member receives."
        description = "An owner or an admin of the organization, or the member themself (as `getSkillDelivery`)."
        parameters {
            organizationHeader()
            pathId("memberId", MEMBER_ID)
        }
        responses {
            ok<SkillManifestDto>("The member and the skills they receive.")
            error(HttpStatusCode.Forbidden, "Neither an owner or an admin nor the member themself (`forbidden`).")
            notFound(MEMBER_NF)
        }
    }
    get("/api/v1/skill-delivery/{name}/{version}") {
        operationId = "downloadSkill"
        tag(TAG)
        summary = "The files of a skill version the caller receives."
        description = "The member themself with a person's credential (the console, the CLI's token) or a service " +
            "key holding `skills:read` created by them; only the currently effective pinned version is served: " +
            "any other version, or a skill the caller no longer receives, is 404. The server recomputes the " +
            "digest from the stored files and refuses to serve a mismatch."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
            pathId("version", SKILL_VERSION)
        }
        responses {
            ok<SkillArtifactDto>("The version and its files, sorted by path.")
            notFound("Not the currently effective pinned version of a skill the caller receives (`not_found`).")
            error(HttpStatusCode.InternalServerError, INTEGRITY)
        }
    }
}

/** What the workstations report installed. */
private fun ApiCatalog.installationOperations() {
    get("/api/v1/skill-delivery/installations") {
        operationId = "listMySkillInstallations"
        tag(TAG)
        summary = "The installation status the caller's workstations reported."
        description = "The member themself, with a person's credential."
        parameters { organizationHeader() }
        responses {
            ok<SkillInstallationListDto>("The reports of the caller's workstations.")
        }
    }
    put("/api/v1/skill-delivery/installations") {
        operationId = "reportSkillInstallations"
        tag(TAG)
        summary = "Report the skills installed on this workstation."
        description = "The CLI's token only: the status is stored for the workstation it signs in. Up to 500 " +
            "skills, each once, named in the catalog; a reported version must be published for that skill, with " +
            "a matching digest when one is given. A status never grants nor revokes access, and is kept when " +
            "access is removed; signing the workstation out deletes its reports. Not audited. The body is " +
            "bounded at 8 MiB."
        parameters { organizationHeader() }
        body<ReportSkillInstallationsRequest>("What the workstation installed, per skill, version and status.")
        responses {
            empty("Stored.")
            badRequest(
                "A version not published for the skill, or a digest that does not match (" +
                    "`invalid_skill_installation`).",
            )
            notFound("A reported skill is not in the catalog (`not_found`).")
            conflict("Not the CLI's token, which names a workstation (`device_required`).")
        }
    }
    get("/api/v1/members/{memberId}/skill-installations") {
        operationId = "listMemberSkillInstallations"
        tag(TAG)
        summary = "The installation status a member's workstations reported."
        description = "$ADMIN At most 1 000 rows, newest first."
        parameters {
            organizationHeader()
            pathId("memberId", MEMBER_ID)
        }
        responses {
            ok<SkillInstallationListDto>("The reports, newest first.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(MEMBER_NF)
        }
    }
}

/** What a cloud session's runner installs for the session's owner. */
private fun ApiCatalog.runnerOperations() {
    post("/api/v1/cloud/runner/skills") {
        operationId = "fetchRunnerSkills"
        tag(RUNNERS_TAG)
        summary = "The runner of a cloud session reads the skills its owner receives."
        description = "(`runners.cloud.skills.v1`) At every start of the agent, to install them in the agent's " +
            "personal skills folder under the agent's own UID: the same manifest as `getSkillDelivery` for that " +
            "member (team skills their decisions or the audience allow, and their own personal skills), " +
            "resolved at every call. A disabled owner receives none. $RUNNER_CHANNEL"
        parameters { organizationHeader() }
        body<RunnerExecutionDto>("The session and the epoch of the execution the runner belongs to.")
        responses {
            ok<SkillManifestDto>("The owner's member id and the skills they receive.")
            conflict(RUNNER_CONFLICT)
        }
    }
    post("/api/v1/cloud/runner/skills/artifact") {
        operationId = "downloadRunnerSkill"
        tag(RUNNERS_TAG)
        summary = "The files of a skill version the session's owner receives."
        description = "(`runners.cloud.skills.v1`) As `downloadSkill`: only the currently effective pinned version " +
            "is served, its digest recomputed from the stored files. The runner verifies it again before " +
            "installing. $RUNNER_CHANNEL"
        parameters { organizationHeader() }
        body<RunnerArtifactRequestDto>("The session and epoch of the execution, and the skill and version wanted.")
        responses {
            ok<SkillArtifactDto>("The version and its files, sorted by path.")
            notFound("Not the currently effective pinned version of a skill the owner receives (`not_found`).")
            conflict(RUNNER_CONFLICT)
            error(HttpStatusCode.InternalServerError, INTEGRITY)
        }
    }
}
