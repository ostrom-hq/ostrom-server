// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.presentation

import kotlinx.serialization.Serializable

/**
 * One file of a skill or hook version.
 *
 * @property path Its relative path.
 * @property mode `file` or `executable`.
 * @property content Its bytes, base64.
 */
@Serializable
data class TeamCodeArtifactFileDto(val path: String, val mode: String, val content: String) {
    /** Without the content. */
    override fun toString(): String = "TeamCodeArtifactFileDto(path=$path, mode=$mode)"
}

/**
 * A member's explicit decision on a skill or a hook.
 *
 * @property memberId TeamCode's member id.
 * @property decision `allow` or `deny`.
 */
@Serializable
data class TeamCodeDecisionDto(val memberId: String, val decision: String)

/**
 * A skill version.
 *
 * @property version Its label.
 * @property files Its files.
 */
@Serializable
data class TeamCodeSkillVersionDto(val version: String, val files: List<TeamCodeArtifactFileDto> = emptyList())

/**
 * A TeamCode skill with the versions the CLI read.
 *
 * @property id TeamCode's id.
 * @property name Its name.
 * @property ownerId The owner of a personal skill.
 * @property allMembers Whether every member without a decision receives it.
 * @property retired Whether TeamCode retired it.
 * @property pinnedVersion The delivered version.
 * @property versions The versions read.
 * @property omittedVersions How many others were not read.
 * @property decisions The members' decisions.
 */
@Serializable
data class TeamCodeSkillDto(
    val id: String,
    val name: String,
    val ownerId: String? = null,
    val allMembers: Boolean = false,
    val retired: Boolean = false,
    val pinnedVersion: String? = null,
    val versions: List<TeamCodeSkillVersionDto> = emptyList(),
    val omittedVersions: Int = 0,
    val decisions: List<TeamCodeDecisionDto> = emptyList(),
)

/**
 * A hook version.
 *
 * @property version Its label.
 * @property events The events it runs on.
 * @property matcher Its tool matcher.
 * @property command The file it runs.
 * @property timeoutSeconds Its timeout.
 * @property files Its files.
 */
@Serializable
data class TeamCodeHookVersionDto(
    val version: String,
    val events: List<String>,
    val matcher: String? = null,
    val command: String,
    val timeoutSeconds: Int,
    val files: List<TeamCodeArtifactFileDto> = emptyList(),
)

/**
 * A TeamCode hook with the versions the CLI read.
 *
 * @property id TeamCode's id.
 * @property name Its name.
 * @property allMembers Whether every member without a decision receives it.
 * @property pinnedVersion The delivered version.
 * @property versions The versions read.
 * @property omittedVersions How many others were not read.
 * @property decisions The members' decisions.
 */
@Serializable
data class TeamCodeHookDto(
    val id: String,
    val name: String,
    val allMembers: Boolean = false,
    val pinnedVersion: String? = null,
    val versions: List<TeamCodeHookVersionDto> = emptyList(),
    val omittedVersions: Int = 0,
    val decisions: List<TeamCodeDecisionDto> = emptyList(),
)

/**
 * A TeamCode environment variant.
 *
 * @property id TeamCode's id.
 * @property variable The environment variable.
 * @property name Its name.
 * @property binding `shared` or `perMember`.
 * @property ownerId The owner of a personal variant.
 * @property value The shared value, in clear (sent once, over TLS).
 */
@Serializable
data class TeamCodeVariableDto(
    val id: String,
    val variable: String,
    val name: String,
    val binding: String,
    val ownerId: String? = null,
    val value: String? = null,
) {
    /** Without the value. */
    override fun toString(): String =
        "TeamCodeVariableDto(id=$id, variable=$variable, name=$name, binding=$binding, ownerId=$ownerId)"
}

/**
 * A member's selection of an environment variant.
 *
 * @property memberId TeamCode's member id.
 * @property variantId TeamCode's variant id.
 * @property source `direct` or `bulk` in TeamCode; Ostrom keeps every imported selection as a direct one.
 * @property memberValue The member's own value, in clear.
 */
@Serializable
data class TeamCodeVariableAssignmentDto(
    val memberId: String,
    val variantId: String,
    val source: String? = null,
    val memberValue: String? = null,
) {
    /** Without the value. */
    override fun toString(): String =
        "TeamCodeVariableAssignmentDto(memberId=$memberId, variantId=$variantId, source=$source)"
}

/**
 * A member excluded from an environment variable.
 *
 * @property memberId TeamCode's member id.
 * @property variable The variable.
 */
@Serializable
data class TeamCodeVariableExclusionDto(val memberId: String, val variable: String)
