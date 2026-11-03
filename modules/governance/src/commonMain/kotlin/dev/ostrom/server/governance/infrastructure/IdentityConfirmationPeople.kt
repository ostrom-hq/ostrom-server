// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.infrastructure

import dev.ostrom.server.governance.domain.ConfirmationPeople
import dev.ostrom.server.governance.domain.PersonName
import dev.ostrom.server.identity.domain.UsersRepository
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
