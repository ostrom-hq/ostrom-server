// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The roles that manage an organization's service keys. */
private val KEY_ADMINS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/**
 * The member, refused unless an owner or an admin of the organization.
 *
 * @throws ServiceKeysAdminRequiredException Otherwise.
 */
private fun OrgPrincipal.requireKeyAdmin(): OrgPrincipal {
    if (role !in KEY_ADMINS) throw ServiceKeysAdminRequiredException()
    return this
}

/**
 * Creates a service key of an organization, with some of [SERVICE_KEY_SCOPES] and the lifetime a personal token takes:
 * a key of the CI scopes acts for the organization, a key of member scopes acts as its creator, within those scopes.
 * Only an owner or an admin creates one, so the [RESERVED_SERVICE_KEY_SCOPES] are always given by one; the key itself
 * checks it again at every call. Only its SHA-256 is stored; its secret exists once, in the answer.
 */
interface CreateServiceKeyUseCase {
    /**
     * Creates the key.
     *
     * @param member The member creating it, in its organization.
     * @param userId The person behind [member], recorded as its creator and in the audit log.
     * @param name Its label.
     * @param scopes What it allows: at least one of [SERVICE_KEY_SCOPES], nothing else; the CI scopes are not mixed
     *   with the others.
     * @param lifetime How long it lives (1 to 366 days), or null for a key that does not expire.
     *
     * @return The key and its secret, shown once.
     *
     * @throws ServiceKeysAdminRequiredException, InvalidTokenNameException, InvalidServiceKeyScopesException,
     *   InvalidTokenLifetimeException, TooManyServiceKeysException
     */
    suspend operator fun invoke(
        member: OrgPrincipal,
        userId: Uuid,
        name: String,
        scopes: Collection<String>,
        lifetime: Duration?,
    ): IssuedToken
}

/**
 * [CreateServiceKeyUseCase] on the tokens repository.
 *
 * @param tokens The tokens, where the key is created.
 * @param keys The organization's keys, for the cap.
 * @param secrets Makes secrets.
 * @param hasher Hashes them.
 * @param audit The audit log.
 * @param clock The clock.
 */
class CreateServiceKeyUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val keys: ServiceKeysRepository,
    private val secrets: SecretGenerator,
    private val hasher: SecretHasher,
    private val audit: AuditLog,
    private val clock: Clock,
) : CreateServiceKeyUseCase {
    override suspend fun invoke(
        member: OrgPrincipal,
        userId: Uuid,
        name: String,
        scopes: Collection<String>,
        lifetime: Duration?,
    ): IssuedToken {
        val orgId = member.requireKeyAdmin().orgId
        val label = tokenName(name)
        val validScopes = scopes.toSet()
        // The CI scopes act for the organization, the others as the creator: one key is never both.
        val mixed = validScopes.any { it in CI_SERVICE_KEY_SCOPES } && validScopes.any { it !in CI_SERVICE_KEY_SCOPES }
        if (validScopes.isEmpty() || !SERVICE_KEY_SCOPES.containsAll(validScopes) || mixed) {
            throw InvalidServiceKeyScopesException()
        }
        if (lifetime != null && (lifetime < 1.days || lifetime > MAX_TOKEN_LIFETIME)) {
            throw InvalidTokenLifetimeException()
        }
        val now = clock.nowMillis()
        val live = keys.listLive(orgId, now)
        if (live.size >= MAX_SERVICE_KEYS_PER_ORG) throw TooManyServiceKeysException()
        val issued = issue(secrets, TokenKind.SERVICE) { id, hint ->
            val expiresAt = lifetime?.let { now + it }
            ApiToken(id, userId, orgId, label, TokenKind.SERVICE, validScopes, hint, null, now, expiresAt, null, null)
        }
        tokens.create(issued.token, hasher.hash(issued.secret))
        val details = mapOf("name" to label, "scopes" to validScopes.sorted().joinToString(" "))
        val keyId = issued.token.id.toString()
        // A key that never expires is its own event, for whoever watches the long-lived credentials (TC-KEY-6).
        val event = if (lifetime == null) "service_key.created_no_expiry" else "service_key.created"
        audit.record(AuditEntry(orgId, userId, event, "service_key", keyId, details))
        return issued
    }
}

/** Lists an organization's live service keys, for its owners and admins. */
interface ListServiceKeysUseCase {
    /**
     * Lists the keys.
     *
     * @param member The member asking, in its organization.
     *
     * @return The live keys, newest first, without their secrets.
     *
     * @throws ServiceKeysAdminRequiredException When the member is neither owner nor admin.
     */
    suspend operator fun invoke(member: OrgPrincipal): List<ApiToken>
}

/**
 * [ListServiceKeysUseCase] on the service keys repository.
 *
 * @param keys The keys.
 * @param clock The clock.
 */
class ListServiceKeysUseCaseImpl(
    private val keys: ServiceKeysRepository,
    private val clock: Clock,
) : ListServiceKeysUseCase {
    override suspend fun invoke(member: OrgPrincipal): List<ApiToken> =
        keys.listLive(member.requireKeyAdmin().orgId, clock.now())
}

/** Deletes (revokes) a service key of an organization: it stops working at once. */
interface DeleteServiceKeyUseCase {
    /**
     * Deletes the key.
     *
     * @param member The member deleting it, in its organization.
     * @param userId The person behind [member], for the audit log.
     * @param keyId The key.
     *
     * @throws ServiceKeysAdminRequiredException When the member is neither owner nor admin.
     * @throws ServiceKeyNotFoundException When the key is absent, another organization's, expired or already deleted.
     */
    suspend operator fun invoke(member: OrgPrincipal, userId: Uuid, keyId: Uuid)
}

/**
 * [DeleteServiceKeyUseCase] on the service keys repository.
 *
 * @param keys The keys.
 * @param audit The audit log.
 * @param clock The clock.
 */
class DeleteServiceKeyUseCaseImpl(
    private val keys: ServiceKeysRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : DeleteServiceKeyUseCase {
    override suspend fun invoke(member: OrgPrincipal, userId: Uuid, keyId: Uuid) {
        val orgId = member.requireKeyAdmin().orgId
        val at: Instant = clock.nowMillis()
        if (!keys.revoke(orgId, keyId, at)) throw ServiceKeyNotFoundException()
        audit.record(AuditEntry(orgId, userId, "service_key.deleted", "service_key", keyId.toString()))
    }
}
