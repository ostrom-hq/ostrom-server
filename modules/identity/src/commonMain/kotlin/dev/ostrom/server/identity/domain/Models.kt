// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A person with an Ostrom account. There is no password: a user signs in with GitHub, Google, an e-mailed link or
 * their organization's SSO, and every way in that proves the same verified e-mail lands on the same user.
 *
 * @property id The stable id of the user.
 * @property login The user's handle, unique on the instance: lower case letters, digits and dashes, at most 39
 *   characters (the GitHub login when the account starts with GitHub, else made from the e-mail or the name). Members
 *   are named by it in the API (`/api/v1/members/{login}`).
 * @property displayName The name shown in the console.
 * @property primaryEmail The e-mail the user is reached at, normalized (lower case), if known.
 * @property avatarUrl The picture shown in the console, if any.
 * @property createdAt When the account was created.
 */
data class User(
    val id: Uuid,
    val login: String,
    val displayName: String,
    val primaryEmail: String?,
    val avatarUrl: String?,
    val createdAt: Instant,
)

/**
 * An e-mail address of a user. Only a verified address merges accounts.
 *
 * @property email The address, normalized (lower case, trimmed).
 * @property verified True once a sign-in link, or a provider that is an authority for the address, proved the user
 *   owns it (S2-07: an organization's SSO never verifies an address).
 */
data class UserEmail(
    val email: String,
    val verified: Boolean,
)

/** Where a sign-in comes from. [key] is the stored and published name. */
enum class IdentityProvider(val key: String) {
    GITHUB("github"),
    GOOGLE("google"),
    EMAIL("email"),
    OIDC("oidc"),
    ;

    /** Reads a stored provider name back. */
    companion object {
        /**
         * Reads a stored provider name.
         *
         * @param key The stored name.
         *
         * @return The provider.
         *
         * @throws IllegalArgumentException When the name is unknown (a corrupted row).
         */
        fun of(key: String): IdentityProvider = requireNotNull(entries.firstOrNull { it.key == key }) {
            "Unknown identity provider."
        }
    }
}

/**
 * What a provider proved about the person signing in, after the server checked it (state, PKCE, nonce, token
 * signature): never what a client claims.
 *
 * @property provider The provider.
 * @property subject The provider's stable id of the person (GitHub user id, OIDC `sub`, the e-mail for a link).
 * @property email The e-mail the provider gives, if any.
 * @property emailVerified True only when the provider says it verified [email]; only then can accounts merge.
 * @property displayName A name to start the account with.
 * @property avatarUrl A picture to start the account with.
 * @property login A handle to start the account with (the GitHub login); taken or invalid, another is made.
 * @property connectionId For [IdentityProvider.OIDC], the organization's SSO connection; null otherwise.
 * @property hostedDomain For Google, the Workspace domain of the account (`hd` claim): its administrator owns the
 *   addresses of that domain, so Google is an authority for them. Null for a consumer account and other providers.
 */
data class VerifiedIdentity(
    val provider: IdentityProvider,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val login: String? = null,
    val connectionId: Uuid? = null,
    val hostedDomain: String? = null,
) {
    /** The scope in which [subject] is unique: the provider, plus the connection for organization SSO. */
    val issuer: String get() = if (connectionId == null) provider.key else "${provider.key}:$connectionId"
}

/**
 * A browser session. Its secret only exists in the user's cookie; the server keeps its SHA-256.
 *
 * @property id The id of the session.
 * @property userId The signed-in user.
 * @property createdAt When it started.
 * @property expiresAt When it stops working, whatever happens.
 * @property lastSeenAt When it was last used (updated at most once per [SESSION_TOUCH_INTERVAL]).
 * @property revokedAt When the user signed out or the session was revoked, if so.
 */
data class WebSession(
    val id: Uuid,
    val userId: Uuid,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
)

/**
 * The kinds of API token. [prefix] starts the secret, so a leaked token says what it is (and secret scanners can
 * match it).
 */
enum class TokenKind(val prefix: String) {
    /** Created by a user in the console, with the scopes they chose. */
    PERSONAL("osp"),

    /** Issued to the CLI by `ostrom login` (device authorization). */
    CLI("osc"),

    /**
     * Issued to the CLI by `ostrom login --admin`: the governance writes of one organization ([ADMIN_SCOPES]), approved
     * in the browser by an owner or an admin of it, for [ADMIN_TOKEN_LIFETIME] at most, never rotated.
     */
    ADMIN("osg"),

    /** Issued to one agent session: it can only act on that session (audit A2, JOURNAL 02:15). */
    AGENT("osa"),

    /**
     * Issued to the phone app by a mobile sign-in (PKCE): it lives in the phone's keychain, never on the developer's
     * machine, and says yes only after the phone's biometric unlock (S2-31, S2-54).
     */
    MOBILE("osm"),

    /**
     * An organization's service key (CI, a runner, a script), created by an owner or an admin: it belongs to the
     * organization, not to its creator ([ApiToken.userId] only says who made it), acts as a
     * [PrincipalKind.SERVICE][dev.ostrom.server.kernel.auth.PrincipalKind.SERVICE] bound to that organization, and is
     * never a member: a route serves it only when it admits services.
     */
    SERVICE("oss"),
    ;

    /** Reads a stored kind back. */
    companion object {
        /**
         * Reads a stored kind.
         *
         * @param name The stored name.
         *
         * @return The kind.
         *
         * @throws IllegalArgumentException When the name is unknown (a corrupted row).
         */
        fun of(name: String): TokenKind = valueOf(name)
    }
}

/**
 * An API token. Like a session, only its SHA-256 is stored.
 *
 * @property id The id of the token.
 * @property userId The user it acts for.
 * @property orgId The organization it is confined to, if any.
 * @property name A label chosen by the user (or the CLI's machine name).
 * @property kind What it is.
 * @property scopes What it allows; never empty.
 * @property hint The first characters of the secret, shown in lists so the user can recognize it.
 * @property agentSessionId For an [TokenKind.AGENT] token, the only agent session it can act on.
 * @property createdAt When it was issued.
 * @property expiresAt When it stops working, if it expires.
 * @property lastUsedAt When it was last used (updated at most once per [SESSION_TOUCH_INTERVAL]).
 * @property revokedAt When it was revoked, if so.
 * @property authenticatedAt For a [TokenKind.MOBILE] token, the last biometric unlock of the phone (S2-54); null
 *   before the first one, and for every other kind.
 */
data class ApiToken(
    val id: Uuid,
    val userId: Uuid,
    val orgId: Uuid?,
    val name: String,
    val kind: TokenKind,
    val scopes: Set<String>,
    val hint: String,
    val agentSessionId: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    val authenticatedAt: Instant? = null,
)

/**
 * A token as issued: the only time its secret exists outside the client.
 *
 * @property token The token.
 * @property secret The secret to hand to the client once.
 */
data class IssuedToken(
    val token: ApiToken,
    val secret: String,
)

/**
 * A started browser session.
 *
 * @property session The session.
 * @property secret The value of the session cookie.
 * @property csrfToken The token the console sends back in `X-CSRF-Token` on every state-changing request.
 */
data class StartedSession(
    val session: WebSession,
    val secret: String,
    val csrfToken: String,
)

/** The states of a device authorization (`ostrom login`, RFC 8628). */
enum class DeviceAuthorizationStatus { PENDING, APPROVED, DENIED, CONSUMED }

/**
 * A pending `ostrom login`: the CLI shows [userCode], the user confirms it in the console, the CLI polls with its
 * device code (stored hashed) until it gets its token.
 *
 * @property id The id.
 * @property userCode The short code the user types or confirms (`ABCD-EFGH`).
 * @property clientName What the CLI says it is (machine name), shown to the user before they approve.
 * @property status Where it stands.
 * @property userId The user who approved, once approved.
 * @property createdAt When the CLI started it.
 * @property expiresAt When it stops being usable.
 * @property lastPolledAt When the CLI last polled, to enforce the interval.
 * @property scopes The governance write scopes `ostrom login --admin` asks for ([ADMIN_SCOPES]), shown before the
 *   yes; empty for the ordinary sign-in.
 * @property approvingOrgId The organization whose owner or admin approved them, which the token is bound to.
 */
data class DeviceAuthorization(
    val id: Uuid,
    val userCode: String,
    val clientName: String,
    val status: DeviceAuthorizationStatus,
    val userId: Uuid?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastPolledAt: Instant?,
    val scopes: Set<String> = emptySet(),
    val approvingOrgId: Uuid? = null,
)

/**
 * A device authorization as started: the device code only exists here and in the CLI.
 *
 * @property authorization The authorization.
 * @property deviceCode The secret the CLI polls with.
 */
data class StartedDeviceAuthorization(
    val authorization: DeviceAuthorization,
    val deviceCode: String,
)

/**
 * A sensitive action to record in the signed audit log.
 *
 * @property orgId The organization it happened in, or null for an action on the user's own account.
 * @property actorId Who did it (a user id), or null for the server itself.
 * @property action What happened, a stable dotted name (`token.created`, `member.removed`).
 * @property targetType What kind of thing it happened to (`token`, `member`, `org`).
 * @property targetId The id of that thing.
 * @property details Extra facts, never a secret.
 */
data class AuditEntry(
    val orgId: Uuid?,
    val actorId: Uuid?,
    val action: String,
    val targetType: String,
    val targetId: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * A recorded audit event, chained to the previous one of its chain (its organization, or the instance for
 * account-level events) and signed by the instance key: removing or editing an event breaks the chain.
 *
 * @property id The id.
 * @property sequence The position in its chain, from 1.
 * @property entry What happened.
 * @property occurredAt When.
 * @property previousHash The hash of the previous event of the chain, or null for the first one.
 * @property hash The SHA-256 (hex) of the canonical form of this event.
 * @property signature The instance's Ed25519 signature of the canonical form (base64).
 * @property keyId The instance key that signed it.
 */
data class AuditEvent(
    val id: Uuid,
    val sequence: Long,
    val entry: AuditEntry,
    val occurredAt: Instant,
    val previousHash: String?,
    val hash: String,
    val signature: String,
    val keyId: String,
)
