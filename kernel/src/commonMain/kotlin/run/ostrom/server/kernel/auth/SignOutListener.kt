// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.auth

/**
 * Told when a person signs out, so that a module drops what it tied to the credential that ends: the phones
 * registered for push notifications with it stop receiving that person's notifications (decision APP, 30/10 06:10).
 * The identity module calls every listener bound in Koin after the credential is revoked; a listener that fails makes
 * the sign-out answer an error, never leaves the credential alive.
 */
fun interface SignOutListener {
    /**
     * The credential no longer works.
     *
     * @param subjectId The person ([AuthenticatedPrincipal.subjectId]).
     * @param credentialId The credential that ended ([AuthenticatedPrincipal.credentialId]), or null when every
     *   browser session of the person ended at once (sign out everywhere).
     */
    suspend fun signedOut(subjectId: String, credentialId: String?)
}
