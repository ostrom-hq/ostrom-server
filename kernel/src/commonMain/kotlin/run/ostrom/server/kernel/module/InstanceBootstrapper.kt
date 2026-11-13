// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.module

/**
 * Makes the instance's one-time setup link, through `ostrom-server bootstrap`: its person signs in with it and gets the
 * first account of an invite-only instance (TeamCode's first-run administrator). The identity module binds the one
 * implementation.
 */
interface InstanceBootstrapper {
    /**
     * Makes a new setup link; an earlier one stops working.
     *
     * @return The console URL that carries it, shown once; null when the instance already has an account (its owners
     *   and admins invite people instead).
     */
    suspend fun setupLink(): SetupLinkOutput?
}

/**
 * A setup link, printed by `ostrom-server bootstrap`.
 *
 * @property url The console URL, its token in the fragment.
 * @property expiresAt When it stops working (ISO 8601).
 */
data class SetupLinkOutput(val url: String, val expiresAt: String)
