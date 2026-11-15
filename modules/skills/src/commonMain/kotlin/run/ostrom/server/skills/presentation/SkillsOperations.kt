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
/** Declared by the runners module, whose catalogue owns the runner's calls. */
private const val RUNNERS_TAG = "runners"

internal const val ADMIN = "An owner or an admin of the organization, from the console (a browser session); every " +
    "token and service key is refused (403 `forbidden`)."

private const val PERSONAL = "A member's personal skill is refused with 409 `skill_personal`: promote it first."

internal const val NOT_ADMIN = "Not an owner or an admin signed in to the console: a token or a service key is " +
    "refused (`forbidden`)."

internal const val SKILL_NAME = "The skill's name: 1 to 64 lowercase letters, digits and single hyphens."

internal const val SKILL_VERSION = "A version label: 1 to 64 characters `[0-9A-Za-z][0-9A-Za-z.+_-]*`, " +
    "case-sensitive."

private const val SKILL_NF = "No such skill in the organization (`not_found`)."

private const val PERSONAL_CONFLICT = "The skill is a member's personal one (`skill_personal`)."

internal const val MEMBER_ID = "The member's id."

internal const val MEMBER_NF = "No such member in the caller's organization (`not_found`)."

internal const val PUBLISH = "An invalid skill name (`invalid_skill_name`), version label (`invalid_skill_version`), " +
    "file path (`invalid_skill_path`) or artifact (`invalid_skill_artifact`: 1 to 200 files, `SKILL.md` at the " +
    "root, unique paths ignoring case, no path both a file and a directory, mode `file` or `executable`, " +
    "standard padded base64), or `allMembers` on a personal skill (`invalid_skill_audience`)."

internal const val PUBLISH_CONFLICT = "The label holds other content (`skill_version_conflict`), the version or the " +
    "skill is retired (`skill_version_retired`, `skill_retired`), a member's personal skill holds the name " +
    "(`name_taken`), or the catalog is full (`skill_limit`, 500 skills; `skill_version_limit`, 200 versions)."

internal const val INTEGRITY = "The stored files no longer match the published digest (`skill_integrity`)."

/**
 * The skills module's operations: the catalog of team skills an organization publishes, pins and assigns, the
 * members' personal skills, what each member receives and reports installed, and what a cloud session's runner
 * installs for its owner. The answers are the module's own types.
 */
internal fun ApiCatalog.skillsOperations() {
    catalogOperations()
    versionOperations()
    versionRetirementOperations()
    pinOperations()
    assignmentOperations()
    promotionOperations()
    skillsMemberOperations()
}

/** The team catalog: reading it, removing a skill and restoring one. */
private fun ApiCatalog.catalogOperations() {
    get("/api/v1/skills") {
        operationId = "listSkills"
        tag(TAG)
        summary = "The organization's skill catalog."
        description = "$ADMIN Every skill of the organization, team and personal (`ownerId`), newest change first, " +
            "at most 550."
        parameters { organizationHeader() }
        responses {
            ok<SkillCatalogDto>("The skills, newest change first.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
        }
    }
    delete("/api/v1/skills/{name}") {
        operationId = "retireSkill"
        tag(TAG)
        summary = "Remove a skill from the catalog."
        description = "Unpinned, delivered to nobody, closed to publication. $ADMIN Versions and decisions remain; " +
            "`restoreSkill` reopens publication without pinning anything. Idempotent: `skill.retired` is audited " +
            "only when the skill was active. $PERSONAL"
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The skill, retired.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
            conflict(PERSONAL_CONFLICT)
        }
    }
    post("/api/v1/skills/{name}/restore") {
        operationId = "restoreSkill"
        tag(TAG)
        summary = "Restore a removed skill."
        description = "Nothing is pinned. $ADMIN Idempotent; audited `skill.restored` when the skill was retired. " +
            PERSONAL
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The skill, restored.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
            conflict(PERSONAL_CONFLICT)
        }
    }
}

/** A team skill's versions: listing and publishing. */
private fun ApiCatalog.versionOperations() {
    get("/api/v1/skills/{name}/versions") {
        operationId = "listSkillVersions"
        tag(TAG)
        summary = "A skill's versions, newest first."
        description = "$ADMIN At most 200 versions. A personal skill's versions are readable too, as metadata."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillDetailDto>("The skill and its versions.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
        }
    }
    post("/api/v1/skills/{name}/versions") {
        operationId = "publishSkillVersion"
        tag(TAG)
        summary = "Publish an immutable skill version."
        description = "From CI or by an administrator. An owner or an admin from the console, or a service key with " +
            "`skills:publish` created by an owner or an admin (the creator's role is checked again at every " +
            "call). The first publication creates the skill. `pin` also pins the version in the same " +
            "transaction (a key needs `skills:pin` besides); `allMembers` delivers the skill to every member " +
            "without an explicit decision (a key needs `skills:assign`); without them nothing changes what " +
            "members receive. 201 for a new version; 200 with `created: false` when the label exists with the " +
            "same digest (a CI retry: nothing is audited, `pin` and `allMembers` still converge). The body is " +
            "bounded at 8 MiB, 5 MiB of content."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        body<PublishSkillVersionRequest>("The version label, its files (base64) and whether to pin it and deliver it.")
        responses {
            ok<SkillPublicationDto>("Published.", HttpStatusCode.Created)
            ok<SkillPublicationDto>("Already published with the same digest (`created` is false).")
            badRequest(PUBLISH)
            error(
                HttpStatusCode.Forbidden,
                "A key without the scope a field needs (`credential_scope`), or not an administrator or a " +
                    "publishing key (`forbidden`).",
            )
            conflict(PUBLISH_CONFLICT)
            error(HttpStatusCode.PayloadTooLarge, "The files hold more than 5 MiB (`skill_artifact_too_large`).")
        }
    }
}

/** Retiring a version of a team skill. */
private fun ApiCatalog.versionRetirementOperations() {
    delete("/api/v1/skills/{name}/versions/{version}") {
        operationId = "retireSkillVersion"
        tag(TAG)
        summary = "Retire a version: it can never be pinned again."
        description = "$ADMIN 409 `skill_version_pinned` for the pinned version (pin another first). Idempotent. " +
            PERSONAL
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
            pathId("version", SKILL_VERSION)
        }
        responses {
            ok<SkillVersionDto>("The version, retired.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound("No such skill, or version of it, in the organization (`not_found`).")
            conflict("The version is the pinned one (`skill_version_pinned`), or the skill is personal " +
                "(`skill_personal`).")
        }
    }
}

/** The delivered version of a team skill, and its audience. */
private fun ApiCatalog.pinOperations() {
    put("/api/v1/skills/{name}/pin") {
        operationId = "pinSkill"
        tag(TAG)
        summary = "Pin the delivered version (update or rollback)."
        description = "An owner or an admin of the organization, from the console (a browser session); or a service " +
            "key holding `skills:publish` and `skills:pin` created by one. Idempotent: `skill.pinned` is " +
            "audited (with the key's id) only when the pin changes. $PERSONAL"
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        body<PinSkillRequest>("The version to deliver.")
        responses {
            ok<SkillEntryDto>("The skill, with its pin.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound("No such skill, or version of it, in the organization (`not_found`).")
            conflict("The skill or the version is retired (`skill_retired`, `skill_version_retired`), or the " +
                "skill is personal (`skill_personal`).")
        }
    }
    delete("/api/v1/skills/{name}/pin") {
        operationId = "unpinSkill"
        tag(TAG)
        summary = "Unpin: stop delivery without touching versions or decisions."
        description = "$ADMIN Idempotent. $PERSONAL"
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The skill, with no pin.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
            conflict(PERSONAL_CONFLICT)
        }
    }
    put("/api/v1/skills/{name}/audience") {
        operationId = "setSkillAudience"
        tag(TAG)
        summary = "Deliver to all members, or only to members set to yes."
        description = "$ADMIN With `allMembers`, every member without an explicit decision receives the pinned " +
            "version, members who join later included; an explicit `deny` still excludes. Idempotent. $PERSONAL"
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        body<SetAudienceRequest>("Whether every member receives it, or only those set to yes.")
        responses {
            ok<SkillEntryDto>("The skill, with its audience.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
            conflict(PERSONAL_CONFLICT)
        }
    }
}

/** The members' decisions on a team skill. */
private fun ApiCatalog.assignmentOperations() {
    get("/api/v1/skills/{name}/assignments") {
        operationId = "listSkillAssignments"
        tag(TAG)
        summary = "The members' explicit decisions on a skill."
        description = "$ADMIN At most 1 000 decisions, by member id."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<AssignmentListDto>("The decisions, by member id.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
        }
    }
    put("/api/v1/skills/{name}/assignments/{memberId}") {
        operationId = "setSkillAssignment"
        tag(TAG)
        summary = "Set a member's decision: allow (yes) or deny (no)."
        description = "$ADMIN 404 for a member of another organization. Idempotent; audited `skill.access.allowed` " +
            "or `skill.access.denied`. $PERSONAL"
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
            pathId("memberId", MEMBER_ID)
        }
        body<SetAssignmentRequest>("The decision: `allow` or `deny`.")
        responses {
            ok<AssignmentDto>("The decision as saved.")
            badRequest("A decision that is neither `allow` nor `deny` (`invalid_request`).")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound("No such skill or member in the organization (`not_found`).")
            conflict(PERSONAL_CONFLICT)
        }
    }
    delete("/api/v1/skills/{name}/assignments/{memberId}") {
        operationId = "clearSkillAssignment"
        tag(TAG)
        summary = "Clear a member's decision."
        description = "The member is then not assigned. $ADMIN Idempotent; audited `skill.access.cleared` when a " +
            "decision was set. $PERSONAL"
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
            pathId("memberId", MEMBER_ID)
        }
        responses {
            empty("Cleared.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound("No such skill or member in the organization (`not_found`).")
            conflict(PERSONAL_CONFLICT)
        }
    }
}

/** The promotion of a member's personal skill to the team. */
private fun ApiCatalog.promotionOperations() {
    post("/api/v1/skills/{name}/promote") {
        operationId = "promoteSkill"
        tag(TAG)
        summary = "Make a member's personal skill a team skill."
        description = "With nothing pinned. $ADMIN Clears the owner, the pin and the audience: the administrator " +
            "pins a version afterwards and assigns it. Audited `skill.promoted`."
        parameters {
            organizationHeader()
            pathId("name", SKILL_NAME)
        }
        responses {
            ok<SkillEntryDto>("The team skill.")
            error(HttpStatusCode.Forbidden, NOT_ADMIN)
            notFound(SKILL_NF)
            conflict("The skill is a team skill already (`skill_not_personal`), or the catalog is full " +
                "(`skill_limit`).")
        }
    }
}
