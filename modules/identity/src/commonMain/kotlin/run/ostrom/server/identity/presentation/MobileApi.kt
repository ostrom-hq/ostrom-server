// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.IssuedMobileToken
import run.ostrom.server.identity.domain.MobileCallback
import run.ostrom.server.identity.domain.NotificationPreferences
import run.ostrom.server.identity.domain.QuietHours
import io.ktor.http.formUrlEncode
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** The phone app's sign-in: the code exchange and the biometric unlock. */
@Serializable
@Resource("/api/v1/auth/mobile")
class MobileApi {
    /**
     * `POST /api/v1/auth/mobile/token`: exchanges the one-time code of the deep link, with the PKCE verifier.
     *
     * @property parent The mobile resource.
     */
    @Serializable
    @Resource("token")
    class Token(val parent: MobileApi = MobileApi())

    /**
     * `POST /api/v1/auth/mobile/unlock`: the app presents the key it keeps behind the phone's biometrics.
     *
     * @property parent The mobile resource.
     */
    @Serializable
    @Resource("unlock")
    class Unlock(val parent: MobileApi = MobileApi())
}

/** The caller's own settings outside any organization. */
@Serializable
@Resource("/api/v1/me")
class MySettingsApi {
    /**
     * `GET`/`PUT /api/v1/me/notifications`: what the caller is told on their phone.
     *
     * @property parent The caller's resource.
     */
    @Serializable
    @Resource("notifications")
    class Notifications(val parent: MySettingsApi = MySettingsApi())
}

/**
 * Body of `POST /api/v1/auth/mobile/token`.
 *
 * @property code The one-time code from the deep link.
 * @property codeVerifier The PKCE verifier.
 * @property redirectUri The deep link the code was sent to.
 */
@Serializable
data class MobileTokenRequest(val code: String, val codeVerifier: String, val redirectUri: String)

/**
 * The signed-in person, as the contract's `AuthUser`.
 *
 * @property login Their login.
 * @property name Their display name.
 * @property avatarUrl Their picture, if any.
 */
@Serializable
data class AuthUserResponse(val login: String, val name: String? = null, val avatarUrl: String? = null)

/**
 * The app's token.
 *
 * @property token The bearer token (`osm_…`), for the keychain.
 * @property user Who signed in.
 * @property expiresAt When it stops working (ISO 8601).
 * @property unlockKey The key the app keeps behind the phone's biometrics and presents to
 *   `POST /api/v1/auth/mobile/unlock` before a yes (S2-54); never sent anywhere else.
 */
@Serializable
data class MobileTokenResponse(
    val token: String,
    val user: AuthUserResponse,
    val expiresAt: String? = null,
    val unlockKey: String,
)

/**
 * Body of `POST /api/v1/auth/mobile/unlock`.
 *
 * @property unlockKey The key from the token exchange.
 * @property requestId The approval request the unlock is for: a yes from the app needs it (Q-APP-STEPUP-RISK).
 */
@Serializable
data class MobileUnlockRequest(val unlockKey: String, val requestId: String? = null)

/**
 * A recorded unlock.
 *
 * @property authenticatedAt When (ISO 8601): a no from the app is accepted for five minutes after it, a yes within a
 *   minute and once, to the request the unlock named.
 */
@Serializable
data class MobileUnlockResponse(val authenticatedAt: String)

/**
 * The contract's `NotificationPreferences`.
 *
 * @property requests A request for the caller's yes.
 * @property decisions An answer to a request of the caller.
 * @property spending A budget about to be reached.
 * @property quietHours Hours without notifications.
 */
@Serializable
data class NotificationPreferencesBody(
    val requests: Boolean,
    val decisions: Boolean,
    val spending: Boolean,
    val quietHours: QuietHoursBody? = null,
)

/**
 * The contract's `QuietHours`.
 *
 * @property start `HH:MM`.
 * @property end `HH:MM`.
 * @property timeZone An IANA time zone.
 */
@Serializable
data class QuietHoursBody(val start: String, val end: String, val timeZone: String)

/**
 * The answer of the code exchange.
 *
 * @return The response.
 */
fun IssuedMobileToken.toResponse(): MobileTokenResponse = MobileTokenResponse(
    token = token.secret,
    user = AuthUserResponse(user.login, user.displayName, user.avatarUrl),
    expiresAt = expiresAt.toString(),
    unlockKey = unlockKey,
)

/**
 * The app's deep link with the code or the error, and the app's state (the redirect URI has no query of its own).
 *
 * @return The URL.
 */
fun MobileCallback.toUrl(): String {
    val outcome = if (code != null) "code" to code else "error" to error.orEmpty()
    return redirectUri + "?" + listOf(outcome, "state" to state).formUrlEncode()
}

/**
 * The page a mobile sign-in ends on when it has a code: who signed in, and a link to the app that the person taps
 * (AUDIT-SECU-APP-2 N1). A redirect would hand the code to whichever app claims `ostrom://` on the phone, for a sign-in
 * someone else started with their own PKCE challenge and sent as a link; the page asks first. No script, no form:
 * nothing leaves the page but on the tap.
 *
 * @return The HTML.
 */
fun MobileCallback.confirmPage(): String = """
    <!doctype html>
    <html lang="en">
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Back to Ostrom</title>
    <body style="font-family: system-ui; display: grid; place-items: center; min-height: 100vh; margin: 0;
    padding: 0 16px">
    <main style="max-width: 28rem">
    <h1 style="font-size: 1.25rem">Sign in to the Ostrom app${login?.let { " as @" + htmlEscape(it) }.orEmpty()}?</h1>
    <p>Continue only if you started this sign-in from the Ostrom app on this phone. If you did not, close this page:
    someone else may be trying to sign in as you.</p>
    <p><a href="${htmlEscape(toUrl())}" style="display: inline-block; padding: 12px 20px; border-radius: 8px;
    background: #111; color: #fff; text-decoration: none">Back to Ostrom</a></p>
    </main>
    </body>
    </html>
""".trimIndent()

// For text and attribute values between double quotes.
private fun htmlEscape(text: String): String = text.replace("&", "&amp;").replace("\"", "&quot;")
    .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")

/**
 * The response body.
 *
 * @return The body.
 */
fun NotificationPreferences.toBody(): NotificationPreferencesBody = NotificationPreferencesBody(
    requests = requests,
    decisions = decisions,
    spending = spending,
    quietHours = quietHours?.let { QuietHoursBody(it.start, it.end, it.timeZone) },
)

/**
 * The domain value of a request body.
 *
 * @return The preferences.
 */
fun NotificationPreferencesBody.toPreferences(): NotificationPreferences = NotificationPreferences(
    requests = requests,
    decisions = decisions,
    spending = spending,
    quietHours = quietHours?.let { QuietHours(it.start, it.end, it.timeZone) },
)
