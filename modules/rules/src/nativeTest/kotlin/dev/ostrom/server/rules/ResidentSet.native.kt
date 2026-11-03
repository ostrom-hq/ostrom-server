// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

/** Kotlin/Native reads the resident set (`/proc/self/statm`), as the pod's limit counts it. */
internal actual val residentSetMeasured: Boolean = true
