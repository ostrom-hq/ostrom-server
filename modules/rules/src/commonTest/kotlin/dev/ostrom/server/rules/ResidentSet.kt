// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

/**
 * Whether the server's resident-memory gauge reads the resident set of the process, as in a pod (Kotlin/Native), rather
 * than the heap in use of the test-only JVM target, which counts the garbage not yet collected.
 */
internal expect val residentSetMeasured: Boolean
