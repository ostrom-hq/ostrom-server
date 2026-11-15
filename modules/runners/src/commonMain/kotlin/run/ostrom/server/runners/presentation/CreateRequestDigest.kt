// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.runners.domain.Caller
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json

private val sha256 = CryptographyProvider.Default.get(SHA256).hasher()

/**
 * The SHA-256 (hex) of the canonical request a creation's `Idempotency-Key` is kept with (TeamCode's create): the
 * decoded body, then the member it is created for.
 */
internal fun requestDigest(request: CreateCloudSessionRequest, caller: Caller): String {
    val canonical = Json.encodeToString(CreateCloudSessionRequest.serializer(), request) + "\n" + caller.memberId
    return sha256.hashBlocking(canonical.encodeToByteArray()).toHexString()
}
