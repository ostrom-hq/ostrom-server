// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

/**
 * The drifts between the server's answers and the ostrom-core contract that are known and waiting for their fix: the
 * server's, then the contract's by an additive change. Each fix removes its lines; the list only gets shorter. A drift
 * absent from it fails the test that met it ([ContractObserver.finish]); a line no drift meets any more is reported by
 * `scripts/check-contract-coverage.py`. Found by the first run of the check on every served operation (#246).
 */
object KnownDrifts {
    private const val HELD = "Contract: a token's member write is held (202); ostrom-core#107 declares it."

    /**
     * Each drift's key ([Drift.key], such as `listThings 200 $.id MISSING`) to why it waits. The held member writes
     * wait for ostrom-core#107, which declares their 202.
     */
    val drifts: Map<String, String> = listOf(
        "createInvitationLink 202 $ STATUS",
        "inviteOrgMembers 202 $ STATUS",
        "removeOrgMember 202 $ STATUS",
        "updateOrgMember 202 $ STATUS",
    ).associateWith { HELD }
}
