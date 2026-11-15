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
        // An offer's body over its limit (bodyLimit, refused before it is read): the contract, served since
        // ostrom-core#286, does not declare that 413 yet (the runner channel's are, since ostrom-core#293).
        "transferCloudSession 413 \$ STATUS" to "the contract does not declare the transfer offer's 413 (body limit)",
        // A Discord interaction's answer (served since ostrom-core#296): the contract declares `data` without the
        // message fields Discord reads (content, flags, components, allowed_mentions).
        "receiveDiscordInteraction 200 \$.data.allowed_mentions UNDECLARED" to
            "the contract does not declare the interaction message's allowed_mentions",
        "receiveDiscordInteraction 200 \$.data.components UNDECLARED" to
            "the contract does not declare the interaction message's components",
        "receiveDiscordInteraction 200 \$.data.content UNDECLARED" to
            "the contract does not declare the interaction message's content",
        "receiveDiscordInteraction 200 \$.data.flags UNDECLARED" to
            "the contract does not declare the interaction message's flags",
    )
}
