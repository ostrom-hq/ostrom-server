// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.skills.domain.AccessPolicy
import run.ostrom.server.skills.domain.CatalogAccess
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogNotFoundException
import run.ostrom.server.skills.domain.RunnerOwner
import run.ostrom.server.skills.domain.Sha256
import kotlin.uuid.Uuid

/** What a member receives. Their CLI installs only the versions they approved on their computer. */
interface HookDeliveryUseCase {
    /**
     * The hooks [memberId] receives: pinned versions their decision, or the audience, allows. Evaluated on the shared
     * state at each call.
     *
     * @return Sorted by name.
     *
     * @throws CatalogNotFoundException For a member of another organization.
     */
    suspend fun effective(caller: CatalogCaller, memberId: String): List<EffectiveHook>

    /**
     * The definition and files of [version] of [name], only while it is the caller's effective pinned version.
     *
     * @throws CatalogNotFoundException Otherwise.
     * @throws run.ostrom.server.skills.domain.ArtifactIntegrityException `hook_integrity` on a mismatch.
     */
    suspend fun download(caller: CatalogCaller, name: String, version: String): HookDownload

    /**
     * The hooks a cloud session's [owner] receives, for its runner: as [effective], none for an owner who is no longer
     * an active member. The pod asks no approval: the administrator's pin and the owner's decision are it.
     *
     * @return Sorted by name.
     */
    suspend fun forRunner(owner: RunnerOwner): List<EffectiveHook>

    /**
     * The definition and files of [version] of [name] for a cloud session's [owner], only while it is their effective
     * pinned version.
     *
     * @throws CatalogNotFoundException Otherwise.
     * @throws run.ostrom.server.skills.domain.ArtifactIntegrityException `hook_integrity` on a mismatch.
     */
    suspend fun downloadForRunner(owner: RunnerOwner, name: String, version: String): HookDownload
}

/** [HookDeliveryUseCase] (TeamCode's `ResolveEffectiveHooksUseCase` and `DownloadHookUseCase`). */
internal class HookDeliveryUseCaseImpl(
    private val repository: HooksRepository,
    private val members: MemberDirectory,
    private val sha256: Sha256,
) : HookDeliveryUseCase {
    override suspend fun effective(caller: CatalogCaller, memberId: String): List<EffectiveHook> {
        CatalogAccess.requireReader(caller, memberId, HookScopes.READ)
        if (memberId != caller.memberId && !members.isActiveMember(caller.orgId, memberId)) {
            throw CatalogNotFoundException("Member not found.")
        }
        return resolve(caller.orgId, memberId)
    }

    private suspend fun resolve(orgId: Uuid, memberId: String): List<EffectiveHook> =
        repository.read(orgId) { candidates(memberId, HookLimits.HOOKS.toInt()) }
            .mapNotNull { it.effective() }.sortedBy { it.name }

    override suspend fun download(caller: CatalogCaller, name: String, version: String): HookDownload {
        CatalogAccess.requireReader(caller, caller.memberId, HookScopes.READ)
        return download(caller.orgId, caller.memberId, name, version)
    }

    override suspend fun forRunner(owner: RunnerOwner): List<EffectiveHook> =
        if (members.isActiveMember(owner.orgId, owner.memberId)) resolve(owner.orgId, owner.memberId) else emptyList()

    override suspend fun downloadForRunner(owner: RunnerOwner, name: String, version: String): HookDownload {
        if (!members.isActiveMember(owner.orgId, owner.memberId)) throw CatalogNotFoundException("Member not found.")
        return download(owner.orgId, owner.memberId, name, version)
    }

    private suspend fun download(orgId: Uuid, memberId: String, name: String, version: String): HookDownload {
        val hook = resolve(orgId, memberId).firstOrNull { it.name == name && it.version.version == version }
            ?: throw CatalogNotFoundException("This hook version is not delivered to you, or is not the pinned one.")
        val files = repository.read(orgId) { files(hook.version.id) }
        return HookArtifacts.verified(name, hook.version, files, sha256)
    }
}

/** The version this candidate's member receives, or null: a pinned version and the access policy's yes. */
internal fun HookCandidate.effective(): EffectiveHook? =
    pinned?.takeIf { AccessPolicy.allowed(decision, hook.allMembers) }?.let { EffectiveHook(hook.name, it) }
