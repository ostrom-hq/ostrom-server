// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.auth.SignOutListener
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/**
 * Forgets, in every organization of the person, the phones registered with the credential they signed out with
 * (`POST /auth/logout`), or with any of their browser sessions (`POST /auth/logout/everywhere`): the app receives no
 * more yes requests (`logout` in the contract). A failure in one organization is logged and the others are still
 * cleaned: the sign-out itself is done (its credential is revoked), and a phone left behind is never told anyway,
 * since its credential is no longer live. A phone of an organization the person left is not read either: the
 * notifications reach members only.
 *
 * @param devices The phones.
 * @param organizations The person's organizations.
 * @param logger Logs an organization whose phones could not be forgotten.
 */
class PushDevicesSignOutListener(
    private val devices: PushDevicesRepository,
    private val organizations: PersonOrganizations,
    private val logger: Logger,
) : SignOutListener {
    override suspend fun signedOut(subjectId: String, credentialId: String?) {
        val user = Uuid.parseOrNull(subjectId) ?: return
        val credential = credentialId?.let { Uuid.parseOrNull(it) ?: return }
        for (org in organizations.of(user)) {
            try {
                devices.forget(org, user, credential)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn(
                    "push devices not forgotten at sign-out",
                    mapOf("org_id" to org.toString(), "error" to error::class.simpleName),
                )
            }
        }
    }
}
