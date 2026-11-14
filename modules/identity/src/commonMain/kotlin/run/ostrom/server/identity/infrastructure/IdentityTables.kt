// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

/*
 * The tables of [IdentityMigrations], as Exposed reads and writes them. The migrations create the schema; these
 * objects only name its columns and their types, and never create anything (no `SchemaUtils`). Two of them hold an
 * organization's rows under row-level security: [IdentityApiTokens] (its agent tokens, service keys and elevated
 * tokens) and [IdentityAuditEvents] (an organization's chain); the others are no organization's.
 */

internal object IdentityUsers : Table("identity_users") {
    val id = uuid("id")
    val login = text("login")
    val displayName = text("display_name")
    val primaryEmail = text("primary_email").nullable()
    val avatarUrl = text("avatar_url").nullable()
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(id)
}

internal object IdentityUserEmails : Table("identity_user_emails") {
    val userId = uuid("user_id")
    val email = text("email")
    val verified = bool("verified")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(userId, email)
}

internal object IdentityIdentities : Table("identity_identities") {
    val issuer = text("issuer")
    val subject = text("subject")
    val userId = uuid("user_id")
    val email = text("email").nullable()
    val createdAt = instant("created_at")
    val lastUsedAt = instant("last_used_at")

    override val primaryKey = PrimaryKey(issuer, subject)
}

/** Browser sessions: a credential table, read by the hash of the cookie, never `selectAll()`. */
internal object IdentityWebSessions : Table("identity_web_sessions") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val secretHash = text("secret_hash")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val lastSeenAt = instant("last_seen_at")
    val revokedAt = instant("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The columns of a token that are read back ([toApiToken]): never its hashes. */
internal interface TokenColumns {
    val id: Column<Uuid>
    val userId: Column<Uuid>
    val orgId: Column<Uuid?>
    val name: Column<String>
    val kind: Column<String>
    val scopes: Column<String>
    val hint: Column<String>
    val agentSessionId: Column<String?>
    val createdAt: Column<Instant>
    val expiresAt: Column<Instant?>
    val lastUsedAt: Column<Instant?>
    val revokedAt: Column<Instant?>
    val authenticatedAt: Column<Instant?>

    /** What a read of a token selects. */
    val read: List<Column<*>>
        get() = listOf(
            id, userId, orgId, name, kind, scopes, hint, agentSessionId, createdAt, expiresAt, lastUsedAt, revokedAt,
            authenticatedAt,
        )
}

/**
 * Every token (personal, CLI, agent, service, admin, the phone app's): a credential table and a tenant table. Its
 * account-level rows (`org_id IS NULL`) are reached outside any organization, an organization's inside it.
 */
internal object IdentityApiTokens : Table("identity_api_tokens"), TokenColumns {
    override val id = uuid("id")
    override val userId = uuid("user_id")
    override val orgId = uuid("org_id").nullable()
    override val name = text("name")
    override val kind = text("kind")
    override val scopes = text("scopes")
    override val hint = text("hint")
    override val agentSessionId = text("agent_session_id").nullable()
    val secretHash = text("secret_hash")
    override val createdAt = instant("created_at")
    override val expiresAt = instant("expires_at").nullable()
    override val lastUsedAt = instant("last_used_at").nullable()
    override val revokedAt = instant("revoked_at").nullable()
    val unlockKeyHash = text("unlock_key_hash").nullable()
    override val authenticatedAt = instant("authenticated_at").nullable()
    val unlockRequestId = text("unlock_request_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * The rows of `identity_token_by_hash(hash)` (migration 6), the `ostrom_router` function that finds a token of any
 * organization by its hash, read as a table: `SELECT … FROM identity_token_by_hash(?)`, the hash bound. PostgreSQL
 * names a function in `FROM` after it, so the columns read as `identity_token_by_hash.id`.
 *
 * @param hash The SHA-256 of the secret.
 */
internal class TokenByHash(private val hash: String) : Table("identity_token_by_hash"), TokenColumns {
    override val id = uuid("id")
    override val userId = uuid("user_id")
    override val orgId = uuid("org_id").nullable()
    override val name = text("name")
    override val kind = text("kind")
    override val scopes = text("scopes")
    override val hint = text("hint")
    override val agentSessionId = text("agent_session_id").nullable()
    override val createdAt = instant("created_at")
    override val expiresAt = instant("expires_at").nullable()
    override val lastUsedAt = instant("last_used_at").nullable()
    override val revokedAt = instant("revoked_at").nullable()
    override val authenticatedAt = instant("authenticated_at").nullable()

    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        append('(')
        registerArgument(TextColumnType(), hash)
        append(')')
    }
}

/** The device logins of `ostrom login`: a credential table, read by the hash of the device code. */
internal object IdentityDeviceAuthorizations : Table("identity_device_authorizations") {
    val id = uuid("id")
    val deviceCodeHash = text("device_code_hash")
    val userCode = text("user_code")
    val clientName = text("client_name")
    val status = text("status")
    val userId = uuid("user_id").nullable()
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val lastPolledAt = instant("last_polled_at").nullable()
    val scopes = text("scopes")
    val approvingOrgId = uuid("approving_org_id").nullable()
    val requestedFrom = text("requested_from").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The signed, chained audit log: an organization's chain is a tenant's, the account-level chain no one's. */
internal object IdentityAuditEvents : Table("identity_audit_events") {
    val id = uuid("id")
    val chain = text("chain")
    val sequence = long("sequence")
    val orgId = uuid("org_id").nullable()
    val actorId = uuid("actor_id").nullable()
    val action = text("action")
    val targetType = text("target_type")
    val targetId = text("target_id")

    /** JSON of a string map (`DETAILS`). */
    val details = text("details")
    val occurredAt = instant("occurred_at")
    val previousHash = text("previous_hash").nullable()
    val hash = text("hash")
    val signature = text("signature")
    val keyId = text("key_id")

    override val primaryKey = PrimaryKey(id)
}

/** OAuth sign-ins in progress: a credential table, read by the hash of the state. */
internal object IdentityOAuthAttempts : Table("identity_oauth_attempts") {
    val id = uuid("id")
    val stateHash = text("state_hash")
    val provider = text("provider")
    val connectionId = uuid("connection_id").nullable()
    val codeVerifier = text("code_verifier")
    val nonce = text("nonce")
    val redirectPath = text("redirect_path")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()
    val linkUserId = uuid("link_user_id").nullable()
    val mobileRedirectUri = text("mobile_redirect_uri").nullable()
    val mobileCodeChallenge = text("mobile_code_challenge").nullable()
    val mobileState = text("mobile_state").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** E-mailed sign-in links: a credential table, read by the hash of the link. */
internal object IdentityMagicLinks : Table("identity_magic_links") {
    val id = uuid("id")
    val tokenHash = text("token_hash")
    val email = text("email")
    val redirectPath = text("redirect_path")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()
    val browserHash = text("browser_hash").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A signed-in person's one-time links: a credential table, read by the hash of the link. */
internal object IdentitySignInLinks : Table("identity_sign_in_links") {
    val id = uuid("id")
    val tokenHash = text("token_hash")
    val userId = uuid("user_id")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The phone app's one-time codes: a credential table, read by the hash of the code. */
internal object IdentityMobileCodes : Table("identity_mobile_codes") {
    val id = uuid("id")
    val codeHash = text("code_hash")
    val userId = uuid("user_id")
    val codeChallenge = text("code_challenge")
    val redirectUri = text("redirect_uri")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object IdentityNotificationPreferences : Table("identity_notification_preferences") {
    val userId = uuid("user_id")
    val requests = bool("requests")
    val decisions = bool("decisions")
    val spending = bool("spending")
    val quietStart = text("quiet_start").nullable()
    val quietEnd = text("quiet_end").nullable()
    val quietTimeZone = text("quiet_time_zone").nullable()
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

/** The instance's one-time setup links: a credential table, read by the hash of the link. */
internal object IdentitySetupLinks : Table("identity_setup_links") {
    val id = uuid("id")
    val tokenHash = text("token_hash")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val usedAt = instant("used_at").nullable()
    val usedBy = uuid("used_by").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The sign-in rate limits' windows, by the hash of their key. */
internal object IdentityRateLimits : Table("identity_rate_limits") {
    val keyHash = text("key_hash")
    val attempts = integer("attempts")
    val endsAt = instant("ends_at")

    override val primaryKey = PrimaryKey(keyHash)
}
