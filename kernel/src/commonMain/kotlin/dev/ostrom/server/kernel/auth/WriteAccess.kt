// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.auth

/**
 * Who may call a console write route (SECURITY-AUDIT S2-52, CONVENTIONS 16.2): a person's browser session always may;
 * any other credential (a personal token, a device, an agent) only when the route says so here. A route still checks
 * its scope: this decides which kinds of credential reach it at all.
 *
 * @property mobileApp Whether the mobile app's token, presented after the phone's biometric unlock, may write.
 * @property tokenKinds The kinds of API token the route admits; none for a console write.
 */
class WriteAccess private constructor(val mobileApp: Boolean, val tokenKinds: Set<PrincipalKind>) {
    /** The usual declarations. */
    companion object {
        /** A browser session only: every console write unless it declares more. */
        val BROWSER: WriteAccess = WriteAccess(mobileApp = false, tokenKinds = emptySet())

        /** A person: a browser session or the unlocked mobile app, never a token a program can present. */
        val PERSON: WriteAccess = WriteAccess(mobileApp = true, tokenKinds = emptySet())

        /**
         * A browser session, or an API token of one of [kinds] (a personal or CLI token is [PrincipalKind.USER], an
         * agent's [PrincipalKind.SERVICE], a device's [PrincipalKind.DEVICE]): for a route made for programs.
         *
         * @param kinds The kinds of token admitted.
         *
         * @return The declaration.
         */
        fun tokens(vararg kinds: PrincipalKind): WriteAccess =
            WriteAccess(mobileApp = false, tokenKinds = kinds.toSet())
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
    PrincipalChannel.API_TOKEN -> kind in access.tokenKinds
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
