// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.presentation

import run.ostrom.server.skills.presentation.ArtifactFileContentDto
import run.ostrom.server.skills.presentation.SkillFileUploadDto
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET /api/v1/hooks`: the organization's team hooks (ostrom-core `listHooks`), and each below. */
@Serializable
@Resource("/api/v1/hooks")
class HooksApi {
    /**
     * A hook.
     *
     * @property parent The catalog.
     * @property name The hook.
     */
    @Serializable
    @Resource("{name}")
    class Name(val parent: HooksApi = HooksApi(), val name: String) {
        /** `GET` and `POST .../versions` (`listHookVersions`, `publishHookVersion`). @property parent The hook. */
        @Serializable
        @Resource("versions")
        class Versions(val parent: Name) {
            /**
             * `GET .../versions/{version}` (`readHookVersion`).
             *
             * @property parent The versions.
             * @property version The label.
             */
            @Serializable
            @Resource("{version}")
            class Version(val parent: Versions, val version: String)
        }

        /** `PUT` and `DELETE .../pin` (`pinHook`, `unpinHook`). @property parent The hook. */
        @Serializable
        @Resource("pin")
        class Pin(val parent: Name)

        /** `PUT .../audience` (`setHookAudience`). @property parent The hook. */
        @Serializable
        @Resource("audience")
        class Audience(val parent: Name)

        /** `GET .../assignments` (`listHookAssignments`). @property parent The hook. */
        @Serializable
        @Resource("assignments")
        class Assignments(val parent: Name) {
            /**
             * `PUT` and `DELETE .../assignments/{memberId}` (`setHookAssignment`, `clearHookAssignment`).
             *
             * @property parent The decisions.
             * @property memberId The member.
             */
            @Serializable
            @Resource("{memberId}")
            class Member(val parent: Assignments, val memberId: String)
        }
    }
}

/** `GET /api/v1/hook-delivery`: what the caller receives (`getHookDelivery`). */
@Serializable
@Resource("/api/v1/hook-delivery")
class HookDeliveryApi {
    /**
     * `GET /api/v1/hook-delivery/{name}/{version}` (`downloadHook`).
     *
     * @property parent The delivery.
     * @property name The hook.
     * @property version Its pinned version.
     */
    @Serializable
    @Resource("{name}/{version}")
    class Download(val parent: HookDeliveryApi = HookDeliveryApi(), val name: String, val version: String)
}

/**
 * `GET /api/v1/members/{memberId}/hook-delivery` (`getMemberHookDelivery`).
 *
 * @property memberId The member.
 */
@Serializable
@Resource("/api/v1/members/{memberId}/hook-delivery")
class MemberHookDeliveryApi(val memberId: String)

/**
 * ostrom-core `HookDefinition`.
 *
 * @property events The agent events, each once.
 * @property matcher Passed to the agent unchanged; absent for none.
 * @property command The path of an executable file of the artifact.
 * @property timeoutSeconds 1 to 600, 60 by default.
 */
@Serializable
data class HookDefinitionDto(
    val events: List<String>,
    val matcher: String? = null,
    val command: String,
    val timeoutSeconds: Int? = null,
)

/**
 * ostrom-core `HookEntry`.
 *
 * @property id Its id.
 * @property name Its name.
 * @property pinnedVersion The delivered version; absent when none is.
 * @property allMembers Delivered to every member without a decision.
 * @property createdAt ISO 8601.
 * @property updatedAt ISO 8601.
 */
@Serializable
data class HookEntryDto(
    val id: String,
    val name: String,
    val pinnedVersion: String?,
    val allMembers: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/** ostrom-core `HookCatalog`. @property items The hooks. */
@Serializable
data class HookCatalogDto(val items: List<HookEntryDto>)

/**
 * ostrom-core `HookVersion`.
 *
 * @property version Its label.
 * @property digest `sha256:<hex>`.
 * @property definition When and what it runs.
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 * @property publishedBy The member who published it.
 * @property publishedAt ISO 8601.
 * @property pinned The hook's pinned version.
 */
@Serializable
data class HookVersionDto(
    val version: String,
    val digest: String,
    val definition: HookDefinitionDto,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: String,
    val pinned: Boolean,
)

/** ostrom-core `HookDetail`. @property hook The hook. @property versions Its versions, newest first. */
@Serializable
data class HookDetailDto(val hook: HookEntryDto, val versions: List<HookVersionDto>)

/**
 * ostrom-core `PublishHookVersionRequest`.
 *
 * @property version The label.
 * @property definition When and what it runs.
 * @property files The files, the command among them as `executable`.
 * @property allMembers Deliver to all members once pinned.
 */
@Serializable
data class PublishHookVersionRequest(
    val version: String,
    val definition: HookDefinitionDto,
    val files: List<SkillFileUploadDto>,
    val allMembers: Boolean = false,
)

/**
 * ostrom-core `HookPublication`.
 *
 * @property hook The hook after it.
 * @property version The version.
 * @property created False when it was already published with the same digest.
 */
@Serializable
data class HookPublicationDto(val hook: HookEntryDto, val version: HookVersionDto, val created: Boolean)

/**
 * ostrom-core `EffectiveHook`.
 *
 * @property name The hook.
 * @property version The pinned version.
 * @property digest Its digest.
 * @property definition When and what it runs.
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 */
@Serializable
data class EffectiveHookDto(
    val name: String,
    val version: String,
    val digest: String,
    val definition: HookDefinitionDto,
    val sizeBytes: Long,
    val fileCount: Int,
)

/** ostrom-core `HookManifest`. @property memberId The member. @property hooks What they receive. */
@Serializable
data class HookManifestDto(val memberId: String, val hooks: List<EffectiveHookDto>)

/**
 * ostrom-core `HookArtifact`.
 *
 * @property name The hook.
 * @property version The version.
 * @property digest Its digest.
 * @property definition When and what it runs.
 * @property files Its files, sorted by path.
 */
@Serializable
data class HookArtifactDto(
    val name: String,
    val version: String,
    val digest: String,
    val definition: HookDefinitionDto,
    val files: List<ArtifactFileContentDto>,
)

/**
 * `POST /api/v1/cloud/runner/hooks` (`fetchRunnerHooks`): the hooks a cloud session's owner receives, asked by its
 * runner with the execution's runner token.
 */
@Serializable
@Resource("/api/v1/cloud/runner/hooks")
class RunnerHooksApi {
    /**
     * `POST /api/v1/cloud/runner/hooks/artifact` (`downloadRunnerHook`).
     *
     * @property parent The runner's delivery.
     */
    @Serializable
    @Resource("artifact")
    class Artifact(val parent: RunnerHooksApi = RunnerHooksApi())
}
