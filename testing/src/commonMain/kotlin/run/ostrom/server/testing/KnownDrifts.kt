// SPDX-License-Identifier: Apache-2.0
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
        // The runner channel refuses a body over its limit (bodyLimit) before reading it; the contract, served since
        // ostrom-core#287 and #289, does not declare that 413 yet: an additive core change declares it.
        "cloudRunnerAgent 413 \$ STATUS" to "the contract does not declare the runner channel's 413 (body limit)",
        "issueRunnerInference 413 \$ STATUS" to "the contract does not declare the runner channel's 413 (body limit)",
        // An offer's body over its limit, likewise (served since ostrom-core#286).
        "transferCloudSession 413 \$ STATUS" to "the contract does not declare the transfer offer's 413 (body limit)",
    )
}
