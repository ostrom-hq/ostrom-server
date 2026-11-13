// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.testing

/**
 * The drifts between the server's answers and the ostrom-core contract that are known and waiting for their fix: the
 * server's, then the contract's by an additive change. Each fix removes its lines; the list only gets shorter. A drift
 * absent from it fails the test that met it ([ContractObserver.finish]); a line no drift meets any more is reported by
 * `scripts/check-contract-coverage.py`. Found by the first run of the check on every served operation (#246).
 */
object KnownDrifts {
    /** Each drift's key ([Drift.key], such as `listThings 200 $.id MISSING`) to why it waits. */
    val drifts: Map<String, String> = mapOf(
        // Seen once the server reads ostrom-core 0.1.467 or later, where grantSignUp is served: the contract declares
        // no 403 for a sign-up refused to a stranger. Its fix is ostrom-core's, by an additive 403.
        "grantSignUp 403 \$ STATUS" to "ostrom-core declares no 403 for grantSignUp",
    )
}
