// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.auth

import kotlin.uuid.Uuid

/**
 * Which models a member may use. The inference gateway binds it with the organization's model policy, so that what
 * another module answers before a model call (a launch) is what the gateway answers at the call. Unbound, each module
 * keeps its own default.
 */
interface ModelAccess {
    /**
     * Says why a member may not use a model.
     *
     * @param orgId The organization.
     * @param memberId The member.
     * @param model The model.
     *
     * @return The refusal, or null when the model is allowed.
     */
    suspend fun refusal(orgId: Uuid, memberId: Uuid, model: String): String?
}
