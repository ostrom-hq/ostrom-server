// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class, DelicateCoroutinesApi::class)

package dev.ostrom.server.app

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertTrue

class MemoryTest {
    /**
     * Chart load (ostrom-e2e), relay leg: under the chart's 256 Mi, a replica holds 37 model streams at once and turns
     * the rest away, yet its memory climbed phase after phase at the same 37 held calls (106, 126, 147, 148, 159, 186
     * MiB at rest, run 41931444182) until the end of the 300 streams OOMKilled a pod (41957078621, 42085793965); the
     * green runs peaked at 252 and 258 MiB. The same server built with Kotlin/Native's allocator without pages stayed
     * at 73 to 88 MiB over the whole ramp (42087325520). Here 64 threads, as many as CIO's Dispatchers.IO, serve rounds
     * of calls that allocate buffers and objects of mixed sizes, a few of them held until the next round: once the
     * first rounds are behind, what the process keeps after a collection must stop growing.
     */
    @Test
    fun roundsOfCallsOnManyThreadsDoNotRatchetTheMemory() = runBlocking {
        if (rssMiB() == null) return@runBlocking // No /proc: macOS.
        val pool = newFixedThreadPoolContext(THREADS, "memory-test")
        val afterCollection = mutableListOf<Long>()
        try {
            var held: List<Any> = emptyList()
            repeat(ROUNDS) { round ->
                held = (0 until THREADS).map { thread -> async(pool) { calls(thread * PRIME + round) } }.awaitAll().flatten()
                GC.collect()
                afterCollection += rssMiB()!!
            }
            check(held.isNotEmpty())
        } finally {
            pool.close()
        }
        val growth = afterCollection.last() - afterCollection[WARM_ROUNDS - 1]
        assertTrue(
            growth <= TOLERANCE_MIB,
            "resident memory after each round's collection (MiB): $afterCollection, +$growth MiB past round $WARM_ROUNDS",
        )
    }

    /** [CALLS] calls on this thread, each with its buffers and texts; one in [HELD_EVERY] stays open (returned). */
    private fun calls(seed: Int): List<Any> {
        val held = ArrayList<Any>()
        for (call in 0 until CALLS) {
            val objects = ArrayList<Any>(BUFFERS + TEXTS)
            for (i in 0 until BUFFERS) objects += ByteArray(SIZES[(i * STRIDE + seed + call) % SIZES.size])
            for (i in 0 until TEXTS) objects += "call $seed-$call-$i".repeat(1 + i % REPEATS)
            if (call % HELD_EVERY == 0) held += objects
        }
        return held
    }

    private fun rssMiB(): Long? = readFile("/proc/self/status")
        ?.lineSequence()?.firstOrNull { it.startsWith("VmRSS:") }
        ?.substringAfter(":")?.trim()?.substringBefore(" ")?.toLong()?.div(KIB_PER_MIB)

    private fun readFile(path: String): String? {
        val file = fopen(path, "r") ?: return null
        try {
            return memScoped {
                val buffer = allocArray<ByteVar>(BUFFER)
                buildString {
                    while (fgets(buffer, BUFFER, file) != null) append(buffer.toKString())
                }
            }
        } finally {
            fclose(file)
        }
    }

    private companion object {
        const val THREADS = 64
        const val ROUNDS = 6
        const val WARM_ROUNDS = 2
        const val TOLERANCE_MIB = 16
        const val CALLS = 400
        const val HELD_EVERY = 50
        const val BUFFERS = 60
        const val TEXTS = 40
        const val STRIDE = 7
        const val REPEATS = 5
        const val PRIME = 31
        const val KIB_PER_MIB = 1024
        const val BUFFER = 4096
        val SIZES = intArrayOf(24, 40, 56, 72, 96, 128, 200, 256, 384, 512, 768, 1024, 2048, 4096, 8192)
    }
}
