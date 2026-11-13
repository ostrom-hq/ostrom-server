// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogConflictException
import run.ostrom.server.skills.domain.CatalogEvent
import run.ostrom.server.skills.domain.CatalogNotFoundException
import run.ostrom.server.skills.domain.CredentialKind
import run.ostrom.server.skills.domain.ValidatedArtifact
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The scopes of the hook catalog. */
object HookScopes {
    /** Publishing hook versions (an owner's or an admin's key); never pinning. */
    const val PUBLISH: String = "hooks:publish"

    /** Reading one's own delivery. */
    const val READ: String = "hooks:read"
}

/**
 * The state changes of the hook catalog, applied inside one write transaction of [HooksRepository] (TeamCode's
 * `SqlHookStore`): each returns the audit events it caused, none for a change that changes nothing.
 */
internal object HookRules {
    /** The hook [name], or 404. */
    suspend fun HooksStore.existing(name: String): Hook =
        hook(name) ?: throw CatalogNotFoundException("Hook not found.")

    /** A version of [hook] by its label, or 404. */
    suspend fun HooksStore.versionOf(hook: Hook, label: String): HookVersion =
        version(hook.id, label) ?: throw CatalogNotFoundException("Hook version not found.")

    /** Points [hook] at [version] unless it already is. */
    suspend fun HooksStore.pin(
        hook: Hook,
        version: HookVersion,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Hook {
        if (hook.pinnedVersionId == version.id) return hook
        val pinned = hook.copy(pinnedVersionId = version.id, pinnedVersion = version.version, updatedAt = now)
        updateHook(pinned)
        events += CatalogEvent("hook.pinned", "hook_version", version.id)
        return pinned
    }

    /** Stops delivering [hook]. */
    suspend fun HooksStore.unpin(hook: Hook, now: Instant, events: MutableList<CatalogEvent>): Hook {
        if (hook.pinnedVersionId == null) return hook
        val changed = hook.copy(pinnedVersionId = null, pinnedVersion = null, updatedAt = now)
        updateHook(changed)
        events += CatalogEvent("hook.unpinned", "hook", hook.id)
        return changed
    }

    /** Sets [hook]'s audience unless it already is. */
    suspend fun HooksStore.audience(
        hook: Hook,
        allMembers: Boolean,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Hook {
        if (hook.allMembers == allMembers) return hook
        val changed = hook.copy(allMembers = allMembers, updatedAt = now)
        updateHook(changed)
        val event = if (allMembers) "hook.audience.all_members" else "hook.audience.assigned_only"
        events += CatalogEvent(event, "hook", hook.id)
        return changed
    }

    /**
     * Publishes [label] of [name], creating the hook on its first publication (TeamCode's `publish` and
     * `publishVersion`, in one transaction here: a refused first publication leaves no empty hook behind). A
     * publication never pins. [allMembers] widens the audience: always for an administrator, for a key only while
     * the hook has no pinned version, so the administrator's pin approves the code and who runs it together.
     */
    @Suppress("LongParameterList") // The fields of one publication.
    suspend fun HooksStore.publish(
        caller: CatalogCaller,
        name: String,
        label: String,
        artifact: ValidatedArtifact,
        definition: HookDefinition,
        allMembers: Boolean,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): HookPublication {
        val hook = hook(name) ?: create(caller, name, now, events)
        val widen = allMembers && !hook.allMembers
        // Read under the organization's hook lock, which the pin also takes.
        if (widen && caller.credential == CredentialKind.SERVICE_KEY && hook.pinnedVersionId != null) {
            throw HookAudienceException()
        }
        val known = version(hook.id, label)
        if (known != null) {
            ensureSameContent(known, artifact)
            // A retry converges on the same state; only an actual change is audited.
            val after = if (widen) audience(hook, true, now, events) else hook
            return HookPublication(after, known, created = false)
        }
        ensureRoomForAVersion(hook)
        val version = HookVersion(
            id = Uuid.random(),
            hookId = hook.id,
            version = label,
            digest = artifact.digest,
            definition = definition,
            sizeBytes = artifact.sizeBytes,
            fileCount = artifact.entries.size,
            publishedBy = caller.memberId,
            publishedAt = now,
        )
        insertVersion(version, artifact)
        events += CatalogEvent("hook.version.published", "hook_version", version.id)
        var after = hook.copy(updatedAt = now)
        updateHook(after)
        if (widen) after = audience(after, true, now, events)
        return HookPublication(after, version, created = true)
    }

    private fun ensureSameContent(known: HookVersion, artifact: ValidatedArtifact) {
        if (known.digest != artifact.digest) {
            throw CatalogConflictException(
                "hook_version_conflict",
                "This version is already published with different content: publish a new version.",
            )
        }
    }

    private suspend fun HooksStore.ensureRoomForAVersion(hook: Hook) {
        if (countVersions(hook.id) >= HookLimits.VERSIONS) {
            throw CatalogConflictException(
                "hook_version_limit",
                "A hook keeps at most ${HookLimits.VERSIONS} versions.",
            )
        }
    }

    private suspend fun HooksStore.create(
        caller: CatalogCaller,
        name: String,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Hook {
        if (countHooks() >= HookLimits.HOOKS) {
            throw CatalogConflictException("hook_limit", "The catalog is limited to ${HookLimits.HOOKS} hooks.")
        }
        val hook = Hook(Uuid.random(), caller.orgId, name, null, null, false, now, now)
        insertHook(hook)
        events += CatalogEvent("hook.created", "hook", hook.id)
        return hook
    }
}
