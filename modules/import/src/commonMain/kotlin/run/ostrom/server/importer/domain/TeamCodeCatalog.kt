// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import run.ostrom.server.secrets.domain.SecretValue

/**
 * What a run imports into the skill and hook catalogs and the environment: TeamCode's skills, hooks and environment
 * variants (`variants` of kind `ENV`), with their assignments and exclusions.
 *
 * @property skills TeamCode's skills.
 * @property hooks TeamCode's hooks.
 * @property variables TeamCode's environment variants, values in clear.
 * @property variableAssignments Which member uses which variant, with their own value for a per-member one.
 * @property variableExclusions The members excluded from a variable.
 */
data class TeamCodeCatalog(
    val skills: List<TeamCodeSkill> = emptyList(),
    val hooks: List<TeamCodeHook> = emptyList(),
    val variables: List<TeamCodeVariable> = emptyList(),
    val variableAssignments: List<TeamCodeVariableAssignment> = emptyList(),
    val variableExclusions: List<TeamCodeVariableExclusion> = emptyList(),
)

/**
 * One file of a skill or hook version.
 *
 * @property path Its relative path.
 * @property mode `file` or `executable`.
 * @property content Its bytes.
 */
class TeamCodeArtifactFile(val path: String, val mode: String, val content: ByteArray) {
    // Never print the content.
    override fun toString(): String = "TeamCodeArtifactFile(path=$path, mode=$mode, size=${content.size})"
}

/**
 * A member's explicit decision on a skill or a hook.
 *
 * @property memberId TeamCode's member id.
 * @property decision `allow` or `deny`.
 */
data class TeamCodeDecision(val memberId: String, val decision: String)

/**
 * A skill version.
 *
 * @property version Its label.
 * @property files Its files.
 */
data class TeamCodeSkillVersion(val version: String, val files: List<TeamCodeArtifactFile>)

/**
 * A row of TeamCode's `skills` table with the versions the CLI read.
 *
 * @property id TeamCode's id.
 * @property name Its name, kept in Ostrom.
 * @property ownerId The TeamCode member a personal skill belongs to; null for a team skill.
 * @property allMembers Whether every member without a decision receives it.
 * @property retired Whether TeamCode retired it: it is not imported.
 * @property pinnedVersion The label TeamCode delivers, or null.
 * @property versions The versions read, oldest first.
 * @property omittedVersions How many other versions were not read.
 * @property decisions The members' explicit decisions.
 */
data class TeamCodeSkill(
    val id: String,
    val name: String,
    val ownerId: String? = null,
    val allMembers: Boolean = false,
    val retired: Boolean = false,
    val pinnedVersion: String? = null,
    val versions: List<TeamCodeSkillVersion> = emptyList(),
    val omittedVersions: Int = 0,
    val decisions: List<TeamCodeDecision> = emptyList(),
)

/**
 * A hook version.
 *
 * @property version Its label.
 * @property events The events it runs on (Claude Code's names).
 * @property matcher Its tool matcher, or null.
 * @property command The executable file it runs.
 * @property timeoutSeconds Its timeout.
 * @property files Its files.
 */
data class TeamCodeHookVersion(
    val version: String,
    val events: List<String>,
    val matcher: String? = null,
    val command: String,
    val timeoutSeconds: Int,
    val files: List<TeamCodeArtifactFile>,
)

/**
 * A row of TeamCode's `hooks` table with the versions the CLI read.
 *
 * @property id TeamCode's id.
 * @property name Its name, kept in Ostrom.
 * @property allMembers Whether every member without a decision receives it.
 * @property pinnedVersion The label TeamCode delivers, or null.
 * @property versions The versions read, oldest first.
 * @property omittedVersions How many other versions were not read.
 * @property decisions The members' explicit decisions.
 */
data class TeamCodeHook(
    val id: String,
    val name: String,
    val allMembers: Boolean = false,
    val pinnedVersion: String? = null,
    val versions: List<TeamCodeHookVersion> = emptyList(),
    val omittedVersions: Int = 0,
    val decisions: List<TeamCodeDecision> = emptyList(),
)

/**
 * A TeamCode environment variant.
 *
 * @property id TeamCode's id.
 * @property variable The environment variable it fills.
 * @property name Its name.
 * @property perMember Whether each member brings their own value.
 * @property ownerId The TeamCode member a personal variant belongs to; null for a team variant.
 * @property value The shared value, null for a per-member variant.
 */
data class TeamCodeVariable(
    val id: String,
    val variable: String,
    val name: String,
    val perMember: Boolean = false,
    val ownerId: String? = null,
    val value: SecretValue? = null,
)

/**
 * A member's selection of an environment variant.
 *
 * @property memberId TeamCode's member id.
 * @property variantId TeamCode's variant id.
 * @property memberValue The member's own value, for a per-member variant.
 */
data class TeamCodeVariableAssignment(
    val memberId: String,
    val variantId: String,
    val memberValue: SecretValue? = null,
)

/**
 * A member excluded from an environment variable.
 *
 * @property memberId TeamCode's member id.
 * @property variable The variable.
 */
data class TeamCodeVariableExclusion(val memberId: String, val variable: String)
