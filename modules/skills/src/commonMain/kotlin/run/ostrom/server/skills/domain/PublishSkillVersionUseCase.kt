// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.domain

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.skills.domain.SkillRules.publish
import kotlin.time.Clock

/**
 * Publishes immutable skill versions: a team's (from the console or CI) or a member's personal one.
 */
interface PublishSkillVersionUseCase {
    /**
     * Refuses a caller that may not publish to the team, before a multi-megabyte body is read.
     *
     * @throws CatalogForbiddenException For a member, a person's token, or a key without `skills:publish`.
     */
    fun authorize(caller: CatalogCaller)

    /**
     * Publishes a team version. With [pin] it also becomes the delivered version (a key needs `skills:pin`); with
     * [allMembers] the skill reaches every member without a decision (a key needs `skills:assign`). Both are
     * idempotent, and absent they change nothing members receive.
     *
     * @return What the publication did.
     */
    suspend operator fun invoke(
        caller: CatalogCaller,
        name: String,
        version: String,
        files: List<ArtifactFile>,
        pin: Boolean,
        allMembers: Boolean,
    ): Publication

    /**
     * Refuses a caller that may not publish a personal skill, before the body is read.
     *
     * @throws CatalogForbiddenException For a service key or a credential without `skills:write`.
     */
    fun authorizePersonal(caller: CatalogCaller)

    /**
     * Publishes a version of one of the caller's personal skills, delivered to them only; [pin] approves it for
     * themself.
     *
     * @return What the publication did.
     */
    suspend fun personal(
        caller: CatalogCaller,
        name: String,
        version: String,
        files: List<ArtifactFile>,
        pin: Boolean,
    ): Publication
}

/**
 * [PublishSkillVersionUseCase]: the name, the label and the artifact are checked before anything is stored, the rules
 * of the current state inside the organization's write transaction, and each change is audited once committed.
 */
internal class PublishSkillVersionUseCaseImpl(
    private val repository: SkillsRepository,
    private val sha256: Sha256,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : PublishSkillVersionUseCase {
    override fun authorize(caller: CatalogCaller) = CatalogAccess.requirePublisher(caller, SkillScopes.PUBLISH)

    override suspend fun invoke(
        caller: CatalogCaller,
        name: String,
        version: String,
        files: List<ArtifactFile>,
        pin: Boolean,
        allMembers: Boolean,
    ): Publication {
        authorize(caller)
        if (pin) CatalogAccess.requireKeyScope(caller, SkillScopes.PIN)
        if (allMembers) CatalogAccess.requireKeyScope(caller, SkillScopes.ASSIGN)
        return store(caller, name, version, files, pin, allMembers, owner = null)
    }

    override fun authorizePersonal(caller: CatalogCaller) = PersonalAccess.requireOwner(caller, SkillScopes.WRITE)

    override suspend fun personal(
        caller: CatalogCaller,
        name: String,
        version: String,
        files: List<ArtifactFile>,
        pin: Boolean,
    ): Publication {
        authorizePersonal(caller)
        return store(caller, name, version, files, pin, allMembers = false, owner = caller.memberId)
    }

    @Suppress("LongParameterList") // The fields of one publication.
    private suspend fun store(
        caller: CatalogCaller,
        name: String,
        version: String,
        files: List<ArtifactFile>,
        pin: Boolean,
        allMembers: Boolean,
        owner: String?,
    ): Publication {
        ArtifactTree.checkName(name, KIND)
        ArtifactTree.checkVersion(version, KIND)
        val artifact = validate(files)
        val events = mutableListOf<CatalogEvent>()
        val publication = repository.write(caller.orgId) {
            publish(caller, caller.orgId, name, version, artifact, pin, allMembers, owner, clock.now(), events)
        }
        audit.recordAll(caller, events)
        return publication
    }

    private suspend fun validate(files: List<ArtifactFile>): ValidatedArtifact {
        val entries = ArtifactTree.entries(files, SkillLimits.FILES, SkillLimits.BYTES, KIND, sha256)
        ensureValid(entries.any { it.path == SkillLimits.ENTRY_FILE }, "invalid_skill_artifact") {
            "An artifact holds ${SkillLimits.ENTRY_FILE} at its root."
        }
        return ValidatedArtifact(ArtifactTree.digest(entries, sha256), files.sortedBy { it.path }, entries)
    }

    private companion object {
        const val KIND = "skill"
    }
}

/** Who manages a personal skill: its owner, with a person's credential. */
internal object PersonalAccess {
    /**
     * A person (the console, or a person's token holding [scope]); never a service key.
     *
     * @throws CatalogForbiddenException For a service key.
     * @throws CredentialScopeException For a token without [scope].
     */
    fun requireOwner(caller: CatalogCaller, scope: String) {
        if (caller.credential == CredentialKind.SERVICE_KEY) {
            throw CatalogForbiddenException("Personal skills are managed with a person's credential, never a key.")
        }
        if (!caller.allows(scope)) throw CredentialScopeException(scope)
    }
}
