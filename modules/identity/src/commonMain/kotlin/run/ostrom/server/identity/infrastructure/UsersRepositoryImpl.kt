// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.DuplicateIdentityException
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UserEmail
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [UsersRepository] on PostgreSQL through Exposed ([IdentityUsers], [IdentityIdentities], [IdentityUserEmails]); no
 * organization's rows.
 *
 * @param database The JDBC pool.
 */
class UsersRepositoryImpl(private val database: JdbcDatabase) : UsersRepository {
    private val u = IdentityUsers

    override suspend fun get(id: Uuid): User? = database.query {
        u.select(columns).where { u.id eq id }.singleOrNull()?.toUser()
    }

    override suspend fun findByIdentity(issuer: String, subject: String): User? = database.query {
        val i = IdentityIdentities
        i.join(u, JoinType.INNER, i.userId, u.id)
            .select(columns)
            .where { (i.issuer eq issuer) and (i.subject eq subject) }
            .singleOrNull()
            ?.toUser()
    }

    override suspend fun findByVerifiedEmail(email: String): User? = database.query {
        val e = IdentityUserEmails
        e.join(u, JoinType.INNER, e.userId, u.id)
            .select(columns)
            .where { (e.email eq email) and (e.verified eq true) }
            .singleOrNull()
            ?.toUser()
    }

    override suspend fun loginTaken(login: String): Boolean = database.query {
        !u.select(u.id).where { u.login eq login }.limit(1).empty()
    }

    override suspend fun findByLogin(login: String): User? = database.query {
        u.select(columns).where { u.login eq login.lowercase() }.singleOrNull()?.toUser()
    }

    override suspend fun getMany(ids: Collection<Uuid>): List<User> {
        if (ids.isEmpty()) return emptyList()
        val distinct = ids.distinct()
        // One bound parameter per id, never a value spliced into the SQL.
        return database.query {
            u.select(columns).where { u.id inList distinct }.limit(distinct.size).map { it.toUser() }
        }
    }

    override suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = uniqueGuard {
        database.query {
            u.insert {
                it[id] = user.id
                it[login] = user.login
                it[displayName] = user.displayName
                it[primaryEmail] = user.primaryEmail
                it[avatarUrl] = user.avatarUrl
                it[createdAt] = user.createdAt
            }
            insertIdentity(user.id, identity, at)
            if (email != null) upsertEmail(user.id, email, at)
        }
    }

    override suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = uniqueGuard {
        database.query {
            insertIdentity(userId, identity, at)
            if (email != null) upsertEmail(userId, email, at)
        }
    }

    override suspend fun listEmails(userId: Uuid): List<UserEmail> = database.query {
        val e = IdentityUserEmails
        e.select(e.email, e.verified)
            .where { e.userId eq userId }
            .orderBy(e.verified to SortOrder.DESC, e.email to SortOrder.ASC)
            .limit(MAX_LISTED)
            .map { UserEmail(it[e.email], it[e.verified]) }
    }

    override suspend fun listIssuers(userId: Uuid): List<String> = database.query {
        val i = IdentityIdentities
        i.select(i.issuer).withDistinct()
            .where { i.userId eq userId }
            .orderBy(i.issuer)
            .limit(MAX_LISTED)
            .map { it[i.issuer] }
    }

    private val columns = listOf(u.id, u.login, u.displayName, u.primaryEmail, u.avatarUrl, u.createdAt)

    private companion object {
        const val MAX_LISTED = 50
    }
}

private fun ResultRow.toUser(): User {
    val u = IdentityUsers
    return User(
        id = this[u.id],
        login = this[u.login],
        displayName = this[u.displayName],
        primaryEmail = this[u.primaryEmail],
        avatarUrl = this[u.avatarUrl],
        createdAt = this[u.createdAt],
    )
}

private fun JdbcTransaction.insertIdentity(userId: Uuid, identity: VerifiedIdentity, at: Instant) {
    // A known identity of the same user only refreshes its last use. Of another user, nothing is written and the
    // call fails: the use case found it free a moment ago, so another sign-in took it in between (a race), and the
    // caller retries, which then finds it. Writing nothing silently would leave a user without a way in.
    val i = IdentityIdentities
    val written = i.upsert(
        i.issuer,
        i.subject,
        onUpdate = { it[i.lastUsedAt] = insertValue(i.lastUsedAt) },
        // The row's user is the one being written (EXCLUDED.user_id), bound as such.
        where = { i.userId eq userId },
    ) {
        it[issuer] = identity.issuer
        it[subject] = identity.subject
        it[this.userId] = userId
        it[email] = identity.email
        it[createdAt] = at
        it[lastUsedAt] = at
    }.insertedCount
    if (written == 0) throw DuplicateIdentityException()
}

private fun JdbcTransaction.upsertEmail(userId: Uuid, email: UserEmail, at: Instant) {
    // An e-mail becomes verified, never unverified.
    val e = IdentityUserEmails
    e.upsert(e.userId, e.email, onUpdate = { it[e.verified] = e.verified or insertValue(e.verified) }) {
        it[this.userId] = userId
        it[this.email] = email.email
        it[verified] = email.verified
        it[createdAt] = at
    }
}

/** PostgreSQL's SQLSTATE of a unique violation. */
private const val UNIQUE_VIOLATION = "23505"

// Not inline: one copy, whichever repository method fails. Another sign-in that verified the same e-mail at the same
// moment breaks the partial unique index `identity_user_emails_verified` (one verified owner per e-mail). Kept as a
// catch, not an insertIgnore and a count: the e-mail's write must update the user's own row on its primary key
// (`verified OR EXCLUDED.verified`), and a DO UPDATE has that one arbiter, so the same statement cannot also skip the
// partial index. Splitting it (update the own row, insert ignoring every conflict, then read which conflict it was)
// takes three statements and leaves a window between the insert and the read; the violation is the database's own
// answer, atomic, and rolls the whole transaction back (user, identity, e-mail) as the caller expects.
private suspend fun <T> uniqueGuard(block: suspend () -> T): T = try {
    block()
} catch (error: ExposedSQLException) {
    if (error.sqlState == UNIQUE_VIOLATION) throw DuplicateIdentityException()
    throw error
}
