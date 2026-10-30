// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The deep link of the Ostrom app: the only `redirect_uri` a mobile sign-in returns to, compared as a whole string.
 * A custom scheme can be claimed by another app (SECURITY-AUDIT S2-24): PKCE and the app's `state` keep such an app
 * from using the code it would receive.
 */
const val APP_REDIRECT_URI = "ostrom://auth/callback"

/** The `redirect_uri` values a mobile sign-in accepts, exactly. */
val MOBILE_REDIRECT_URIS: Set<String> = setOf(APP_REDIRECT_URI)

/** The only PKCE method a mobile sign-in accepts (RFC 7636 § 4.2): `plain` would put the verifier in the URL. */
const val MOBILE_CHALLENGE_METHOD = "S256"

/** How long the one-time code of a mobile sign-in works: the app exchanges it at once. */
val MOBILE_CODE_LIFETIME: Duration = 60.seconds

/** How long the app's token lasts before the person signs in again on the phone. */
val MOBILE_TOKEN_LIFETIME: Duration = 30.days

/** The longest code or unlock key accepted: the server's secrets are far shorter, anything longer is not one. */
internal const val MAX_MOBILE_SECRET_LENGTH = 128

/** An S256 challenge is the unpadded base64url of 32 bytes: exactly 43 characters. */
private val CHALLENGE_PATTERN = Regex("[A-Za-z0-9_-]{43}")

/** A PKCE verifier (RFC 7636 § 4.1): 43 to 128 unreserved characters. */
private val VERIFIER_PATTERN = Regex("[A-Za-z0-9._~-]{43,128}")

/** The app's `state`: unreserved characters only, so it goes back in the deep link unchanged. */
private val STATE_PATTERN = Regex("[A-Za-z0-9._~-]{1,256}")

/**
 * The app that started a sign-in, kept with the OAuth attempt until the provider sends the browser back.
 *
 * @property redirectUri Where the code goes ([APP_REDIRECT_URI]).
 * @property codeChallenge The S256 challenge of the verifier only the app knows.
 * @property state The app's own anti-forgery value, returned with the code.
 */
data class MobileClient(val redirectUri: String, val codeChallenge: String, val state: String)

/**
 * Reads the mobile parameters of `GET /api/v1/auth/{provider}/start`. Without `redirect_uri`, `code_challenge` and
 * `code_challenge_method`, the sign-in is the browser's; with any of them, all must be valid.
 *
 * @param redirectUri The `redirect_uri` query parameter.
 * @param state The `state` query parameter.
 * @param codeChallenge The `code_challenge` query parameter.
 * @param challengeMethod The `code_challenge_method` query parameter.
 *
 * @return The app, or null for a browser sign-in.
 *
 * @throws InvalidMobileSignInException When a parameter is missing or invalid: nothing is redirected anywhere.
 */
fun mobileClientOf(
    redirectUri: String?,
    state: String?,
    codeChallenge: String?,
    challengeMethod: String?,
): MobileClient? {
    if (redirectUri == null && codeChallenge == null && challengeMethod == null) return null
    val client = MobileClient(redirectUri.orEmpty(), codeChallenge.orEmpty(), state.orEmpty())
    if (!client.isValid(challengeMethod)) throw InvalidMobileSignInException()
    return client
}

/** A listed deep link, the S256 method, a 43-character challenge and a state of unreserved characters. */
private fun MobileClient.isValid(challengeMethod: String?): Boolean = redirectUri in MOBILE_REDIRECT_URIS &&
    challengeMethod == MOBILE_CHALLENGE_METHOD && CHALLENGE_PATTERN.matches(codeChallenge) &&
    STATE_PATTERN.matches(state)

/**
 * Whether a PKCE verifier is well formed (RFC 7636 § 4.1).
 *
 * @param verifier The verifier the app sends.
 *
 * @return True when it has 43 to 128 unreserved characters.
 */
fun isPkceVerifier(verifier: String): Boolean = VERIFIER_PATTERN.matches(verifier)

/**
 * The one-time code of a mobile sign-in, stored hashed until the app exchanges it.
 *
 * @property id The id.
 * @property userId Who signed in.
 * @property codeChallenge The S256 challenge the verifier must match.
 * @property redirectUri The `redirect_uri` the exchange must repeat.
 * @property createdAt When it was issued.
 * @property expiresAt When it stops working.
 */
data class MobileCode(
    val id: Uuid,
    val userId: Uuid,
    val codeChallenge: String,
    val redirectUri: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Where the browser goes at the end of a mobile sign-in: the app's deep link, with the code or an error.
 *
 * @property redirectUri The app's deep link.
 * @property state The app's state, returned unchanged.
 * @property code The one-time code, on success.
 * @property error Why it failed (`access_denied`, `refused`, `link_required`), otherwise.
 */
data class MobileCallback(
    val redirectUri: String,
    val state: String,
    val code: String? = null,
    val error: String? = null,
)

/**
 * The app's token as issued.
 *
 * @property token The token (kind [TokenKind.MOBILE]) and its secret.
 * @property unlockKey The secret the app keeps behind the phone's biometrics and presents to unlock (S2-54).
 * @property user Who signed in.
 * @property expiresAt When the token stops working.
 */
data class IssuedMobileToken(val token: IssuedToken, val unlockKey: String, val user: User, val expiresAt: Instant)

/** The one-time codes of mobile sign-ins. */
interface MobileCodesRepository {
    /**
     * Stores a code.
     *
     * @param code The code.
     * @param codeHash The SHA-256 of its value.
     */
    suspend fun create(code: MobileCode, codeHash: String)

    /**
     * Uses a code: marks it used and returns it in one statement, so two exchanges cannot both win.
     *
     * @param codeHash The SHA-256 of its value.
     * @param now The current time.
     *
     * @return The code, or null when unknown, used or expired.
     */
    suspend fun consume(codeHash: String, now: Instant): MobileCode?
}

/** The app's tokens: their unlock key and their last unlock. */
interface MobileTokensRepository {
    /**
     * Stores a new app token with the hash of its unlock key.
     *
     * @param token The token (kind [TokenKind.MOBILE]).
     * @param secretHash The SHA-256 of its secret.
     * @param unlockKeyHash The SHA-256 of its unlock key.
     */
    suspend fun create(token: ApiToken, secretHash: String, unlockKeyHash: String)

    /**
     * Records an unlock: the token's [ApiToken.authenticatedAt] becomes [at], only when the token is the user's, an
     * app token, live, and its unlock key has this hash.
     *
     * @param tokenId The token.
     * @param userId Its owner.
     * @param unlockKeyHash The SHA-256 of the presented unlock key.
     * @param at When.
     *
     * @return True when recorded.
     */
    suspend fun unlock(tokenId: Uuid, userId: Uuid, unlockKeyHash: String, at: Instant): Boolean
}

/** The mobile parameters of a sign-in start are missing or invalid. */
class InvalidMobileSignInException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "A mobile sign-in needs the app's redirect_uri, a state and an S256 code_challenge.",
)

/** The code is unknown, expired or used, or its verifier or redirect URI does not match: it is spent either way. */
class InvalidMobileCodeException :
    OstromException(ErrorKind.INVALID_REQUEST, "This sign-in code is not valid any more. Sign in again in the app.")

/** The credential is not the app's token, or the unlock key is wrong. */
class MobileUnlockRefusedException :
    OstromException(ErrorKind.FORBIDDEN, "This phone could not be unlocked. Sign in again in the app.")
