package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.Caller
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.TerminalTicket
import dev.ostrom.server.runners.domain.TerminalTickets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Terminal tickets in the server's memory, like the terminal relay they open (one replica serves terminals). A
 * ticket is random, lives [lifetime], opens one session's terminal once, and only its hash is kept.
 *
 * @param tokens Draws and hashes the random values.
 * @param clock The clock.
 * @param lifetime How long a ticket stays valid.
 */
class InMemoryTerminalTickets(
    private val tokens: RunnerTokens,
    private val clock: Clock,
    private val lifetime: Duration = DEFAULT_LIFETIME,
) : TerminalTickets {
    private class Entry(val caller: Caller, val sessionId: Uuid, val expiresAt: Instant)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    override suspend fun issue(caller: Caller, sessionId: Uuid): TerminalTicket {
        val value = PREFIX + tokens.mint().substringAfter('_').substringAfter('_')
        val expiresAt = clock.now() + lifetime
        mutex.withLock {
            val now = clock.now()
            entries.entries.removeAll { it.value.expiresAt <= now }
            entries[tokens.hash(value)] = Entry(caller, sessionId, expiresAt)
        }
        return TerminalTicket(value, expiresAt)
    }

    override suspend fun redeem(value: String, sessionId: Uuid): Caller? = mutex.withLock {
        val entry = entries.remove(tokens.hash(value)) ?: return@withLock null
        entry.caller.takeIf { entry.sessionId == sessionId && clock.now() < entry.expiresAt }
    }

    companion object {
        const val PREFIX = "ostrom_term_"
        val DEFAULT_LIFETIME = 60.seconds
    }
}
