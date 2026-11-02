// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The text of `/proc/self/status` (Linux), empty where there is none. */
internal expect fun processStatus(): String

/** The names of this process's threads (`/proc/self/task/<tid>/comm`), empty where there are none. */
internal expect fun threadNames(): List<String>

// Thread names without their numbers, counted: `tokio-runtime-w ×8`.
private fun threadKinds(): String = threadNames()
    .groupingBy { it.trim().trimEnd { c -> c.isDigit() || c == '-' || c == '#' } }
    .eachCount()
    .entries
    .sortedByDescending { it.value }
    .joinToString(",") { "${it.key.replace(' ', '_')}x${it.value}" }

/** The Kotlin objects alive at the start of the last collection, in kB (native); the used heap on the JVM. */
internal expect fun kotlinHeapKb(): Long

/** Collects the Kotlin heap, then says what was alive before and after, and the resident memory left. */
internal expect fun afterCollection(): String

/** A number of [status]: `VmRSS` in kB, `Threads` as a count; 0 when absent. */
internal fun statusField(status: String, name: String): Long = status.lineSequence()
    .firstOrNull { it.startsWith("$name:") }
    ?.substringAfter(':')
    ?.trim()
    ?.substringBefore(' ')
    ?.toLongOrNull() ?: 0

/**
 * Runs [block] while sampling the resident memory and the threads of this process every millisecond, then prints the
 * peaks above the start (`PEAKS stage=… rss_kb=… threads=…`): what a burst of requests costs the server.
 */
internal suspend fun <T> peaks(stage: String, block: suspend CoroutineScope.() -> T): T = coroutineScope {
    val start = processStatus()
    val rss = statusField(start, "VmRSS")
    val threads = statusField(start, "Threads")
    var peakRss = rss
    var peakThreads = threads
    val heap = kotlinHeapKb()
    var peakHeap = heap
    val sampler = launch(Dispatchers.Default) {
        while (isActive) {
            val now = processStatus()
            peakRss = maxOf(peakRss, statusField(now, "VmRSS"))
            peakThreads = maxOf(peakThreads, statusField(now, "Threads"))
            peakHeap = maxOf(peakHeap, kotlinHeapKb())
            delay(1)
        }
    }
    try {
        block()
    } finally {
        sampler.cancelAndJoin()
        println(
            "PEAKS stage=$stage rss_kb=$rss peak_rss_kb=$peakRss delta_rss_kb=${peakRss - rss} " +
                "threads=$threads peak_threads=$peakThreads kinds=${threadKinds()} " +
                "heap_kb=$heap peak_heap_kb=$peakHeap ${afterCollection()}",
        )
    }
}
