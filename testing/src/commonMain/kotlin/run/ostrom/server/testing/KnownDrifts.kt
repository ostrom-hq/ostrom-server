// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.testing

/**
 * The drifts between the server's answers and the ostrom-core contract that are known and waiting for their fix: the
 * server's, then the contract's by an additive change. Each fix removes its lines; the list only gets shorter. A drift
 * absent from it fails the test that met it ([ContractObserver.finish]); a line no drift meets any more is reported by
 * `scripts/check-contract-coverage.py`. Found by the first run of the check on every served operation (#246).
 */
object KnownDrifts {
    /**
     * Each drift's key ([Drift.key], such as `listThings 200 $.id MISSING`) to why it waits. Empty: every answer the
     * tests see follows the ostrom-core contract.
     */
    val drifts: Map<String, String> = emptyMap()
}
