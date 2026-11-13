// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

/** What host protocol v1 asks of the host; [Supervisor] implements it. */
interface HostService {
    /**
     * One `list` or `call`.
     *
     * @param request The operation.
     * @throws HostFailure When the request is malformed.
     */
    suspend fun op(request: OpsRequest): OpsResponse

    /**
     * Starts a key's process in the background.
     *
     * @param request The key and its definition.
     * @throws HostFailure When the key is malformed or the host is stopping.
     */
    suspend fun warm(request: WarmRequest): KeyState

    /**
     * Stops what is no longer assigned.
     *
     * @param keys Every valid key.
     * @return How many processes it stops.
     */
    suspend fun retain(keys: Collection<String>): Int

    /**
     * An administrator's restart.
     *
     * @param key The process key.
     * @return Whether the key was known.
     */
    suspend fun stop(key: String): Boolean

    /** Every key's state. */
    suspend fun status(): HostStatus

    /** Whether the host takes new operations. */
    fun ready(): Boolean
}
