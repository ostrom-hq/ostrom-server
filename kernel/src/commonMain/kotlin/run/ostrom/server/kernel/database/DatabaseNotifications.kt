// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.TextColumnType
import org.postgresql.PGConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PostgreSQL's `LISTEN`/`NOTIFY`: what one replica tells every replica at once (a new row's id, a wake-up), on the
 * database every replica already shares, with no broker. A notification carries no tenant data: PostgreSQL's
 * row-level security does not apply to it, so a payload names rows (their organization and id), which a listener
 * then reads inside that organization.
 *
 * Delivery is best effort: a notification sent while a listener's connection is being re-established is lost, so
 * what relies on it either tolerates a miss (a live feed) or also looks at the database now and then (a wake-up).
 */
interface DatabaseNotifications {
    /**
     * Sends a notification, delivered to every listener of [channel], this replica's included.
     *
     * @param channel The channel, a lower-case identifier.
     * @param payload The payload, under 8000 bytes.
     */
    suspend fun notify(channel: String, payload: String)

    /**
     * Listens on a channel, calling [onPayload] for each notification, for as long as the caller's coroutine is not
     * cancelled: it suspends while it listens. One connection of its own, re-established when it drops.
     *
     * @param channel The channel.
     * @param onPayload Called with each payload, one at a time.
     */
    suspend fun listen(channel: String, onPayload: suspend (String) -> Unit)
}

/**
 * [DatabaseNotifications] on PostgreSQL: `pg_notify` sent through the pool, and each listener on a connection of its
 * own outside the pool (a `LISTEN` lasts as long as its session), whose notifications pgjdbc hands over when asked
 * ([PGConnection.getNotifications]): asked every [poll], so that a cancelled listener stops within it.
 *
 * @param database The pool, and how to open the listening connections.
 * @param poll How long one wait for notifications lasts.
 * @param retry The pause before a dropped listening connection is opened again.
 */
class PostgresNotifications(
    private val database: JdbcDatabase,
    private val poll: Duration = 500.milliseconds,
    private val retry: Duration = 1.seconds,
) : DatabaseNotifications {
    override suspend fun notify(channel: String, payload: String) {
        database.query {
            exec("SELECT pg_notify(?, ?)", listOf(TextColumnType() to channel(channel), TextColumnType() to payload)) {}
        }
    }

    override suspend fun listen(channel: String, onPayload: suspend (String) -> Unit) {
        val name = channel(channel)
        withContext(Dispatchers.IO) {
            while (true) {
                try {
                    database.connectAlone().use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("LISTEN $name")
                            val notifications = connection.unwrap(PGConnection::class.java)
                            while (true) {
                                ensureActive()
                                // A round trip each poll: a session the network dropped silently (no reset, a NAT's
                                // timeout) fails here and is opened again, instead of hearing nothing forever.
                                statement.execute("SELECT 1")
                                notifications.getNotifications(poll.inWholeMilliseconds.toInt())
                                    .filter { it.name == name }
                                    .forEach { onPayload(it.parameter) }
                            }
                        }
                    }
                } catch (_: SQLException) {
                    // The connection dropped (a restart, a failover): opened again after a pause.
                    delay(retry)
                }
            }
        }
    }

    private fun channel(channel: String): String {
        require(CHANNEL.matches(channel)) { "A channel is a lower-case identifier, not $channel." }
        return channel
    }
}

// A channel name PostgreSQL takes as an identifier unquoted: at most 63 bytes, never anything to escape.
private val CHANNEL = Regex("[a-z_][a-z0-9_]{0,62}")

/**
 * [DatabaseNotifications] within this process, for a single process without a shared database: a notification
 * reaches this process's listeners only, which are then every listener there is.
 */
class InProcessNotifications : DatabaseNotifications {
    private val sent = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = BUFFER)

    override suspend fun notify(channel: String, payload: String) {
        sent.emit(channel to payload)
    }

    override suspend fun listen(channel: String, onPayload: suspend (String) -> Unit) {
        sent.filter { it.first == channel }.collect { onPayload(it.second) }
    }

    private companion object {
        const val BUFFER = 256
    }
}
