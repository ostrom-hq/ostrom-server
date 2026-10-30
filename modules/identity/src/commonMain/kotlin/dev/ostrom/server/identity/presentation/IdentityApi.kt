package dev.ostrom.server.identity.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// The identity API. These classes move to ostrom-core (the API contract) once it is published (docs/MODULES.md);
// the routes are described in ostrom-core/api/openapi.yaml.

/** `GET /v1/me`: the signed-in user. */
@Serializable
@Resource("/v1/me")
class MeApi {
    /**
     * `GET /v1/me/audit`: the user's account-level audit events, newest first.
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
@Resource("/v1/auth")
class AuthApi {
    /**
     * `POST /v1/auth/logout`: ends the current browser session, or revokes the calling CLI or personal token.
     *
     * @property parent The auth resource.
     */
    @Serializable
    @Resource("logout")
    class Logout(val parent: AuthApi = AuthApi()) {
        /**
         * `POST /v1/auth/logout/everywhere`: ends every browser session of the user.
         *
         * @property parent The logout resource.
         */
        @Serializable
        @Resource("everywhere")
        class Everywhere(val parent: Logout = Logout())
    }

    /**
     * `GET /v1/auth/device?userCode=`: the pending `ostrom login` the user is about to approve.
     *
     * @property parent The auth resource.
     * @property userCode The code the CLI shows.
     */
    @Serializable
    @Resource("device")
    class Device(val parent: AuthApi = AuthApi(), val userCode: String? = null) {
        /**
         * `POST /v1/auth/device/code`: the CLI starts a login.
         *
         * @property parent The device resource.
         */
        @Serializable
        @Resource("code")
        class Code(val parent: Device = Device())

        /**
         * `POST /v1/auth/device/token`: the CLI polls for its token.
         *
         * @property parent The device resource.
         */
        @Serializable
        @Resource("token")
        class Token(val parent: Device = Device())

        /**
         * `POST /v1/auth/device/answer`: the user approves or denies a login in the console.
         *
         * @property parent The device resource.
         */
        @Serializable
        @Resource("answer")
        class Answer(val parent: Device = Device())
    }
}

/** Signing in: the offered methods, GitHub and Google, and e-mailed links. */
@Serializable
@Resource("/v1/auth")
class SignInApi {
    /**
     * `GET /v1/auth/providers`: the sign-in methods this instance offers.
     *
     * @property parent The auth resource.
     */
    @Serializable
    @Resource("providers")
    class Providers(val parent: SignInApi = SignInApi())

    /**
     * A sign-in with an OAuth provider.
     *
     * @property parent The auth resource.
     * @property provider `github` or `google`.
     */
    @Serializable
    @Resource("{provider}")
    class Provider(val parent: SignInApi = SignInApi(), val provider: String) {
        /**
         * `GET /v1/auth/{provider}/start?redirect=`: sends the browser to the provider's consent page.
         *
         * @property parent The provider resource.
         * @property redirect The console path to return to.
         */
        @Serializable
        @Resource("start")
        class Start(val parent: Provider, val redirect: String? = null)

        /**
         * `GET /v1/auth/{provider}/callback`: where the provider sends the browser back.
         *
         * @property parent The provider resource.
         * @property code The authorization code.
         * @property state The state.
         * @property error The provider's error, when the user refused or it failed.
         */
        @Serializable
        @Resource("callback")
        class Callback(val parent: Provider, val code: String? = null, val state: String? = null, val error: String? = null)
    }

    /**
     * `POST /v1/auth/email`: e-mails a sign-in link.
     *
     * @property parent The auth resource.
     */
    @Serializable
    @Resource("email")
    class Email(val parent: SignInApi = SignInApi()) {
        /**
         * `POST /v1/auth/email/verify`: signs in with the link's token.
         *
         * @property parent The e-mail resource.
         */
        @Serializable
        @Resource("verify")
        class Verify(val parent: Email = Email())
    }
}

/**
 * The sign-in methods this instance offers.
 *
 * @property methods `github`, `google`, `email`, in that order of keys.
 */
@Serializable
data class ProvidersResponse(val methods: List<String>)

/**
 * A request for a sign-in link.
 *
 * @property email The address.
 * @property redirect The console path to return to after signing in.
 */
@Serializable
data class MagicLinkRequest(val email: String, val redirect: String? = null)

/**
 * The token of a sign-in link, read by the console from the link's fragment (`/auth/email#token=…`): a fragment is
 * never sent to a server, so the token stays out of access logs and `Referer` headers, and a mail scanner that opens
 * the link signs nobody in.
 *
 * @property token The token.
 */
@Serializable
data class VerifyMagicLinkRequest(val token: String)

/**
 * A completed sign-in: the session cookie is set on the same answer.
 *
 * @property redirect The console path to go to.
 * @property csrfToken The token to send in `X-CSRF-Token` on state-changing requests.
 */
@Serializable
data class SignedInResponse(val redirect: String, val csrfToken: String)

/** `GET`/`POST /v1/tokens`: the user's personal and CLI tokens. */
@Serializable
@Resource("/v1/tokens")
class TokensApi {
    /**
     * `DELETE /v1/tokens/{id}`: revokes a token.
     *
     * @property parent The tokens resource.
     * @property id The token id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: TokensApi = TokensApi(), val id: String) {
        /**
         * `POST /v1/tokens/{id}/rotate`: replaces a token's secret.
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
