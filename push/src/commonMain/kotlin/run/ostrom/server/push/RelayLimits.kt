// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

/**
 * The relay's quotas, per minute.
 *
 * @property perInstance Bounds one Ostrom server.
 * @property perToken Bounds one phone, whatever the server.
 * @property total Bounds the whole relay (the Firebase project's own quota is shared by every instance).
 */
public data class RelayLimits(
    val perInstance: Int = DEFAULT_PER_INSTANCE,
    val perToken: Int = DEFAULT_PER_TOKEN,
    val total: Int = DEFAULT_TOTAL,
) {
    /** The defaults, per minute. */
    public companion object {
        /** One instance. */
        public const val DEFAULT_PER_INSTANCE: Int = 600

        /** One token. */
        public const val DEFAULT_PER_TOKEN: Int = 20

        /** The whole relay. */
        public const val DEFAULT_TOTAL: Int = 6_000
    }
}
