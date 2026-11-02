// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlin.uuid.Uuid

/**
 * The member a request acts for, inside their organization.
 *
 * @property orgId The caller's organization; a session of another organization does not exist for them.
 * @property memberId The caller's id.
 * @property admin Whether the caller is an owner or an admin of the organization.
 */
data class Caller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean = false,
)
