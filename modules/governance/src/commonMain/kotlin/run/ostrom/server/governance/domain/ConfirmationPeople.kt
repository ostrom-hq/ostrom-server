// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.domain

/**
 * A person as a confirmation shows them.
 *
 * @property login Their login.
 * @property name Their name.
 */
data class PersonName(val login: String, val name: String)

/** Names the people of a confirmation. */
interface ConfirmationPeople {
    /**
     * Names a person.
     *
     * @param userId Their user id.
     *
     * @return Their name, or null when unknown.
     */
    suspend fun nameOf(userId: String): PersonName?
}
