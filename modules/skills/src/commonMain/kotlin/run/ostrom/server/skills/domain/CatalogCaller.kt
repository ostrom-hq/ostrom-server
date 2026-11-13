// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.domain

import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.uuid.Uuid

/** The kind of credential a catalog request comes with: who may do what follows from it. */
enum class CredentialKind {
    /** A person's browser session in the console: the only credential that administers a catalog. */
    CONSOLE,

    /** A person's token a program presents: the CLI's (a workstation), or a personal token. */
    PERSON_TOKEN,

    /** A service key, acting as the person who created it (always as a plain member, rights come from its scopes). */
    SERVICE_KEY,
}

/**
 * Who calls the skill or hook catalog, resolved from the request's credential.
 *
 * @property orgId The organization the request acts in.
 * @property memberId The member it acts for (a key's creator).
 * @property administrator Whether that member is an owner or an admin. A service key is never resolved as one; a key
 *   holding a reserved scope (`skills:publish`…) is resolved at all only while its creator is an owner or an admin.
 * @property credential The kind of credential.
 * @property scopes What it holds; `user:*` for a browser session.
 * @property workstation The workstation's name for the CLI's token (it signs in one), null for any other credential.
 * @property credentialId The credential's id (the workstation's id for the CLI's token).
 * @property principal The authenticated principal, for the audit log.
 */
data class CatalogCaller(
    val orgId: Uuid,
    val memberId: String,
    val administrator: Boolean,
    val credential: CredentialKind,
    val scopes: Set<String>,
    val workstation: String?,
    val credentialId: String?,
    val principal: AuthenticatedPrincipal,
) {
    /**
     * Whether the credential may do what [scope] names: a browser session, a scope it lists, or a person's credential
     * holding every scope of its person (`user:*`, the unlocked app's).
     *
     * @param scope The scope.
     *
     * @return True when allowed.
     */
    fun allows(scope: String): Boolean = credential == CredentialKind.CONSOLE || scope in scopes ||
        (credential == CredentialKind.PERSON_TOKEN && ALL_USER_SCOPES in scopes)

    /** The key's id for its audit events; null for a person's credential. */
    val keyId: String? get() = credentialId.takeIf { credential == CredentialKind.SERVICE_KEY }
}

/** The scopes of the skill catalog. */
object SkillScopes {
    /** Publishing skill versions (an owner's or an admin's key). */
    const val PUBLISH: String = "skills:publish"

    /** With [PUBLISH]: pinning at publication or afterwards. */
    const val PIN: String = "skills:pin"

    /** With [PUBLISH]: delivering to all members at publication. */
    const val ASSIGN: String = "skills:assign"

    /** Reading one's own delivery. */
    const val READ: String = "skills:read"

    /** Writing one's own personal skills (the CLI's token). */
    const val WRITE: String = "skills:write"
}

/**
 * Who receives a skill (TeamCode's `SkillAccessPolicy`): an explicit member decision wins; with none, the audience
 * decides, all members delivering and members-set-to-yes delivering nothing. All members is therefore the weakest
 * rule: an explicit *no* always excludes. Groups will slot in between, a group *no* over a group *yes*, when they
 * carry decisions.
 */
object AccessPolicy {
    /**
     * Whether a member receives an item.
     *
     * @param decision The member's explicit decision, if any.
     * @param allMembers The item's audience.
     *
     * @return True when delivered.
     */
    fun allowed(decision: Decision?, allMembers: Boolean): Boolean =
        if (decision == null) allMembers else decision == Decision.ALLOW
}

/** The refusals of the catalog's callers, shared by the skill and hook use cases. */
object CatalogAccess {
    /**
     * An owner or an admin in the console.
     *
     * @throws CatalogForbiddenException Otherwise.
     */
    fun requireConsoleAdministrator(caller: CatalogCaller) {
        if (caller.credential != CredentialKind.CONSOLE || !caller.administrator) {
            throw CatalogForbiddenException("Only an owner or an admin manages the catalog, from the console.")
        }
    }

    /**
     * A publisher: an owner or an admin in the console, or a key holding [scope] (a reserved scope: the key resolves
     * only while its creator is an owner or an admin).
     *
     * @throws CredentialScopeException For a key without [scope].
     * @throws CatalogForbiddenException For any other credential.
     */
    fun requirePublisher(caller: CatalogCaller, scope: String) {
        when (caller.credential) {
            CredentialKind.CONSOLE -> requireConsoleAdministrator(caller)
            CredentialKind.SERVICE_KEY -> requireKeyScope(caller, scope)
            CredentialKind.PERSON_TOKEN ->
                throw CatalogForbiddenException("Publish from the console or with a service key that holds $scope.")
        }
    }

    /**
     * A key's scope; a person in the console passes.
     *
     * @throws CredentialScopeException For a key without [scope].
     */
    fun requireKeyScope(caller: CatalogCaller, scope: String) {
        if (caller.credential == CredentialKind.SERVICE_KEY && scope !in caller.scopes) {
            throw CredentialScopeException(scope)
        }
    }

    /**
     * A reader of [memberId]'s delivery: the member themself with [scope], or an owner or an admin in the console.
     *
     * @throws CatalogForbiddenException Otherwise.
     */
    fun requireReader(caller: CatalogCaller, memberId: String, scope: String) {
        if (memberId != caller.memberId) return requireConsoleAdministrator(caller)
        if (!caller.allows(scope)) throw CredentialScopeException(scope)
    }
}
