package dev.ostrom.server.mcprelay.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Concurrency and per-minute limits of relayed operations, per member and server and per server across the org.
 * Held in this replica's memory: with several replicas each enforces its own share (the journal stays shared).
 *
 * @property memberConcurrency Running operations per member and server.
 * @property serverConcurrency Running operations per server.
 * @property memberPerMinute Operations per member and server per minute.
 * @property serverPerMinute Operations per server per minute.
 */
data class McpLimits(
    val memberConcurrency: Int = 4,
    val serverConcurrency: Int = 32,
    val memberPerMinute: Int = 120,
    val serverPerMinute: Int = 1200,
)

/**
 * Admits operations under [McpLimits].
 *
 * @param limits The limits.
 * @param clock The time, for the per-minute windows.
 */
class McpCallLimiter(private val limits: McpLimits, private val clock: Clock) {
    private val mutex = Mutex()
    private val running = mutableMapOf<String, Int>()
    private val windows = mutableMapOf<String, Pair<Long, Int>>()

    /**
     * Takes a slot.
     *
     * @return True when admitted; the caller then calls [release] exactly once.
     */
    suspend fun acquire(orgId: Uuid, memberId: Uuid, serverId: Uuid): Boolean = mutex.withLock {
        val minute = clock.now().epochSeconds / 60
        val member = "$orgId:$serverId:$memberId"
        val server = "$orgId:$serverId"
        val admitted = (running[member] ?: 0) < limits.memberConcurrency &&
            (running[server] ?: 0) < limits.serverConcurrency &&
            count(member, minute) < limits.memberPerMinute &&
            count(server, minute) < limits.serverPerMinute
        if (admitted) {
            running[member] = (running[member] ?: 0) + 1
            running[server] = (running[server] ?: 0) + 1
            windows[member] = minute to count(member, minute) + 1
            windows[server] = minute to count(server, minute) + 1
            if (windows.size > MAX_KEYS) windows.entries.removeAll { it.value.first < minute }
        }
        admitted
    }

    /** Returns a slot taken by [acquire]. */
    suspend fun release(orgId: Uuid, memberId: Uuid, serverId: Uuid) {
        mutex.withLock {
            decrement("$orgId:$serverId:$memberId")
            decrement("$orgId:$serverId")
        }
    }

    private fun count(key: String, minute: Long): Int = windows[key]?.takeIf { it.first == minute }?.second ?: 0

    private fun decrement(key: String) {
        val next = (running[key] ?: 0) - 1
        if (next <= 0) running.remove(key) else running[key] = next
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
