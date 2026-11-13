// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.fakes

import run.ostrom.server.kernel.auth.PhoneUnlockProofs
import kotlin.time.Instant

/**
 * The app's unlocks as the identity module keeps them: [unlock] binds a token's last unlock to a request, at the
 * bench's time; [consume] uses it once, only for that request and only when recent enough.
 *
 * @param clock The bench's clock.
 */
class FakePhoneUnlocks(private val clock: SettableClock) : PhoneUnlockProofs {
    private val bound = mutableMapOf<String, Pair<String, Instant>>()

    /** The tokens a yes asked about, in order. */
    val asked = mutableListOf<String>()

    /**
     * The app unlocks for [requestId].
     *
     * @param credential The app's token.
     * @param requestId The request named by the unlock.
     */
    fun unlock(credential: String, requestId: String) {
        bound[credential] = requestId to clock.now
    }

    override suspend fun consume(credentialId: String, requestId: String, since: Instant): Boolean {
        asked += credentialId
        val proof = bound[credentialId]?.takeIf { (request, at) -> request == requestId && at >= since }
        if (proof != null) bound.remove(credentialId)
        return proof != null
    }
}
