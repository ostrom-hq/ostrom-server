// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

/** The JVM's heap grows before it is collected: its resident set proves no leak. */
internal actual val residentSetMeasured: Boolean = false
