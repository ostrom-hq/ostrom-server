// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid

/** The size of a page of `GET /api/v1/audit`. */
const val ORG_AUDIT_PAGE: Int = 50

/**
 * What a read of an organization's audit log asks for.
 *
 * @property actorId Only the events of this person, or all.
 * @property action Only the events of this name (`org.deleted`), or all.
 * @property beforeSequence Only the events before this position of the chain (keyset pagination), or the newest.
 * @property limit At most this many.
 */
data class OrgAuditQuery(
    val actorId: Uuid? = null,
    val action: String? = null,
    val beforeSequence: Long? = null,
    val limit: Int = ORG_AUDIT_PAGE,
)

/**
 * A line of an organization's audit log, with the login of the person who acted.
 *
 * @property event The signed event.
 * @property actorLogin The actor's login, or null for the server itself or a person who no longer exists.
 */
data class OrgAuditLine(val event: AuditEvent, val actorLogin: String?)

/**
 * A page of an organization's audit log.
 *
 * @property lines The lines, newest first.
 * @property next The cursor of the next page (`before`), or null when this is the last one.
 */
data class OrgAuditPage(val lines: List<OrgAuditLine>, val next: String?)

/** Reads an organization's audit log, newest first, by pages: the caller is an owner or an admin of it. */
interface ListOrgAuditUseCase {
    /**
     * Reads a page.
     *
     * @param orgId The organization.
     * @param actorLogin Only this person's events, by login; an unknown login has none.
     * @param action Only the events of this name.
     * @param before The `next` of the previous page, or null for the newest.
     *
     * @return The page.
     *
     * @throws InvalidAuditCursorException When [before] is not a cursor of this log.
     */
    suspend operator fun invoke(orgId: Uuid, actorLogin: String?, action: String?, before: String?): OrgAuditPage
}

/**
 * [ListOrgAuditUseCase] on the audit repository and the people.
 *
 * @param events The audit repository.
 * @param users The people, for the logins of the actors.
 */
class ListOrgAuditUseCaseImpl(
    private val events: AuditRepository,
    private val users: UsersRepository,
) : ListOrgAuditUseCase {
    override suspend fun invoke(orgId: Uuid, actorLogin: String?, action: String?, before: String?): OrgAuditPage {
        val beforeSequence = before?.let { it.toLongOrNull()?.takeIf { sequence -> sequence > 0 } }
        if (before != null && beforeSequence == null) throw InvalidAuditCursorException()
        val actorId = actorLogin?.let { users.findByLogin(it)?.id ?: return OrgAuditPage(emptyList(), null) }
        // One line more than a page: when it comes, there is a next page; never a cursor to an empty one.
        val read = events.listOrg(orgId, OrgAuditQuery(actorId, action, beforeSequence, ORG_AUDIT_PAGE + 1))
        val page = read.take(ORG_AUDIT_PAGE)
        val logins = users.getMany(page.mapNotNull { it.entry.actorId }.toSet()).associate { it.id to it.login }
        val next = if (read.size > ORG_AUDIT_PAGE) page.last().sequence.toString() else null
        return OrgAuditPage(page.map { OrgAuditLine(it, it.entry.actorId?.let(logins::get)) }, next)
    }
}

/** `before` is not the `next` of a page of the audit log: 400. */
class InvalidAuditCursorException :
    OstromException(ErrorKind.INVALID_REQUEST, "before is not a cursor of the audit log: pass the next of a page.")

/** A member who is neither an owner nor an admin reads the audit log: 403. */
class AuditReadForbiddenException :
    OstromException(ErrorKind.FORBIDDEN, "Only an owner or an admin reads the organization's audit log.")
