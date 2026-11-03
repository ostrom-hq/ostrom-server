// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

/**
 * The drifts between the server's answers and the ostrom-core contract that are known and waiting for their fix: the
 * server's, then the contract's by an additive change. Each fix removes its lines; the list only gets shorter. A drift
 * absent from it fails the test that met it ([ContractObserver.finish]).
 */
object KnownDrifts {
    /** Each drift's key ([Drift.key]) and why it waits. */
    val drifts: Map<String, String> = emptyMap()
}
