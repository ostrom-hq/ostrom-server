// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryFlag
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Responses

private const val TAG = "auth"

private const val PROVIDER = "`github` or `google`."

private const val REDIRECT = "The console path to return to."

private const val ATTEMPTS = "Too many attempts lately (`rate_limited`): nothing was done. A sign-in step is " +
    "limited per address or person over a window; wait, then try again."

/** The sign-in routes of the browser, the e-mailed link, the sign-in link, the CLI's launcher and the phone app. */
internal fun ApiCatalog.signInOperations() {
    providerOperations()
    providerLinkOperations()
    emailOperations()
    signInLinkOperations()
    launcherAndMobileOperations()
}

/** Which methods the instance offers, and the OAuth round trip. */
private fun ApiCatalog.providerOperations() {
    get("/api/v1/auth/providers") {
        operationId = "listSignInMethods"
        tag(TAG)
        summary = "The sign-in methods this instance offers (`github`, `google`, `email`)."
        description = "The methods the instance offers, and who may get a new account there: `open` or `invite`."
        responses {
            ok<ProvidersResponse>("The methods, and who may sign up.")
        }
    }
    get("/api/v1/auth/{provider}/start") {
        operationId = "startOAuthLogin"
        tag(TAG)
        summary = "Send the browser to the provider's consent page (sets the state cookie)."
        description = "On a refusal, a redirect to the console's `/login?error=` (`unavailable`, `rate_limited`, " +
            "`expired`, `refused`, `link_required`, `identity_taken`, `signup_closed`)."
        parameters {
            pathId("provider", PROVIDER)
            queryText("redirect", REDIRECT)
            queryFlag("reauth", "Sign in again before a yes: the provider asks again.")
            queryText("redirect_uri", "Mobile app: its deep link, exactly `ostrom://auth/callback`. With `state`, " +
                "`code_challenge` and `code_challenge_method=S256`, the callback returns a one-time `code` (or an " +
                "`error`) and the app's `state` to it; any invalid mobile parameter answers 400.")
            queryText("state", "Mobile app: its state, returned with the code.")
            queryText("code_challenge", "Mobile app: the S256 PKCE challenge (43 characters).")
            queryText("code_challenge_method", "Mobile app: `S256` only.")
        }
        responses {
            empty("Redirect to the provider's consent page.", HttpStatusCode.Found)
            badRequest("An invalid mobile parameter (`invalid_request`).")
        }
    }
    get("/api/v1/auth/{provider}/callback") {
        operationId = "oauthLoginCallback"
        tag(TAG)
        summary = "Where the provider sends the browser back."
        description = "Sets the session cookies and redirects to the console (or to `/login?error=`, " +
            "`signup_closed` when the instance creates no account for this person: see `signUp` of " +
            "`listSignInMethods`). For a sign-in the phone app started, an error redirects to the app's deep link; " +
            "a code is answered as a page whose link to the deep link the person taps. That page names who signed " +
            "in, has no script, and is never framed or cached."
        parameters {
            pathId("provider", PROVIDER)
            queryText("code", "The provider's authorization code.")
            queryText("state", "The state the sign-in started with.")
            queryText("error", "The provider's error, when the person refused or it failed.")
        }
        responses {
            empty("Redirect to the console, or to its login page with an `error`.", HttpStatusCode.Found)
            ok<String>(
                "A mobile sign-in's code: the page that links to the app's deep link " +
                    "(`ostrom://auth/callback?code=&state=`), on the person's tap.",
            )
        }
    }
}

/** Linking a provider to the account of a signed-in person. */
private fun ApiCatalog.providerLinkOperations() {
    post("/api/v1/auth/{provider}/link") {
        operationId = "linkSignInProvider"
        tag(TAG)
        summary = "Start linking a provider to the caller's account."
        description = "A signed-in person (browser session and `X-CSRF-Token`) starts linking a provider to their " +
            "account: the answer gives the consent page to open, the state cookie is set, and the callback links " +
            "the identity. A token on a developer's machine never adds a way in."
        parameters {
            pathId("provider", PROVIDER)
            queryText("redirect", REDIRECT)
        }
        responses {
            ok<LinkStartedResponse>("Where to send the browser.")
            notFound("No such provider, or one this instance does not offer (`not_found`).")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/signup-grant") {
        operationId = "grantSignUp"
        tag(TAG)
        summary = "Present the token of a setup link or of an invitation before signing in."
        description = "Before signing in on an invite-only instance, the console presents the token of the " +
            "instance's setup link (`/setup#token=osb_…`, printed by `ostrom-server bootstrap`) or of an " +
            "invitation (`/invite#token=…`): the server checks it and sets the `ostrom_signup` cookie (HttpOnly, " +
            "`Path=/api/v1/auth`, 1 hour), which lets the sign-in that follows in this browser create the " +
            "person's account. The setup link is used once, by that account, which may then create the " +
            "instance's organizations. The `Origin` header must be the console's."
        body<SignUpGrantRequest>("The token of the setup link or of the invitation.")
        responses {
            empty("The token is valid: the cookie is set.")
            error(HttpStatusCode.Forbidden, "The call does not come from the console: its `Origin` header is " +
                "missing or another (`forbidden`).")
            notFound("The token is unknown, used or expired: ask for a new setup link or invitation (`not_found`).")
            tooManyAttempts()
        }
    }
}

/** The e-mailed link. */
private fun ApiCatalog.emailOperations() {
    post("/api/v1/auth/email") {
        operationId = "requestSignInEmail"
        tag(TAG)
        summary = "E-mail a sign-in link; the answer is the same whether the address has an account or not."
        description = "The browser that asks keeps a nonce (a cookie) that the link needs to sign in: a link " +
            "opened in another browser signs nobody in."
        body<MagicLinkRequest>("The address, and the console path to go to after signing in.")
        responses {
            empty("Sent if the address may sign in.", HttpStatusCode.Accepted)
            badRequest("Not an e-mail address (`invalid_request`).")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/email/verify") {
        operationId = "verifySignInEmail"
        tag(TAG)
        summary = "Sign in with the token of an e-mailed link."
        description = "The console reads the token from the link's fragment (`/auth/email#token=…`): a fragment is " +
            "never sent to a server, so the token stays out of access logs, and a mail scanner that opens the " +
            "link signs nobody in. The `Origin` header must be the console's, and the request must carry the " +
            "`ostrom_email_link` cookie of the browser that asked for the link (`requestSignInEmail`)."
        body<VerifyMagicLinkRequest>("The token of the link.")
        responses {
            ok<SignedInResponse>("Signed in: the session is in the cookies, never in the body.")
            badRequest("The link has expired or was already used (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "`email_link_other_browser` (a valid link presented without the nonce " +
                "of the browser that asked for it: it is not used and still signs in there), `signup_closed` (no " +
                "account for this address and the instance is invite-only), or `forbidden`.")
        }
    }
}

/** The sign-in link that carries a session to another browser. */
private fun ApiCatalog.signInLinkOperations() {
    post("/api/v1/auth/link") {
        operationId = "exchangeSignInLink"
        tag(TAG)
        summary = "Exchange an approver's sign-in link for the session cookie."
        description = "The console and the app read the token (`/yes#signin=opk_…`) from the fragment, erase it at " +
            "once (`history.replaceState`) and post it here: it never appears in a URL the server receives. " +
            "Single-use. The `Origin` header must be an allowed one (the console's), else 403. The answer sends " +
            "the console to `/yes`. Not the e-mail sign-in, which is `/api/v1/auth/email/verify`."
        body<ExchangeSignInLinkRequest>("The link's token (`opk_…`).")
        responses {
            ok<SignedInResponse>("Signed in: the session is in the cookies, never in the body.")
            error(HttpStatusCode.Unauthorized, "The link has expired or was already used (`unauthorized`).")
            error(HttpStatusCode.Forbidden, "The `Origin` header is not an allowed one (`forbidden`).")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/links") {
        operationId = "createSignInLink"
        tag(TAG)
        summary = "Make a sign-in link for another browser."
        description = "A browser session only, else 403. `url` is the console's `/yes#signin=opk_…` (the token is " +
            "in the fragment, which no server or log receives), `token` the token alone for a QR code. Exchanged " +
            "once by `POST /api/v1/auth/link`."
        responses {
            ok<SignInLinkResponse>("The link, valid for a short while.", HttpStatusCode.Created)
            stepUp()
            tooManyAttempts()
        }
    }
}

/** The CLI's launcher login, and the phone app's token and unlock. */
private fun ApiCatalog.launcherAndMobileOperations() {
    post("/api/v1/auth/launcher/login") {
        operationId = "launcherLogin"
        tag(TAG)
        summary = "Exchange a GitHub token proven by the CLI for a device token."
        description = "A GitHub token of Ostrom's GitHub App, for a CLI token."
        body<LauncherLoginRequest>("The GitHub token, and what the CLI is (the token's label).")
        responses {
            ok<LauncherLoginResponse>("The user's login, and the CLI token, given once.")
            error(HttpStatusCode.Unauthorized, "GitHub did not issue this token to Ostrom (`unauthorized`).")
            notFound("No Ostrom account signs in with this GitHub account (`not_found`).")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/mobile/token") {
        operationId = "mobileToken"
        tag(TAG)
        summary = "Exchange the one-time code of the mobile sign-in (PKCE) for a token."
        description = "The code works once (a wrong verifier spends it), for 60 s, with the exact `redirectUri` of " +
            "the start. The token (`osm_…`, 30 days) answers a gate only within 5 minutes of `unlockMobileApp`. A " +
            "person keeps 5 phones: a sixth sign-in revokes the oldest app token."
        body<MobileTokenRequest>("The code, its PKCE verifier and the deep link the sign-in started with.")
        responses {
            ok<MobileTokenResponse>("The app's token, and the key that unlocks it.")
            badRequest("The code is unknown, spent or expired, or the verifier is wrong (`invalid_request`).")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/mobile/unlock") {
        operationId = "unlockMobileApp"
        tag(TAG)
        summary = "Record the phone's biometric unlock."
        description = "The app's token answers a gate for 5 minutes after it. The app's token only: 403 for a " +
            "browser session or another token, or a wrong key."
        body<MobileUnlockRequest>("The unlock key the app was given, and the request an approval is bound to.")
        responses {
            ok<MobileUnlockResponse>("When the unlock was recorded.")
            tooManyAttempts()
        }
    }
}

internal fun Responses.Builder.tooManyAttempts() = error(HttpStatusCode.TooManyRequests, ATTEMPTS)

/** A write of the console that needs a sign-in less than 5 minutes old. */
internal fun Responses.Builder.stepUp() = error(
    HttpStatusCode.Forbidden,
    "`step_up_required` (a write from the console needs a sign-in less than 5 minutes old: sign in again, then " +
        "send it again), or `forbidden`.",
)
