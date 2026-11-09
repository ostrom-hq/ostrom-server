// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.domain

import run.ostrom.server.kernel.auth.GovernanceChange

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
     * @param kind A [run.ostrom.server.kernel.auth.GovernanceWrite.kind].
     *
     * @return Its [GovernanceChange].
     *
     * @throws IllegalStateException When no module owns it: a route held a write no module makes.
     */
    fun of(kind: String): GovernanceChange =
        checkNotNull(ownerOf(kind)) { "No module makes the governance write $kind." }

    /**
     * The module that owns [kind], if one is bound here.
     *
     * @param kind A [run.ostrom.server.kernel.auth.GovernanceWrite.kind].
     *
     * @return Its [GovernanceChange], or null when no module of this server owns it (a module turned off since).
     */
    fun ownerOf(kind: String): GovernanceChange? = byKind[kind]
}
