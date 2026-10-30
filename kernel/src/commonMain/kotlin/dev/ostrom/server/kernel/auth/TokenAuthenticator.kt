// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

import kotlin.time.Instant

/**
 * Who made an authenticated request. The identity module decides what a token maps to; other modules only read
 * the principal (`call.principal<AuthenticatedPrincipal>()`, or `call.requirePrincipal()`).
 *
 * @property subjectId The stable id of the user, device or service the token belongs to.
 * @property kind What the subject is.
 * @property scopes What the token allows, as decided by the identity module. Empty grants no scope: a route that
 *   requires a scope refuses it. There is no implicit "everything".
 * @property channel Where the credential is held, as the identity module knows it: an API token unless it says
 *   otherwise (SECURITY-AUDIT S2-31, a yes is only given in the browser or the unlocked app).
 * @property authenticatedAt When the person last proved who they are, as the identity module knows it: the sign-in of
 *   a browser session (OAuth `max_age`/`prompt=login`, a fresh e-mail link), the app's biometric unlock; null when
 *   unknown. A yes to a high-risk action needs it within five minutes (S2-54).
 */
data class AuthenticatedPrincipal(
    val subjectId: String,
    val kind: PrincipalKind,
    val scopes: Set<String>,
    val channel: PrincipalChannel = PrincipalChannel.API_TOKEN,
    val authenticatedAt: Instant? = null,
)

/** The kinds of subject a token can belong to. */
enum class PrincipalKind { USER, DEVICE, SERVICE }

/** Where a credential is held: what a person did to present it, which no agent can do in their place. */
enum class PrincipalChannel {
    /** The session cookie of a person signed in to the console. */
    BROWSER,

    /** The token of the mobile app, presented only after the phone's biometric unlock. */
    MOBILE_APP,

    /** A token any program can present: the CLI's, a personal or a service token. */
    API_TOKEN,
}

/**
 * Turns a bearer token into a principal. The identity module binds its implementation in its Koin module; until
 * then [serverKoin][dev.ostrom.server.kernel.module.serverKoin] binds [RejectingTokenAuthenticator] (docs/MODULES.md).
 */
interface TokenAuthenticator {
    /**
     * Resolves a token.
     *
     * @param token The bearer token as sent by the client, without the `Bearer ` prefix.
     *
     * @return The principal, or null when the token is unknown, expired or revoked.
     */
    suspend fun authenticate(token: String): AuthenticatedPrincipal?
}

/** Rejects every token: the default until the identity module is registered, so no route is open by mistake. */
class RejectingTokenAuthenticator : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = null
}
