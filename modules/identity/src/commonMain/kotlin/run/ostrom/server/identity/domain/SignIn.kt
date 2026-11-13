// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How long a user has to come back from a provider's consent page. */
val OAUTH_STATE_LIFETIME: Duration = 10.minutes

/** How long an e-mailed sign-in link works. */
val MAGIC_LINK_LIFETIME: Duration = 15.minutes

/**
 * Where to send the user after signing in: a path of the console, never another site (open redirects are how phishing
 * borrows a trusted domain). Anything that is not a plain absolute path (`//evil`, `/\evil`, `https:`, control
 * characters, more than 512 characters) becomes `/`.
 *
 * @param raw The requested path, from the client.
 *
 * @return A safe path of the console.
 */
fun safeRedirectPath(raw: String?): String {
    val path = raw?.trim().orEmpty()
    val safe = path.length in 1..MAX_REDIRECT && path.startsWith('/') && !path.startsWith("//") &&
        path.none { it == '\\' || it.isISOControl() || it.isWhitespace() } && !path.substringBefore('?').contains(':')
    return if (safe) path else "/"
}

private const val MAX_REDIRECT = 512

/**
 * A sign-in with an OAuth or OpenID Connect provider (GitHub, Google, an organization's SSO).
 *
 * The server keeps one [OAuthAttempt] per started sign-in: the PKCE verifier, the OIDC nonce and the console path to
 * return to. The browser keeps the state in a cookie, so a callback only completes in the browser that started it
 * (no login CSRF), and each attempt completes once.
 */
interface OAuthProvider {
    /** The provider. */
    val provider: IdentityProvider

    /**
     * The URL of the provider's consent page.
     *
     * @param state The anti-forgery state, returned by the provider to the callback.
     * @param codeChallenge The PKCE challenge (S256 of the verifier).
     * @param nonce The OIDC nonce the ID token must carry (ignored by plain OAuth providers).
     * @param redirectUri The server's callback URL.
     * @param reauthenticate Whether the person signs in again to prove it is them (before a yes, S2-54): the
     *   provider is asked not to sign them in silently.
     *
     * @return The URL to send the browser to.
     */
    fun authorizationUrl(
        state: String,
        codeChallenge: String,
        nonce: String,
        redirectUri: String,
        reauthenticate: Boolean,
    ): String

    /**
     * Exchanges the authorization code and reads who signed in. Everything returned has been checked: the token came
     * straight from the provider's token endpoint over TLS, and an ID token's issuer, audience, expiry and nonce match.
     *
     * @param code The authorization code.
     * @param codeVerifier The PKCE verifier.
     * @param nonce The nonce the ID token must carry.
     * @param redirectUri The same callback URL as in [authorizationUrl].
     *
     * @return The verified identity.
     *
     * @throws ProviderRejectedException When the provider refuses the code or answers something unusable.
     */
    suspend fun identity(code: String, codeVerifier: String, nonce: String, redirectUri: String): VerifiedIdentity
}

/**
 * A started OAuth sign-in, stored until its callback.
 *
 * @property id The id.
 * @property provider The provider.
 * @property connectionId For an organization's SSO, its connection; null for GitHub and Google.
 * @property codeVerifier The PKCE verifier.
 * @property nonce The OIDC nonce.
 * @property redirectPath The console path to return to.
 * @property createdAt When it started.
 * @property expiresAt When it stops being usable.
 * @property linkUserId When a signed-in user started it to link this provider to their account (S2-07), that user:
 *   the callback links the identity to them instead of signing in by it.
 * @property mobile When the phone app started it (PKCE, S2-24), the app: the callback returns a one-time code to its
 *   deep link instead of opening a browser session.
 */
data class OAuthAttempt(
    val id: Uuid,
    val provider: IdentityProvider,
    val connectionId: Uuid?,
    val codeVerifier: String,
    val nonce: String,
    val redirectPath: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val linkUserId: Uuid? = null,
    val mobile: MobileClient? = null,
)

/**
 * A started OAuth sign-in, as the route needs it.
 *
 * @property state The state, to put in the browser's cookie.
 * @property authorizationUrl Where to send the browser.
 */
data class StartedOAuth(val state: String, val authorizationUrl: String)

/**
 * A sign-in that succeeded.
 *
 * @property user The user.
 * @property session The browser session to set in the cookie.
 * @property redirectPath The console path to return to.
 */
data class CompletedSignIn(val user: User, val session: StartedSession, val redirectPath: String)

/**
 * An e-mailed sign-in link, stored hashed until used.
 *
 * @property id The id.
 * @property email The address it was sent to (normalized).
 * @property redirectPath The console path to return to.
 * @property createdAt When it was sent.
 * @property expiresAt When it stops working.
 */
data class MagicLink(
    val id: Uuid,
    val email: String,
    val redirectPath: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * An e-mail to send.
 *
 * @property to The recipient.
 * @property subject The subject.
 * @property html The HTML body.
 * @property text The plain-text body, for clients that do not show HTML.
 */
data class Mail(val to: String, val subject: String, val html: String, val text: String)

/** Sends e-mails (Resend in production, a directory in tests and local runs). */
interface MailSender {
    /**
     * Sends a mail.
     *
     * @param mail The mail.
     *
     * @throws MailNotSentException When the provider refuses it or cannot be reached.
     */
    suspend fun send(mail: Mail)
}

/** Started OAuth sign-ins. */
interface OAuthAttemptsRepository {
    /**
     * Stores an attempt.
     *
     * @param attempt The attempt.
     * @param stateHash The SHA-256 of its state.
     */
    suspend fun create(attempt: OAuthAttempt, stateHash: String)

    /**
     * Takes an attempt for its callback: returns it and marks it used, atomically, unless it is unknown, used or
     * expired.
     *
     * @param stateHash The SHA-256 of the state.
     * @param now The current time.
     *
     * @return The attempt, or null.
     */
    suspend fun consume(stateHash: String, now: Instant): OAuthAttempt?
}

/** E-mailed sign-in links. */
interface MagicLinksRepository {
    /**
     * Stores a link.
     *
     * @param link The link.
     * @param tokenHash The SHA-256 of its token.
     * @param browserHash The SHA-256 of the nonce of the browser that asked for it (web audit n°2 M4).
     */
    suspend fun create(link: MagicLink, tokenHash: String, browserHash: String)

    /**
     * Uses a link from the browser that asked for it: returns it and marks it used, atomically.
     *
     * @param tokenHash The SHA-256 of the token.
     * @param browserHash The SHA-256 of the nonce the browser presents.
     * @param now The current time.
     *
     * @return The link, or null when unknown, used, expired or asked for by another browser.
     */
    suspend fun consume(tokenHash: String, browserHash: String, now: Instant): MagicLink?

    /**
     * Whether a link is still usable (known, unused, unexpired), whichever browser presents it.
     *
     * @param tokenHash The SHA-256 of the token.
     * @param now The current time.
     *
     * @return True when it is.
     */
    suspend fun pending(tokenHash: String, now: Instant): Boolean
}

/** The provider is not configured on this instance. */
class ProviderNotConfiguredException :
    OstromException(ErrorKind.NOT_FOUND, "This sign-in method is not available on this instance.")

/** The sign-in attempt is unknown, expired, already used, or was started in another browser. */
class InvalidSignInStateException :
    OstromException(ErrorKind.INVALID_REQUEST, "This sign-in has expired or was started elsewhere. Start again.")

/** The provider refused the sign-in or answered something unusable. */
class ProviderRejectedException(reason: String) :
    OstromException(ErrorKind.UNAUTHORIZED, "The provider refused the sign-in: $reason")

/** The sign-in link is unknown, expired or already used. */
class InvalidMagicLinkException :
    OstromException(ErrorKind.INVALID_REQUEST, "This sign-in link has expired or was already used. Ask for a new one.")

/**
 * A valid link opened in another browser than the one that asked for it (web audit n°2 M4): a link forwarded, read
 * on another device, or caught in transit signs no one in. It stays usable in the browser that asked for it.
 */
class MagicLinkOtherBrowserException : OstromException(
    ErrorKind.FORBIDDEN,
    "This sign-in link was asked for in another browser. Open it there, or ask for a new link from this one.",
    "email_link_other_browser",
)

/** The mail could not be sent. */
class MailNotSentException : OstromException(ErrorKind.UNAVAILABLE, "The e-mail could not be sent. Retry in a moment.")
