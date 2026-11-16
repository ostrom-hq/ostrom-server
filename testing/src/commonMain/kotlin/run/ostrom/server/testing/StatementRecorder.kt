// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import org.jetbrains.exposed.v1.core.IColumnType
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.atomicArrayOfNulls

/**
 * One statement the server sent, as Exposed sent it.
 *
 * @property sql Its text, with `?` placeholders.
 * @property args Its values by position, each with the column type Exposed bound it as ([replayRecorded]).
 * @property transaction Which transaction it ran in: the statements of one transaction share it.
 */
class SentStatement(val sql: String, val args: List<Pair<IColumnType<*>, Any?>>, val transaction: Int)

/**
 * What the modules of a [serverTest] send to the database while [record] runs ([ExposedRecorder]): the statements of
 * one request, to count them (no N+1) and replay them under `EXPLAIN` ([measureRead]).
 */
@OptIn(ExperimentalAtomicApi::class)
class StatementRecorder {
    private val sent = AtomicReference<Recording?>(null)
    private val transactions = AtomicInt(0)

    /**
     * Runs [block] and returns the statements sent meanwhile, by [block] and by anything else running then (a
     * background job): call it with nothing else in flight, and keep the smallest of a few recordings.
     *
     * @param block The work, a request.
     * @return The statements, in order.
     */
    suspend fun record(block: suspend () -> Unit): List<SentStatement> {
        val recording = Recording()
        sent.store(recording)
        try {
            block()
            return recording.statements()
        } finally {
            sent.store(null)
        }
    }

    /** The recording of the [record] that runs, null when none does. */
    internal val recording: Recording? get() = sent.load()

    internal fun newTransaction(): Int = transactions.addAndFetch(1)
}

/**
 * The statements of one recording, appended from any thread without a lock: a slot is claimed, then filled.
 * A request sends a few dozen statements; past [CAPACITY], the next one fails the test.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class Recording {
    private val slots = atomicArrayOfNulls<SentStatement>(CAPACITY)
    private val count = AtomicInt(0)

    fun add(statement: SentStatement) = slots.storeAt(count.fetchAndAdd(1), statement)

    // A slot claimed but not filled yet belongs to a statement still being sent: it is left out.
    fun statements(): List<SentStatement> = List(count.load()) { slots.loadAt(it) }.filterNotNull()

    private companion object {
        const val CAPACITY = 10_000
    }
}
