// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.hooks.domain.HookRules.audience
import run.ostrom.server.hooks.domain.HookRules.existing
import run.ostrom.server.hooks.domain.HookRules.pin
import run.ostrom.server.hooks.domain.HookRules.unpin
import run.ostrom.server.hooks.domain.HookRules.versionOf
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.skills.domain.ArtifactTree
import run.ostrom.server.skills.domain.CatalogAccess
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogEvent
import run.ostrom.server.skills.domain.CatalogNotFoundException
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.Sha256
import run.ostrom.server.skills.domain.recordAll
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The hook catalog's administration: every operation is an owner's or an admin's, from the console. No key ever pins
 * a hook, changes its audience or reads a version's code.
 */
interface ManageHookCatalogUseCase {
    /** The organization's hooks, newest change first. */
    suspend fun catalog(caller: CatalogCaller): List<Hook>

    /** A hook and its versions, newest first. */
    suspend fun versions(caller: CatalogCaller, name: String): HookDetail

    /**
     * A version's definition and files, pinned or not, so an administrator reads the code before pinning it.
     *
     * @throws run.ostrom.server.skills.domain.ArtifactIntegrityException `hook_integrity` on a mismatch.
     */
    suspend fun read(caller: CatalogCaller, name: String, version: String): HookDownload

    /** Pins [version] (update or rollback). */
    suspend fun pin(caller: CatalogCaller, name: String, version: String): Hook

    /** Stops delivery. */
    suspend fun unpin(caller: CatalogCaller, name: String): Hook

    /** Delivers to all members, or only to members set to yes. */
    suspend fun setAudience(caller: CatalogCaller, name: String, allMembers: Boolean): Hook

    /** The explicit decisions. */
    suspend fun assignments(caller: CatalogCaller, name: String): List<MemberDecision>

    /**
     * Sets a member's decision, or clears it with null; idempotent.
     *
     * @return The decision row as it now stands.
     */
    suspend fun assign(caller: CatalogCaller, name: String, memberId: String, decision: Decision?): HookAssignment
}

/** [ManageHookCatalogUseCase]: input checked first, state rules inside the organization's write transaction. */
internal class ManageHookCatalogUseCaseImpl(
    private val repository: HooksRepository,
    private val members: MemberDirectory,
    private val sha256: Sha256,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : ManageHookCatalogUseCase {
    override suspend fun catalog(caller: CatalogCaller): List<Hook> {
        CatalogAccess.requireConsoleAdministrator(caller)
        return repository.read(caller.orgId) { catalog(HookLimits.HOOKS.toInt()) }
    }

    override suspend fun versions(caller: CatalogCaller, name: String): HookDetail {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, HookLimits.KIND)
        return repository.read(caller.orgId) {
            val hook = existing(name)
            HookDetail(hook, versions(hook.id, HookLimits.VERSIONS.toInt()))
        }
    }

    override suspend fun read(caller: CatalogCaller, name: String, version: String): HookDownload {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, HookLimits.KIND)
        ArtifactTree.checkVersion(version, HookLimits.KIND)
        val (stored, files) = repository.read(caller.orgId) {
            val target = versionOf(existing(name), version)
            target to files(target.id)
        }
        return HookArtifacts.verified(name, stored, files, sha256)
    }

    override suspend fun pin(caller: CatalogCaller, name: String, version: String): Hook {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkVersion(version, HookLimits.KIND)
        return write(caller, name) { hook, now, events -> pin(hook, versionOf(hook, version), now, events) }
    }

    override suspend fun unpin(caller: CatalogCaller, name: String): Hook {
        CatalogAccess.requireConsoleAdministrator(caller)
        return write(caller, name) { hook, now, events -> unpin(hook, now, events) }
    }

    override suspend fun setAudience(caller: CatalogCaller, name: String, allMembers: Boolean): Hook {
        CatalogAccess.requireConsoleAdministrator(caller)
        return write(caller, name) { hook, now, events -> audience(hook, allMembers, now, events) }
    }

    override suspend fun assignments(caller: CatalogCaller, name: String): List<MemberDecision> {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, HookLimits.KIND)
        return repository.read(caller.orgId) { assignments(existing(name).id, HookLimits.ROWS) }
    }

    override suspend fun assign(
        caller: CatalogCaller,
        name: String,
        memberId: String,
        decision: Decision?,
    ): HookAssignment {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, HookLimits.KIND)
        if (!members.isActiveMember(caller.orgId, memberId)) throw CatalogNotFoundException("Member not found.")
        val events = mutableListOf<CatalogEvent>()
        val saved = repository.write(caller.orgId) {
            val hook = existing(name)
            val now = clock.now()
            val current = assignment(hook.id, memberId) ?: HookAssignment(Uuid.random(), hook.id, memberId, null, now)
            // Nothing changes: no write, no event (clearing a decision never set included).
            if (current.decision == decision) return@write current
            val row = current.copy(decision = decision, updatedAt = now)
            saveAssignment(row, caller.memberId)
            // No `when` on the enum: its generated fallback branch is unreachable.
            val event = decision?.let { if (it == Decision.ALLOW) "hook.access.allowed" else "hook.access.denied" }
                ?: "hook.access.cleared"
            events += CatalogEvent(event, "hook_assignment", row.id)
            row
        }
        audit.recordAll(caller, events)
        return saved
    }

    /** One change of one hook in the organization's write transaction, audited once committed. */
    private suspend fun write(
        caller: CatalogCaller,
        name: String,
        change: suspend HooksStore.(Hook, Instant, MutableList<CatalogEvent>) -> Hook,
    ): Hook {
        ArtifactTree.checkName(name, HookLimits.KIND)
        val events = mutableListOf<CatalogEvent>()
        val changed = repository.write(caller.orgId) { change(existing(name), clock.now(), events) }
        audit.recordAll(caller, events)
        return changed
    }
}
