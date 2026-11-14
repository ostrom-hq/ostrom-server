// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.domain

import run.ostrom.server.hooks.domain.HookRules.publish
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.ArtifactTree
import run.ostrom.server.skills.domain.CatalogAccess
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogEvent
import run.ostrom.server.skills.domain.Sha256
import run.ostrom.server.skills.domain.recordAll
import kotlin.time.Clock

/** Publishes immutable hook versions, from the console or a CI's key; a publication never delivers. */
interface PublishHookVersionUseCase {
    /**
     * Refuses a caller that may not publish, before a body of up to 2 MiB is read.
     *
     * @throws run.ostrom.server.skills.domain.CatalogForbiddenException For a member or a person's token.
     * @throws run.ostrom.server.skills.domain.CredentialScopeException For a key without `hooks:publish`.
     */
    fun authorize(caller: CatalogCaller)

    /**
     * Publishes a version. With [allMembers] the hook, once pinned, reaches every member without a decision; a key
     * may ask it only while no version is pinned or when the hook already is for all members.
     *
     * @return What the publication did.
     *
     * @throws HookAudienceException For a key widening a pinned hook's audience.
     */
    @Suppress("LongParameterList") // The fields of one publication.
    suspend operator fun invoke(
        caller: CatalogCaller,
        name: String,
        version: String,
        definition: HookDefinition,
        files: List<ArtifactFile>,
        allMembers: Boolean,
    ): HookPublication
}

/**
 * [PublishHookVersionUseCase]: the name, the label, the definition and the artifact are checked before anything is
 * stored, the rules of the current state inside the organization's write transaction, and each change is audited once
 * committed (a key's events name the key).
 */
internal class PublishHookVersionUseCaseImpl(
    private val repository: HooksRepository,
    private val sha256: Sha256,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : PublishHookVersionUseCase {
    override fun authorize(caller: CatalogCaller) = CatalogAccess.requirePublisher(caller, HookScopes.PUBLISH)

    @Suppress("LongParameterList") // The fields of one publication.
    override suspend fun invoke(
        caller: CatalogCaller,
        name: String,
        version: String,
        definition: HookDefinition,
        files: List<ArtifactFile>,
        allMembers: Boolean,
    ): HookPublication {
        authorize(caller)
        ArtifactTree.checkName(name, HookLimits.KIND)
        ArtifactTree.checkVersion(version, HookLimits.KIND)
        val artifact = HookArtifacts.validate(definition, files, sha256)
        val events = mutableListOf<CatalogEvent>()
        val publication = repository.write(caller.orgId) {
            publish(caller, name, version, artifact, definition, allMembers, clock.now(), events)
        }
        audit.recordAll(caller, events)
        return publication
    }
}
