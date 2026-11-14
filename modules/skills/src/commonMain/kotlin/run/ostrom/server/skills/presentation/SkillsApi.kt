// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/skills`: the organization's catalog (ostrom-core `listSkills`), and its skills below.
 */
@Serializable
@Resource("/api/v1/skills")
class SkillsApi {
    /**
     * `DELETE /api/v1/skills/{name}` (`retireSkill`).
     *
     * @property parent The catalog.
     * @property name The skill.
     */
    @Serializable
    @Resource("{name}")
    class Name(val parent: SkillsApi = SkillsApi(), val name: String) {
        /** `POST .../restore` (`restoreSkill`). @property parent The skill. */
        @Serializable
        @Resource("restore")
        class Restore(val parent: Name)

        /** `GET` and `POST .../versions` (`listSkillVersions`, `publishSkillVersion`). @property parent The skill. */
        @Serializable
        @Resource("versions")
        class Versions(val parent: Name) {
            /**
             * `DELETE .../versions/{version}` (`retireSkillVersion`).
             *
             * @property parent The versions.
             * @property version The label.
             */
            @Serializable
            @Resource("{version}")
            class Version(val parent: Versions, val version: String)
        }

        /** `PUT` and `DELETE .../pin` (`pinSkill`, `unpinSkill`). @property parent The skill. */
        @Serializable
        @Resource("pin")
        class Pin(val parent: Name)

        /** `PUT .../audience` (`setSkillAudience`). @property parent The skill. */
        @Serializable
        @Resource("audience")
        class Audience(val parent: Name)

        /** `GET .../assignments` (`listSkillAssignments`). @property parent The skill. */
        @Serializable
        @Resource("assignments")
        class Assignments(val parent: Name) {
            /**
             * `PUT` and `DELETE .../assignments/{memberId}` (`setSkillAssignment`, `clearSkillAssignment`).
             *
             * @property parent The decisions.
             * @property memberId The member.
             */
            @Serializable
            @Resource("{memberId}")
            class Member(val parent: Assignments, val memberId: String)
        }

        /** `POST .../promote` (`promoteSkill`). @property parent The skill. */
        @Serializable
        @Resource("promote")
        class Promote(val parent: Name)
    }
}

/** `GET /api/v1/me/skills`: the caller's personal skills (`listMySkills`), and each below. */
@Serializable
@Resource("/api/v1/me/skills")
class MySkillsApi {
    /**
     * `DELETE /api/v1/me/skills/{name}` (`retireMySkill`).
     *
     * @property parent The personal skills.
     * @property name The skill.
     */
    @Serializable
    @Resource("{name}")
    class Name(val parent: MySkillsApi = MySkillsApi(), val name: String) {
        /** `POST .../restore` (`restoreMySkill`). @property parent The skill. */
        @Serializable
        @Resource("restore")
        class Restore(val parent: Name)

        /**
         * `GET` and `POST .../versions` (`listMySkillVersions`, `publishMySkillVersion`).
         *
         * @property parent The skill.
         */
        @Serializable
        @Resource("versions")
        class Versions(val parent: Name)

        /** `PUT` and `DELETE .../pin` (`pinMySkill`, `unpinMySkill`). @property parent The skill. */
        @Serializable
        @Resource("pin")
        class Pin(val parent: Name)
    }
}

/** `GET /api/v1/skill-delivery`: what the caller receives (`getSkillDelivery`). */
@Serializable
@Resource("/api/v1/skill-delivery")
class SkillDeliveryApi {
    /**
     * `GET /api/v1/skill-delivery/{name}/{version}` (`downloadSkill`).
     *
     * @property parent The delivery.
     * @property name The skill.
     * @property version Its pinned version.
     */
    @Serializable
    @Resource("{name}/{version}")
    class Download(val parent: SkillDeliveryApi = SkillDeliveryApi(), val name: String, val version: String)

    /**
     * `GET` and `PUT /api/v1/skill-delivery/installations` (`listMySkillInstallations`, `reportSkillInstallations`).
     *
     * @property parent The delivery.
     */
    @Serializable
    @Resource("installations")
    class Installations(val parent: SkillDeliveryApi = SkillDeliveryApi())
}

/**
 * `GET /api/v1/members/{memberId}/skill-delivery` (`getMemberSkillDelivery`).
 *
 * @property memberId The member.
 */
@Serializable
@Resource("/api/v1/members/{memberId}/skill-delivery")
class MemberSkillDeliveryApi(val memberId: String)

/**
 * `GET /api/v1/members/{memberId}/skill-installations` (`listMemberSkillInstallations`).
 *
 * @property memberId The member.
 */
@Serializable
@Resource("/api/v1/members/{memberId}/skill-installations")
class MemberSkillInstallationsApi(val memberId: String)

/**
 * ostrom-core `SkillEntry`.
 *
 * @property id Its id.
 * @property name Its name.
 * @property pinnedVersion The delivered version; absent when none is.
 * @property retired Removed from the catalog.
 * @property allMembers Delivered to every member without a decision.
 * @property ownerId The member a personal skill belongs to; absent for a team skill.
 * @property createdAt ISO 8601.
 * @property updatedAt ISO 8601.
 */
@Serializable
data class SkillEntryDto(
    val id: String,
    val name: String,
    val pinnedVersion: String?,
    val retired: Boolean,
    val allMembers: Boolean,
    val ownerId: String?,
    val createdAt: String,
    val updatedAt: String,
)

/** ostrom-core `SkillCatalog`. @property items The skills. */
@Serializable
data class SkillCatalogDto(val items: List<SkillEntryDto>)

/**
 * ostrom-core `SkillVersion`.
 *
 * @property version Its label.
 * @property digest `sha256:<hex>`.
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 * @property publishedBy The member who published it.
 * @property publishedAt ISO 8601.
 * @property retired Can no longer be pinned.
 * @property pinned The skill's pinned version.
 */
@Serializable
data class SkillVersionDto(
    val version: String,
    val digest: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: String,
    val retired: Boolean,
    val pinned: Boolean,
)

/** ostrom-core `SkillDetail`. @property skill The skill. @property versions Its versions, newest first. */
@Serializable
data class SkillDetailDto(val skill: SkillEntryDto, val versions: List<SkillVersionDto>)

/**
 * ostrom-core `SkillFileUpload`.
 *
 * @property path The relative path.
 * @property mode `file` (by default) or `executable`.
 * @property contentBase64 The content.
 */
@Serializable
data class SkillFileUploadDto(val path: String, val mode: String? = null, val contentBase64: String)

/**
 * ostrom-core `PublishSkillVersionRequest`.
 *
 * @property version The label.
 * @property files The files.
 * @property pin Pin it at once.
 * @property allMembers Deliver to all members.
 */
@Serializable
data class PublishSkillVersionRequest(
    val version: String,
    val files: List<SkillFileUploadDto>,
    val pin: Boolean = false,
    val allMembers: Boolean = false,
)

/**
 * ostrom-core `SkillPublication`.
 *
 * @property skill The skill after it.
 * @property version The version.
 * @property created False when it was already published with the same digest.
 */
@Serializable
data class SkillPublicationDto(val skill: SkillEntryDto, val version: SkillVersionDto, val created: Boolean)

/** ostrom-core `PinSkillRequest`. @property version The label. */
@Serializable
data class PinSkillRequest(val version: String)

/** ostrom-core `SetAudienceRequest`. @property allMembers Every member, or only those set to yes. */
@Serializable
data class SetAudienceRequest(val allMembers: Boolean)

/**
 * ostrom-core `Assignment`.
 *
 * @property memberId The member.
 * @property decision `allow` or `deny`.
 * @property updatedAt ISO 8601.
 */
@Serializable
data class AssignmentDto(val memberId: String, val decision: String, val updatedAt: String)

/** ostrom-core `AssignmentList`. @property items The decisions. */
@Serializable
data class AssignmentListDto(val items: List<AssignmentDto>)

/** ostrom-core `SetAssignmentRequest`. @property decision `allow` or `deny`. */
@Serializable
data class SetAssignmentRequest(val decision: String)

/**
 * ostrom-core `EffectiveSkill`.
 *
 * @property name The skill.
 * @property version The pinned version.
 * @property digest Its digest.
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 */
@Serializable
data class EffectiveSkillDto(
    val name: String,
    val version: String,
    val digest: String,
    val sizeBytes: Long,
    val fileCount: Int,
)

/** ostrom-core `SkillManifest`. @property memberId The member. @property skills What they receive. */
@Serializable
data class SkillManifestDto(val memberId: String, val skills: List<EffectiveSkillDto>)

/**
 * ostrom-core `ArtifactFileContent`.
 *
 * @property path Its path.
 * @property mode `file` or `executable`.
 * @property size In bytes.
 * @property sha256 Lower-case hex of its content.
 * @property contentBase64 The content.
 */
@Serializable
data class ArtifactFileContentDto(
    val path: String,
    val mode: String,
    val size: Int,
    val sha256: String,
    val contentBase64: String,
)

/**
 * ostrom-core `SkillArtifact`.
 *
 * @property name The skill.
 * @property version The version.
 * @property digest Its digest.
 * @property files Its files, sorted by path.
 */
@Serializable
data class SkillArtifactDto(
    val name: String,
    val version: String,
    val digest: String,
    val files: List<ArtifactFileContentDto>,
)

/**
 * ostrom-core `SkillInstallationReport`.
 *
 * @property name The skill.
 * @property version The version installed.
 * @property digest Its digest.
 * @property status `installed`, `failed`, `conflict` or `removed`.
 */
@Serializable
data class SkillInstallationReportDto(
    val name: String,
    val version: String? = null,
    val digest: String? = null,
    val status: String,
)

/** ostrom-core `ReportSkillInstallationsRequest`. @property installations The reports. */
@Serializable
data class ReportSkillInstallationsRequest(val installations: List<SkillInstallationReportDto>)

/**
 * ostrom-core `SkillInstallation`.
 *
 * @property deviceId The workstation.
 * @property deviceName Its name.
 * @property name The skill.
 * @property version The version.
 * @property digest Its digest.
 * @property status What the workstation did.
 * @property reportedAt ISO 8601.
 */
@Serializable
data class SkillInstallationDto(
    val deviceId: String,
    val deviceName: String,
    val name: String,
    val version: String?,
    val digest: String?,
    val status: String,
    val reportedAt: String,
)

/** ostrom-core `SkillInstallationList`. @property items The reports. */
@Serializable
data class SkillInstallationListDto(val items: List<SkillInstallationDto>)
