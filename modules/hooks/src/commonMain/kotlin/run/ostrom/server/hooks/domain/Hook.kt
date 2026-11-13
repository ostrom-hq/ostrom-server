// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileEntry
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An agent event a hook listens to (Claude Code's names; Codex has no [NOTIFICATION]).
 *
 * @property wire The contract's value.
 */
enum class HookEvent(val wire: String) {
    /** Before a tool runs. */
    PRE_TOOL_USE("PreToolUse"),

    /** After a tool ran. */
    POST_TOOL_USE("PostToolUse"),

    /** A prompt the person submits. */
    USER_PROMPT_SUBMIT("UserPromptSubmit"),

    /** The agent stops. */
    STOP("Stop"),

    /** A subagent stops. */
    SUBAGENT_STOP("SubagentStop"),

    /** A session starts. */
    SESSION_START("SessionStart"),

    /** A session ends. */
    SESSION_END("SessionEnd"),

    /** Before the context is compacted. */
    PRE_COMPACT("PreCompact"),

    /** A notification. */
    NOTIFICATION("Notification"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /**
         * Reads a wire value.
         *
         * @param wire One of the events' names.
         *
         * @return The event, or null for another value.
         */
        fun fromWire(wire: String): HookEvent? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * When and what a hook runs (TeamCode's `HookDefinition`): [command] is a file of the artifact, run with no argument
 * and the event's JSON on standard input.
 *
 * @property events The events, at least one.
 * @property matcher Passed to the agent unchanged (a tool name for `PreToolUse`), or null.
 * @property command The path of an executable file of the artifact.
 * @property timeoutSeconds 1 to 600.
 */
data class HookDefinition(
    val events: Set<HookEvent>,
    val matcher: String?,
    val command: String,
    val timeoutSeconds: Int,
) {
    /** The events' wire names, sorted: how the digest and the database hold them. */
    val wireEvents: List<String> get() = events.map { it.wire }.sorted()
}

/**
 * A team hook of an organization (TeamCode's `docs/hooks.md`). It reaches members only through its [pinnedVersionId],
 * which only an owner or an admin sets from the console; with [allMembers], every member without an explicit decision
 * receives it; an explicit *no* still excludes. Each member then approves each version on their computer.
 *
 * @property id Its id.
 * @property orgId Its organization.
 * @property name Its name, unique in the organization.
 * @property pinnedVersionId The approved version, delivered; null when nothing is delivered.
 * @property pinnedVersion That version's label, read with it.
 * @property allMembers Whether members without a decision receive it.
 * @property createdAt Its first publication.
 * @property updatedAt Its last change.
 */
data class Hook(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val pinnedVersionId: Uuid?,
    val pinnedVersion: String?,
    val allMembers: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * An immutable published version: its definition, files and digest never change.
 *
 * @property id Its id.
 * @property hookId Its hook.
 * @property version Its label, case-sensitive.
 * @property digest Its canonical digest ([HookArtifacts.digest]).
 * @property definition When and what it runs.
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 * @property publishedBy The member who published it (a service key's creator).
 * @property publishedAt When.
 */
data class HookVersion(
    val id: Uuid,
    val hookId: Uuid,
    val version: String,
    val digest: String,
    val definition: HookDefinition,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: Instant,
)

/**
 * A member's decision on one hook. The row stays when the decision is cleared, so its id names the same pair in the
 * audit log.
 *
 * @property id Its id, the subject of its audit events.
 * @property hookId The hook.
 * @property memberId The member.
 * @property decision The decision; null once cleared.
 * @property updatedAt Its last change.
 */
data class HookAssignment(
    val id: Uuid,
    val hookId: Uuid,
    val memberId: String,
    val decision: Decision?,
    val updatedAt: Instant,
)

/**
 * What decides whether one hook reaches one member.
 *
 * @property hook The hook.
 * @property pinned Its pinned version, if any.
 * @property decision The member's explicit decision, if any.
 */
data class HookCandidate(val hook: Hook, val pinned: HookVersion?, val decision: Decision?)

/**
 * A pinned hook version a member receives.
 *
 * @property name The hook.
 * @property version The version.
 */
data class EffectiveHook(val name: String, val version: HookVersion)

/**
 * A hook and its versions.
 *
 * @property hook The hook.
 * @property versions Its versions, newest first.
 */
data class HookDetail(val hook: Hook, val versions: List<HookVersion>)

/**
 * What a publication did.
 *
 * @property hook The hook after it.
 * @property version The version.
 * @property created False when the label was already published with the same digest.
 */
data class HookPublication(val hook: Hook, val version: HookVersion, val created: Boolean)

/**
 * A version's files, checked against its digest.
 *
 * @property name The hook.
 * @property version The version.
 * @property entries The files' metadata, recomputed from their bytes.
 * @property files The files, sorted by path.
 */
class HookDownload(
    val name: String,
    val version: HookVersion,
    val entries: List<FileEntry>,
    val files: List<ArtifactFile>,
) {
    override fun toString(): String = "HookDownload(name=$name, version=${version.version}, files=${entries.size})"
}
