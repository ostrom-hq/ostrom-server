// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.skills.domain.AccessPolicy
import run.ostrom.server.skills.domain.CatalogAccess
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogNotFoundException
import run.ostrom.server.skills.domain.Sha256

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
        return resolve(caller, memberId)
    }

    private suspend fun resolve(caller: CatalogCaller, memberId: String): List<EffectiveHook> =
        repository.read(caller.orgId) { candidates(memberId, HookLimits.HOOKS.toInt()) }
            .mapNotNull { it.effective() }.sortedBy { it.name }

    override suspend fun download(caller: CatalogCaller, name: String, version: String): HookDownload {
        CatalogAccess.requireReader(caller, caller.memberId, HookScopes.READ)
        val hook = resolve(caller, caller.memberId).firstOrNull { it.name == name && it.version.version == version }
            ?: throw CatalogNotFoundException("This hook version is not delivered to you, or is not the pinned one.")
        val files = repository.read(caller.orgId) { files(hook.version.id) }
        return HookArtifacts.verified(name, hook.version, files, sha256)
    }
}

/** The version this candidate's member receives, or null: a pinned version and the access policy's yes. */
internal fun HookCandidate.effective(): EffectiveHook? =
    pinned?.takeIf { AccessPolicy.allowed(decision, hook.allMembers) }?.let { EffectiveHook(hook.name, it) }
