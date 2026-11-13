// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import kotlin.uuid.Uuid

/**
 * The automation [id] if [caller] owns it or administers its organization; another member's automation, like one of
 * another organization, does not exist for them.
 *
 * @throws AutomationNotFoundException Otherwise.
 */
internal suspend fun AutomationsRepository.visibleTo(caller: AutomationCaller, id: Uuid): Automation {
    val automation = get(caller.orgId, id) ?: throw AutomationNotFoundException()
    if (automation.owner != caller.memberId && !caller.admin) throw AutomationNotFoundException()
    return automation
}

/**
 * The automation [id] if [caller] owns it.
 *
 * @throws AutomationNotFoundException When they cannot see it.
 * @throws AutomationForbiddenException When they administer it without owning it.
 */
internal suspend fun AutomationsRepository.ownedBy(caller: AutomationCaller, id: Uuid): Automation {
    val automation = visibleTo(caller, id)
    if (automation.owner != caller.memberId) {
        throw AutomationForbiddenException("Only the owner of an automation does this.")
    }
    return automation
}
