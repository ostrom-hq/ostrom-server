// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.ConfirmationPeople
import run.ostrom.server.governance.domain.PersonName
import run.ostrom.server.identity.domain.UsersRepository
import kotlin.uuid.Uuid

/**
 * [ConfirmationPeople] on the identity module's users.
 *
 * @param users The users.
 */
class IdentityConfirmationPeople(private val users: UsersRepository) : ConfirmationPeople {
    override suspend fun nameOf(userId: String): PersonName? =
        Uuid.parseOrNull(userId)?.let { users.get(it) }?.let { PersonName(it.login, it.displayName) }
}
