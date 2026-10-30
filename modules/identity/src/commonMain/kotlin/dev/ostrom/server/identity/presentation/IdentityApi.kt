package dev.ostrom.server.identity.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// The identity API. These classes move to ostrom-core (the API contract) once it is published (docs/MODULES.md);
// the routes are described in ostrom-core/api/openapi.yaml.

/** `GET /api/v1/auth/me`: the signed-in user. */
@Serializable
@Resource("/api/v1/auth/me")
class MeApi {
    /**
     * `GET /api/v1/auth/me/audit`: the user's account-level audit events, newest first.
     *
     * @property parent The user resource.
     * @property before The `sequence` of the last event of the previous page.
     * @property limit The page size (1 to 200).
     */
    @Serializable
    @Resource("audit")
    class Audit(val parent: MeApi = MeApi(), val before: Long? = null, val limit: Int = 50)
}

/** Sign-in, sign-out and the CLI's device login. */
@Serializable
@Resource("/api/v1/auth")
class AuthApi {
    /**
     * `POST /api/v1/auth/logout`: ends the current browser session, or revokes the calling CLI or personal token.
     *
     * @property parent The auth resource.
     */
    @Serializable
    @Resource("logout")
    class Logout(val parent: AuthApi = AuthApi()) {
        /**
         * `POST /api/v1/auth/logout/everywhere`: ends every browser session of the user.
         *
         * @property parent The logout resource.
         */
        @Serializable
        @Resource("everywhere")
        class Everywhere(val parent: Logout = Logout())
    }

    /**
     * `POST /api/v1/auth/device`: the CLI starts a login (body optional). `GET /api/v1/auth/device?userCode=`: the
     * pending `ostrom login` the user is about to approve.
     *
     * @property parent The auth resource.
     * @property userCode The code the CLI shows.
     */
    @Serializable
    @Resource("device")
    class Device(val parent: AuthApi = AuthApi(), val userCode: String? = null) {
        /**
         * `POST /api/v1/auth/device/token`: the CLI polls for its token.
         *
         * @property parent The device resource.
         */
        @Serializable
        @Resource("token")
        class Token(val parent: Device = Device())

        /**
         * `POST /api/v1/auth/device/answer`: the user approves or denies a login in the console.
         *
         * @property parent The device resource.
         */
        @Serializable
        @Resource("answer")
        class Answer(val parent: Device = Device())
    }
}

/** `GET`/`POST /api/v1/tokens`: the user's personal and CLI tokens. */
@Serializable
@Resource("/api/v1/tokens")
class TokensApi {
    /**
     * `DELETE /api/v1/tokens/{id}`: revokes a token.
     *
     * @property parent The tokens resource.
     * @property id The token id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: TokensApi = TokensApi(), val id: String) {
        /**
         * `POST /api/v1/tokens/{id}/rotate`: replaces a token's secret.
         *
         * @property parent The token resource.
         */
        @Serializable
        @Resource("rotate")
        class Rotate(val parent: Id)
    }
}

/**
 * An e-mail of the user.
 *
 * @property email The address.
 * @property verified Whether it is verified.
 */
@Serializable
data class EmailResponse(val email: String, val verified: Boolean)

/**
 * The signed-in user.
 *
 * @property id The user id.
 * @property login The handle, unique on the instance.
 * @property displayName The name.
 * @property primaryEmail The main e-mail, if any.
 * @property avatarUrl The picture, if any.
 * @property createdAt When the account was created (ISO 8601).
 * @property emails Every e-mail of the user.
 * @property providers How the user signs in (`github`, `google`, `email`, `oidc:<connection>`).
 * @property csrfToken For a browser session, the token to send in `X-CSRF-Token` on state-changing requests.
 */
@Serializable
data class ProfileResponse(
    val id: String,
    val login: String,
    val displayName: String,
    val primaryEmail: String?,
    val avatarUrl: String?,
    val createdAt: String,
    val emails: List<EmailResponse>,
    val providers: List<String>,
    val csrfToken: String? = null,
)

/**
 * A token, without its secret.
 *
 * @property id The token id.
 * @property name Its label.
 * @property kind `personal` or `cli`.
 * @property scopes What it allows.
 * @property hint The first characters of its secret.
 * @property createdAt When it was issued (ISO 8601).
 * @property expiresAt When it expires, if it does.
 * @property lastUsedAt When it was last used, if ever (precise to five minutes).
 */
@Serializable
data class TokenResponse(
    val id: String,
    val name: String,
    val kind: String,
    val scopes: List<String>,
    val hint: String,
    val createdAt: String,
    val expiresAt: String?,
    val lastUsedAt: String?,
)

/**
 * The user's tokens.
 *
 * @property tokens The live tokens, newest first.
 */
@Serializable
data class TokensResponse(val tokens: List<TokenResponse>)

/**
 * A personal token to create.
 *
 * @property name Its label (1 to 100 characters).
 * @property scopes What it allows (`resource:action`, 1 to 32).
 * @property expiresInDays Its lifetime in days (1 to 366), or null for no expiry.
 */
@Serializable
data class CreateTokenRequest(val name: String, val scopes: List<String>, val expiresInDays: Int? = null)

/**
 * A token just issued: the only answer that contains its secret.
 *
 * @property token The token.
 * @property secret The secret to use as `Authorization: Bearer <secret>`.
 */
@Serializable
data class CreatedTokenResponse(val token: TokenResponse, val secret: String)

/**
 * The start of an `ostrom login`.
 *
 * @property clientName What the CLI is (machine name), shown to the user.
 */
@Serializable
data class DeviceCodeRequest(val clientName: String? = null)

/**
 * The codes of an `ostrom login` (RFC 8628 § 3.2).
 *
 * @property deviceCode The secret the CLI polls with.
 * @property userCode The code the user confirms.
 * @property verificationUri Where the user goes.
 * @property verificationUriComplete The same page with the code filled in.
 * @property expiresIn Seconds before the codes expire.
 * @property interval Seconds to wait between polls.
 */
@Serializable
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresIn: Long,
    val interval: Long,
)

/**
 * A CLI poll.
 *
 * @property deviceCode The device code.
 */
@Serializable
data class DeviceTokenRequest(val deviceCode: String)

/**
 * Where an `ostrom login` stands.
 *
 * @property status `pending`, `slow_down`, `denied`, `expired` or `approved`.
 * @property accessToken With `approved`, the CLI token (once).
 * @property expiresAt With `approved`, when the token expires (ISO 8601).
 * @property scopes With `approved`, what the token allows.
 * @property interval Seconds to wait before the next poll.
 */
@Serializable
data class DeviceTokenResponse(
    val status: String,
    val accessToken: String? = null,
    val expiresAt: String? = null,
    val scopes: List<String>? = null,
    val interval: Long,
)

/**
 * A pending `ostrom login`, as the console shows it before the user answers.
 *
 * @property userCode The code.
 * @property clientName What is asking.
 * @property expiresAt When the request expires (ISO 8601).
 */
@Serializable
data class DeviceAuthorizationResponse(val userCode: String, val clientName: String, val expiresAt: String)

/**
 * The user's answer to an `ostrom login`.
 *
 * @property userCode The code the CLI shows.
 * @property approve True to approve, false to deny.
 */
@Serializable
data class AnswerDeviceRequest(val userCode: String, val approve: Boolean)

/**
 * A signed audit event.
 *
 * @property id The id.
 * @property sequence The position in its chain.
 * @property orgId The organization, or null for an account-level event.
 * @property actorId Who did it.
 * @property action What happened.
 * @property targetType What kind of thing it happened to.
 * @property targetId Its id.
 * @property details Extra facts.
 * @property occurredAt When (ISO 8601).
 * @property previousHash The hash of the previous event of the chain.
 * @property hash The SHA-256 of the canonical form.
 * @property signature The instance's Ed25519 signature of the canonical form (base64).
 * @property keyId The instance key that signed it (`GET /v1/instance/keys`).
 */
@Serializable
data class AuditEventResponse(
    val id: String,
    val sequence: Long,
    val orgId: String?,
    val actorId: String?,
    val action: String,
    val targetType: String,
    val targetId: String,
    val details: Map<String, String>,
    val occurredAt: String,
    val previousHash: String?,
    val hash: String,
    val signature: String,
    val keyId: String,
)

/**
 * A page of audit events.
 *
 * @property events The events, newest first.
 * @property nextBefore The `before` of the next page, or null when this is the last one.
 */
@Serializable
data class AuditEventsResponse(val events: List<AuditEventResponse>, val nextBefore: Long?)
