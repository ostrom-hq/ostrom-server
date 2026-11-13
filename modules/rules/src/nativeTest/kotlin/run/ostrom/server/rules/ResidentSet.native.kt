// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

/** Kotlin/Native reads the resident set (`/proc/self/statm`), as the pod's limit counts it. */
internal actual val residentSetMeasured: Boolean = true
