// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.models

import kotlin.time.Instant

/**
 * A save of an organization's compiler settings, as an owner or an admin sends it (`PUT /api/v1/packages/settings`).
 *
 * @property pin `auto` or `manual`.
 * @property codePublishers Who may publish code in a script, or null for every person who decides.
 * @property prodApprover The approver of a gate that names none, or null for none.
 * @property expected The `updatedAt` of the settings edited, null when none were stored.
 */
data class CompilerSettingsChange(
    val pin: String,
    val codePublishers: List<String>? = null,
    val prodApprover: String? = null,
    val expected: Instant? = null,
)
