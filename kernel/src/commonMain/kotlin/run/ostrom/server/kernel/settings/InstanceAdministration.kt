// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.settings

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Who administers the instance: the people who configure what serves every organization (clusters, the GitHub App,
 * sign-in providers, mail, push, provider keys, billing). The admin module binds it on its stored administrators.
 */
fun interface InstanceAdministrators {
    /**
     * Whether a person administers the instance.
     *
     * @param userId The person's account id.
     *
     * @return True when they do.
     */
    suspend fun isAdmin(userId: Uuid): Boolean
}

/** Nobody: the default of a server built without the admin module (a module's own tests). */
class NoInstanceAdministrators : InstanceAdministrators {
    override suspend fun isAdmin(userId: Uuid): Boolean = false
}

/**
 * Makes the person who used the instance's setup link (`ostrom-server bootstrap`) its first administrator. The
 * identity module calls it when the link is used; the admin module binds it.
 */
fun interface InstanceAdminGrants {
    /**
     * Records the person who used the setup link as an administrator, unless they already are or were removed.
     *
     * @param userId The person.
     * @param at When they used the link.
     */
    suspend fun grantSetupOwner(userId: Uuid, at: Instant)
}

/** Grants nothing: the default of a server built without the admin module. */
class IgnoringInstanceAdminGrants : InstanceAdminGrants {
    override suspend fun grantSetupOwner(userId: Uuid, at: Instant) = Unit
}

/**
 * An account of the instance, as the instance's administration shows it.
 *
 * @property userId The account id.
 * @property login The handle.
 * @property email The verified primary e-mail, if any.
 */
data class InstanceAccount(val userId: Uuid, val login: String, val email: String?)

/** The accounts of the instance, as the admin module reads them; the identity module binds it. */
interface InstanceAccounts {
    /**
     * The account a verified e-mail belongs to.
     *
     * @param email The address, any case.
     *
     * @return The account, or null when no account has verified it.
     */
    suspend fun byVerifiedEmail(email: String): InstanceAccount?

    /**
     * Describes accounts.
     *
     * @param userIds The accounts.
     *
     * @return Each account found, by id.
     */
    suspend fun describe(userIds: Collection<Uuid>): Map<Uuid, InstanceAccount>

    /**
     * The people who used a setup link of the instance, and when.
     *
     * @return Each person, with the time they used it.
     */
    suspend fun setupOwners(): Map<Uuid, Instant>
}

/** No account: the default of a server built without the identity module. */
class NoInstanceAccounts : InstanceAccounts {
    override suspend fun byVerifiedEmail(email: String): InstanceAccount? = null

    override suspend fun describe(userIds: Collection<Uuid>): Map<Uuid, InstanceAccount> = emptyMap()

    override suspend fun setupOwners(): Map<Uuid, Instant> = emptyMap()
}

/**
 * A change of the instance's administration written to the instance's signed audit log (the chain of no
 * organization): metadata only, never a secret's value.
 *
 * @property actorId Who made it; null when the server did (an import from the environment at start).
 * @property event What happened, a stable dotted name (`instance.settings.saved`).
 * @property subjectType What kind of thing (`settings`, `instance_admin`).
 * @property subjectId Which one.
 * @property details Extra facts, never a secret.
 */
data class InstanceAuditRecord(
    val actorId: Uuid?,
    val event: String,
    val subjectType: String,
    val subjectId: String,
    val details: Map<String, String> = emptyMap(),
)

/** Writes the changes of the instance's administration to its signed audit log; the identity module binds it. */
fun interface InstanceAuditTrail {
    /**
     * Records a change, after it is made.
     *
     * @param record The change.
     */
    suspend fun record(record: InstanceAuditRecord)
}

/** Writes nothing: the default of a server built without the identity module. */
class DiscardingInstanceAuditTrail : InstanceAuditTrail {
    override suspend fun record(record: InstanceAuditRecord) = Unit
}
