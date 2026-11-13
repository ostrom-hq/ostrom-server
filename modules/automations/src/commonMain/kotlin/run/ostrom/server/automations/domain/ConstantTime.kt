// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

/** Compares two strings in a time that does not depend on where they differ. */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
    return difference == 0
}
