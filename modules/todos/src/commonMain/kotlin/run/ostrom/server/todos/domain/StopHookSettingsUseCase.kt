// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

import run.ostrom.server.kernel.auth.MemberDirectory

/**
 * The per-member switch of the todo Stop hook (TeamCode #246, b525389b): a member has it on until an administrator
 * turns it off. Off, every `begin` of the member's sessions is answered `allow` (`hook_disabled`) before anything
 * runs; their todo itself is unchanged.
 */
interface StopHookSettingsUseCase {
    /**
     * The members whose Stop hook is off.
     *
     * @return Their ids.
     * @throws TodoAdminRequiredException When the caller is no administrator.
     */
    suspend fun disabled(caller: TodoCaller): Set<String>

    /**
     * Turns a member's Stop hook on or off.
     *
     * @throws TodoAdminRequiredException When the caller is no administrator.
     * @throws TodoMemberNotFoundException When the member is not one of the organization.
     */
    suspend fun set(caller: TodoCaller, memberId: String, enabled: Boolean)
}

/**
 * [StopHookSettingsUseCase].
 *
 * @param repository The settings.
 * @param members Who the members are.
 */
class StopHookSettingsUseCaseImpl(
    private val repository: TodosRepository,
    private val members: MemberDirectory,
) : StopHookSettingsUseCase {
    override suspend fun disabled(caller: TodoCaller): Set<String> {
        requireAdmin(caller)
        return repository.stopHookDisabled(caller.orgId)
    }

    override suspend fun set(caller: TodoCaller, memberId: String, enabled: Boolean) {
        requireAdmin(caller)
        if (!members.isActiveMember(caller.orgId, memberId)) throw TodoMemberNotFoundException()
        repository.setStopHookEnabled(caller.orgId, memberId, enabled)
    }

    private fun requireAdmin(caller: TodoCaller) {
        if (!caller.admin || caller.agentSession != null) throw TodoAdminRequiredException()
    }
}
