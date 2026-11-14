// SPDX-License-Identifier: Apache-2.0
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
    val drifts: Map<String, String> = mapOf(
        // ostrom-core#189 declares it (ArtifactConflict); gradle/ostrom-core.ref moves to it with the next bump.
        "publishArtifactVersion 409 \$.current UNDECLARED" to "ostrom-core#189, past the pinned contract",
        // TC-ART-29's publishesPerHour: ostrom-core declares the 429 of both publications in the pull request after.
        "publishArtifact 429 \$ STATUS" to "the publications' 429, declared in ostrom-core next",
        "publishArtifactVersion 429 \$ STATUS" to "the publications' 429, declared in ostrom-core next",
        // Met once gradle/ostrom-core.ref passed ostrom-core#187/#188 (served markings by their own pull requests).
        "createArtifactUpload 413 \$ STATUS" to
            "an upload over the bound answers 413, which ostrom-core#188 did not declare when it marked the route " +
                "served",
    )
}
