// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

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
 * [DatabaseNotifications] on PostgreSQL, through sqlx4k's listener.
 *
 * @param database The PostgreSQL pool (not a wrapper of it: the listener needs the driver itself).
 */
class PostgresNotifications(private val database: IPostgresSQL) : DatabaseNotifications {
    override suspend fun notify(channel: String, payload: String) = database.notify(channel, payload)

    override suspend fun listen(channel: String, onPayload: suspend (String) -> Unit) =
        database.listen(channel) { onPayload(it.value.asString()) }
}

/**
 * [DatabaseNotifications] within this process, for a database without `LISTEN` (SQLite, a single process): a
 * notification reaches this process's listeners only, which are then every listener there is.
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

/**
 * The notifications of a database: PostgreSQL's when [driver] is a PostgreSQL pool, else within this process.
 *
 * @param driver The database, as [connectPostgres] opened it.
 *
 * @return The notifications.
 */
fun databaseNotifications(driver: Driver): DatabaseNotifications =
    (driver as? IPostgresSQL)?.let(::PostgresNotifications) ?: InProcessNotifications()
