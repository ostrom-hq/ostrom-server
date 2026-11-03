// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.domain

import dev.ostrom.server.kernel.auth.GovernanceChange

/**
 * The governance writes of every module, by kind.
 *
 * @param changes The modules' [GovernanceChange]s.
 */
class GovernanceChanges(changes: List<GovernanceChange>) {
    private val byKind: Map<String, GovernanceChange> = buildMap {
        for (change in changes) {
            for (kind in change.kinds) {
                require(put(kind, change) == null) { "Two modules own the governance write $kind." }
            }
        }
    }

    /**
     * The module that owns [kind].
     *
     * @param kind A [dev.ostrom.server.kernel.auth.GovernanceWrite.kind].
     *
     * @return Its [GovernanceChange].
     *
     * @throws IllegalStateException When no module owns it: a route held a write no module makes.
     */
    fun of(kind: String): GovernanceChange =
        checkNotNull(byKind[kind]) { "No module makes the governance write $kind." }
}
