// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.auth

/**
 * Who may call a console write route (SECURITY-AUDIT S2-52, CONVENTIONS 16.2): a person's browser session always may;
 * any other credential (a personal token, a device, an agent) only when the route says so here. A route still checks
 * its scope: this decides which kinds of credential reach it at all.
 *
 * @property mobileApp Whether the mobile app's token, presented after the phone's biometric unlock, may write.
 * @property signedIn Whether a person's sign-in token ([AuthenticatedPrincipal.signedIn], the CLI's) may write.
 * @property tokenKinds The kinds of API token the route admits; none for a console write.
 * @property governing The governance scope ([GovernanceScopes]) that also admits a person's token holding it (the
 *   token of `ostrom login --admin`), or null.
 */
class WriteAccess private constructor(
    val mobileApp: Boolean,
    val signedIn: Boolean = false,
    val tokenKinds: Set<PrincipalKind>,
    val governing: String? = null,
) {
    /** The usual declarations. */
    companion object {
        /** A browser session only: every console write unless it declares more. */
        val BROWSER: WriteAccess = WriteAccess(mobileApp = false, tokenKinds = emptySet())

        /**
         * A person, as TeamCode's interactive login: a browser session, the unlocked mobile app or the token of
         * `ostrom login` ([AuthenticatedPrincipal.signedIn]); never a personal token made in the console, a service
         * key, an agent's or a device's token.
         */
        val PERSON: WriteAccess = WriteAccess(mobileApp = true, signedIn = true, tokenKinds = emptySet())

        /**
         * A browser session, or an API token of one of [kinds] (a personal or CLI token is [PrincipalKind.USER], an
         * agent session's [PrincipalKind.AGENT], a machine's [PrincipalKind.SERVICE] or [PrincipalKind.DEVICE]): for a
         * route made for programs.
         *
         * @param kinds The kinds of token admitted.
         *
         * @return The declaration.
         */
        fun tokens(vararg kinds: PrincipalKind): WriteAccess =
            WriteAccess(mobileApp = false, tokenKinds = kinds.toSet())

        /**
         * A governance write: a browser session, a person's token that holds [scope] (see [governs]), or an API token
         * of one of [kinds].
         *
         * @param scope One of [GovernanceScopes].
         * @param kinds The kinds of token also admitted, whatever they hold (a service key, for instance).
         *
         * @return The declaration.
         */
        fun governance(scope: String, vararg kinds: PrincipalKind): WriteAccess =
            WriteAccess(mobileApp = false, tokenKinds = kinds.toSet(), governing = scope)
    }
}

/**
 * Whether this credential may call a write route declared with [access].
 *
 * @param access What the route admits besides a browser session.
 *
 * @return True when allowed.
 */
fun AuthenticatedPrincipal.mayWrite(access: WriteAccess): Boolean = when (channel) {
    PrincipalChannel.BROWSER -> kind == PrincipalKind.USER
    PrincipalChannel.MOBILE_APP -> kind == PrincipalKind.USER && access.mobileApp
    PrincipalChannel.API_TOKEN -> kind in access.tokenKinds || access.governing?.let { governs(it) } == true ||
        (access.signedIn && signedIn && kind == PrincipalKind.USER)
}

/**
 * Refuses a credential a write route does not admit (see [mayWrite]).
 *
 * @param access What the route admits besides a browser session; a browser session only by default.
 *
 * @throws BrowserSessionRequiredException When the credential may not write there.
 */
fun AuthenticatedPrincipal.requireWrite(access: WriteAccess = WriteAccess.BROWSER) {
    if (!mayWrite(access)) throw BrowserSessionRequiredException()
}
