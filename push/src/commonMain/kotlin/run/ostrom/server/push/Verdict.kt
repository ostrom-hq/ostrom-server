// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

/** What the signature of a request came to: a refusal to answer as is, or the instance that signed it. */
internal sealed interface Verdict {
    /** The request is refused with [answer]. */
    class Refused(val answer: RelayAnswer) : Verdict

    /** The request is signed by instance [id] at [at], with [signature] (base64url, as received). */
    class Signed(val id: String, val at: Long, val signature: String) : Verdict
}
