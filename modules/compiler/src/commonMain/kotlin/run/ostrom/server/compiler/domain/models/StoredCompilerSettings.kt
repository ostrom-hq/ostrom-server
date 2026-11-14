// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.models

import kotlin.time.Instant

/**
 * An organization's compiler settings as an owner or an admin saved them last.
 *
 * @property settings The settings.
 * @property updatedBy Who saved them (a login).
 * @property updatedAt When, the version a later save names.
 */
data class StoredCompilerSettings(val settings: CompilerSettings, val updatedBy: String, val updatedAt: Instant)
