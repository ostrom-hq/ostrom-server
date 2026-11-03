// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.kernel.auth.SignOutListener
import kotlin.uuid.Uuid

/**
 * Forgets, in every organization of the person, the phones registered with the credential they signed out with
 * (`POST /auth/logout`), or with any of their browser sessions (`POST /auth/logout/everywhere`): the app receives no
 * more yes requests (`logout` in the contract).
 *
 * @param devices The phones.
 * @param organizations The person's organizations.
 */
class PushDevicesSignOutListener(
    private val devices: PushDevicesRepository,
    private val organizations: PersonOrganizations,
) : SignOutListener {
    override suspend fun signedOut(subjectId: String, credentialId: String?) {
        val user = Uuid.parseOrNull(subjectId) ?: return
        val credential = credentialId?.let { Uuid.parseOrNull(it) ?: return }
        organizations.of(user).forEach { devices.forget(it, user, credential) }
    }
}
