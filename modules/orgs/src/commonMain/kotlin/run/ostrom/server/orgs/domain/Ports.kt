// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs.domain

import kotlin.uuid.Uuid

/**
 * A person as the organization screens show them, read from the identity module.
 *
 * @property id The user.
 * @property login The unique handle, the member's name in the API.
 * @property displayName The name.
 * @property avatarUrl The picture, if any.
 * @property email The primary e-mail, if any.
 */
data class Person(
    val id: Uuid,
    val login: String,
    val displayName: String,
    val avatarUrl: String?,
    val email: String?,
)

/** The people of the instance (the identity module's users). */
interface People {
    /**
     * Reads a person.
     *
     * @param id The user.
     * @return The person, or null.
     */
    suspend fun get(id: Uuid): Person?

    /**
     * Reads several people.
     *
     * @param ids The users.
     * @return The people found, in no particular order.
     */
    suspend fun getMany(ids: Collection<Uuid>): List<Person>

    /**
     * Finds a person by login.
     *
     * @param login The login, any case.
     * @return The person, or null.
     */
    suspend fun findByLogin(login: String): Person?

    /**
     * The e-mail addresses a person proved they own, lower case.
     *
     * @param id The user.
     * @return The addresses.
     */
    suspend fun verifiedEmails(id: Uuid): Set<String>
}

/**
 * How many people an organization's plan allows. The billing module answers it once it is wired;
 * until then [FreePlanSeats] applies the free plan's limit to every organization.
 */
interface SeatPolicy {
    /**
     * Whether the organization may grow to [people] people.
     *
     * @param orgId The organization.
     * @param people The number of people after the addition (members, plus pending e-mail
     *   invitations).
     * @return True when allowed.
     */
    suspend fun allows(orgId: Uuid, people: Int): Boolean

    /**
     * Whether the organization pays: a paying organization does not hold its free claim
     * exclusively.
     *
     * @param orgId The organization.
     * @return True when it pays.
     */
    suspend fun isPaying(orgId: Uuid): Boolean
}

/**
 * The free plan for every organization (Free: up to [limit] people), until the billing module
 * answers.
 *
 * @param limit The free plan's number of people.
 */
class FreePlanSeats(private val limit: Int = FREE_PLAN_PEOPLE) : SeatPolicy {
    override suspend fun allows(orgId: Uuid, people: Int): Boolean = people <= limit

    override suspend fun isPaying(orgId: Uuid): Boolean = false
}

/** The free plan's number of people (Lyes's price grid: Free up to 5 people). */
const val FREE_PLAN_PEOPLE = 5

/** Sends the invitation e-mails. */
interface InvitationMailer {
    /**
     * Sends an invitation.
     *
     * @param to The address.
     * @param org The organization.
     * @param inviter Who invited.
     * @param link The link that accepts it.
     */
    suspend fun send(to: String, org: Org, inviter: Person, link: String)
}

/**
 * A sensitive action of an organization, for the signed audit log.
 *
 * @property orgId The organization.
 * @property actorId The user who acted.
 * @property action The action, such as `org.member.removed`.
 * @property targetType What it acted on, such as `member`.
 * @property targetId Its id.
 * @property details What changed.
 */
data class OrgEvent(
    val orgId: Uuid,
    val actorId: Uuid,
    val action: String,
    val targetType: String,
    val targetId: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Records the sensitive actions of an organization in the signed audit log (the identity module's).
 */
interface OrgAudit {
    /**
     * Records an action.
     *
     * @param event The action.
     */
    suspend fun record(event: OrgEvent)
}

/** Generates and hashes the invitation tokens. */
interface InvitationTokens {
    /**
     * A new random token for a link.
     *
     * @return The token.
     */
    fun generate(): String

    /**
     * The stored hash of a token.
     *
     * @param token The token.
     * @return Its hash.
     */
    suspend fun hash(token: String): String
}

/** Limits how often a caller may do something. */
interface OrgRateLimiter {
    /**
     * Counts an attempt.
     *
     * @param key What is limited, such as `org-create:<user>`.
     * @param limit How many attempts per window.
     * @param windowMinutes The window.
     * @return True when allowed.
     */
    suspend fun tryAcquire(key: String, limit: Int, windowMinutes: Int): Boolean
}
