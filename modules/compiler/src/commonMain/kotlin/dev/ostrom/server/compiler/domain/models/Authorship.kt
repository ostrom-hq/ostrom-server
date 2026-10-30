// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.models

import kotlin.time.Instant

/**
 * Who changed something, and when.
 *
 * @property by The person (or the service) who changed it.
 * @property at When.
 */
data class Authorship(val by: String, val at: Instant)
