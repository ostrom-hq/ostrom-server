// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.presentation

import run.ostrom.server.skills.domain.Assignment
import run.ostrom.server.skills.domain.EffectiveSkill
import run.ostrom.server.skills.domain.Installation
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.Publication
import run.ostrom.server.skills.domain.Skill
import run.ostrom.server.skills.domain.SkillDetail
import run.ostrom.server.skills.domain.SkillDownload
import run.ostrom.server.skills.domain.SkillVersion
import kotlin.io.encoding.Base64

/** The contract's `SkillEntry`. */
fun Skill.toDto(): SkillEntryDto = SkillEntryDto(
    id = id.toString(),
    name = name,
    pinnedVersion = pinnedVersion,
    retired = retired,
    allMembers = allMembers,
    ownerId = ownerId,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/** The contract's `SkillVersion`, [pinned] when it is its skill's pinned version. */
fun SkillVersion.toDto(pinned: Boolean): SkillVersionDto = SkillVersionDto(
    version = version,
    digest = digest,
    sizeBytes = sizeBytes,
    fileCount = fileCount,
    publishedBy = publishedBy,
    publishedAt = publishedAt.toString(),
    retired = retired,
    pinned = pinned,
)

/** The contract's `SkillDetail`. */
fun SkillDetail.toDto(): SkillDetailDto =
    SkillDetailDto(skill.toDto(), versions.map { it.toDto(it.id == skill.pinnedVersionId) })

/** The contract's `SkillPublication`. */
fun Publication.toDto(): SkillPublicationDto =
    SkillPublicationDto(skill.toDto(), version.toDto(version.id == skill.pinnedVersionId), created)

/** The contract's `Assignment`; [decision] is the one the request set (a cleared decision is never shown). */
fun Assignment.toDto(decision: String): AssignmentDto = AssignmentDto(memberId, decision, updatedAt.toString())

/** The contract's `Assignment`. */
fun MemberDecision.toDto(): AssignmentDto = AssignmentDto(memberId, decision.wire, updatedAt.toString())

/** The contract's `EffectiveSkill`. */
fun EffectiveSkill.toDto(): EffectiveSkillDto = EffectiveSkillDto(name, version, digest, sizeBytes, fileCount)

/** The contract's `SkillArtifact`. */
fun SkillDownload.toDto(): SkillArtifactDto = SkillArtifactDto(
    name = skill.name,
    version = skill.version,
    digest = skill.digest,
    files = entries.zip(files).map { (entry, file) ->
        val content = Base64.Default.encode(file.content)
        ArtifactFileContentDto(entry.path, entry.mode.wire, entry.size, entry.sha256, content)
    },
)

/** The contract's `SkillInstallation`. */
fun Installation.toDto(): SkillInstallationDto =
    SkillInstallationDto(deviceId, deviceName, name, version, digest, status.wire, reportedAt.toString())
