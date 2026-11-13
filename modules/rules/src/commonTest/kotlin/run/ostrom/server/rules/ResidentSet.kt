// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

/**
 * Whether the replay bench holds the process's resident-set growth: not on the JVM, whose heap grows up to its maximum
 * before a collection gives anything back, so the growth says nothing about a leak there.
 */
internal expect val residentSetMeasured: Boolean
