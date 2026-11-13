// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.DuplicateIdentityException
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.UserEmail
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.domain.VerifiedIdentity
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [UsersRepository] on PostgreSQL (tables of [IdentityMigrations]).
 *
 * @param driver The database.
 */
class UsersRepositoryImpl(private val driver: Driver) : UsersRepository {
    override suspend fun get(id: Uuid): User? =
        driver.fetchAll(Statement.create("SELECT $USER_COLUMNS FROM identity_users u WHERE u.id = :id").bind("id", id))
            .getOrThrow().firstOrNull()?.toUser()

    override suspend fun findByIdentity(issuer: String, subject: String): User? = driver.fetchAll(
        Statement.create(
            "SELECT $USER_COLUMNS FROM identity_identities i JOIN identity_users u ON u.id = i.user_id " +
                "WHERE i.issuer = :issuer AND i.subject = :subject",
        ).bind("issuer", issuer).bind("subject", subject),
    ).getOrThrow().firstOrNull()?.toUser()

    override suspend fun findByVerifiedEmail(email: String): User? = driver.fetchAll(
        Statement.create(
            "SELECT $USER_COLUMNS FROM identity_user_emails e JOIN identity_users u ON u.id = e.user_id " +
                "WHERE e.email = :email AND e.verified",
        ).bind("email", email),
    ).getOrThrow().firstOrNull()?.toUser()

    override suspend fun loginTaken(login: String): Boolean = driver.fetchAll(
        Statement.create("SELECT 1 FROM identity_users WHERE login = :login").bind("login", login),
    ).getOrThrow().firstOrNull() != null

    override suspend fun findByLogin(login: String): User? = driver.fetchAll(
        Statement.create("SELECT $USER_COLUMNS FROM identity_users u WHERE u.login = :login")
            .bind("login", login.lowercase()),
    ).getOrThrow().firstOrNull()?.toUser()

    override suspend fun getMany(ids: Collection<Uuid>): List<User> {
        if (ids.isEmpty()) return emptyList()
        val distinct = ids.distinct()
        // One bound parameter per id: no list binding, and never a value spliced into the SQL.
        val names = distinct.indices.joinToString { ":id$it" }
        val statement = Statement.create("SELECT $USER_COLUMNS FROM identity_users u WHERE u.id IN ($names)")
        distinct.forEachIndexed { index, id -> statement.bind("id$index", id) }
        return driver.fetchAll(statement).getOrThrow().map { it.toUser() }
    }

    override suspend fun create(user: User, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = uniqueGuard {
        driver.transaction {
            execute(
                Statement.create(
                    "INSERT INTO identity_users (id, login, display_name, primary_email, avatar_url, created_at) " +
                        "VALUES (:id, :login, :name, :email, :avatar, :at)",
                ).bind("id", user.id).bind("login", user.login).bind("name", user.displayName)
                    .bind("email", user.primaryEmail)
                    .bind("avatar", user.avatarUrl).bind("at", user.createdAt),
            ).getOrThrow()
            insertIdentity(this, user.id, identity, at)
            if (email != null) upsertEmail(this, user.id, email, at)
        }
    }

    override suspend fun link(userId: Uuid, identity: VerifiedIdentity, email: UserEmail?, at: Instant) = uniqueGuard {
        driver.transaction {
            insertIdentity(this, userId, identity, at)
            if (email != null) upsertEmail(this, userId, email, at)
        }
    }

    override suspend fun listEmails(userId: Uuid): List<UserEmail> = driver.fetchAll(
        Statement.create(
            "SELECT email, verified FROM identity_user_emails WHERE user_id = :id ORDER BY verified DESC, " +
                "email LIMIT 50",
        ).bind("id", userId),
    ).getOrThrow().map { UserEmail(it.get("email").asString(), it.get("verified").asBoolean()) }

    override suspend fun listIssuers(userId: Uuid): List<String> = driver.fetchAll(
        Statement.create("SELECT DISTINCT issuer FROM identity_identities WHERE user_id = :id ORDER BY issuer LIMIT 50")
            .bind("id", userId),
    ).getOrThrow().map { it.get("issuer").asString() }

    private companion object {
        const val USER_COLUMNS = "u.id, u.login, u.display_name, u.primary_email, u.avatar_url, u.created_at"
    }
}

private suspend fun insertIdentity(executor: QueryExecutor, userId: Uuid, identity: VerifiedIdentity, at: Instant) {
    // A known identity of the same user only refreshes its last use. Of another user, nothing is written and the
    // call fails: the use case found it free a moment ago, so another sign-in took it in between (a race), and the
    // caller retries, which then finds it. Writing nothing silently would leave a user without a way in.
    val written = executor.execute(
        Statement.create(
            "INSERT INTO identity_identities (issuer, subject, user_id, email, created_at, last_used_at) " +
                "VALUES (:issuer, :subject, :user, :email, :at, :at) " +
                "ON CONFLICT (issuer, subject) DO UPDATE SET last_used_at = EXCLUDED.last_used_at " +
                "WHERE identity_identities.user_id = EXCLUDED.user_id",
        ).bind("issuer", identity.issuer).bind("subject", identity.subject).bind("user", userId)
            .bind("email", identity.email).bind("at", at),
    ).getOrThrow()
    if (written == 0L) throw DuplicateIdentityException()
}

private suspend fun upsertEmail(executor: QueryExecutor, userId: Uuid, email: UserEmail, at: Instant) {
    // An e-mail becomes verified, never unverified.
    executor.execute(
        Statement.create(
            "INSERT INTO identity_user_emails (user_id, email, verified, created_at) VALUES (:user, " +
                ":email, :verified, :at) " +
                "ON CONFLICT (user_id, email) DO UPDATE SET verified = identity_user_emails.verified OR " +
                "EXCLUDED.verified",
        ).bind("user", userId).bind("email", email.email).bind("verified", email.verified).bind("at", at),
    ).getOrThrow()
}

// Not inline: one copy, whichever repository method fails.
private suspend fun <T> uniqueGuard(block: suspend () -> T): T = try {
    block()
} catch (error: SQLError) {
    if (error.kind == SQLError.Kind.UniqueViolation) throw DuplicateIdentityException()
    throw error
}

private fun ResultSet.Row.toUser() = User(
    id = get("id").asUuid(),
    login = get("login").asString(),
    displayName = get("display_name").asString(),
    primaryEmail = get("primary_email").asStringOrNull(),
    avatarUrl = get("avatar_url").asStringOrNull(),
    createdAt = get("created_at").asInstant(),
)
