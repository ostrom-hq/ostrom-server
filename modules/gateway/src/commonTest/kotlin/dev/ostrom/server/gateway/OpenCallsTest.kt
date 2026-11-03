// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway

import dev.ostrom.server.gateway.domain.OpenCalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenCallsTest {
    @Test
    fun aReplicaAdmitsUpToItsCeilingThenAClosedCallFreesAPlace() {
        val calls = OpenCalls(max = 2)
        assertTrue(calls.tryOpen())
        assertTrue(calls.tryOpen())
        assertFalse(calls.tryOpen(), "A third call waits for no one: it is refused")
        assertEquals(2, calls.open)
        calls.close()
        assertEquals(1, calls.open)
        assertTrue(calls.tryOpen())
        assertFalse(calls.tryOpen())
    }

    /** chart-load 41612342204 opened 200 streams in under half a second: the ceiling holds when they race. */
    @Test
    fun racingCallsNeverPassTheCeiling() = runBlocking {
        val calls = OpenCalls(max = 50)
        val admitted = (1..400).map { async(Dispatchers.Default) { calls.tryOpen() } }.awaitAll().count { it }
        assertEquals(50, admitted)
        assertEquals(50, calls.open)
        (1..50).map { async(Dispatchers.Default) { calls.close() } }.awaitAll()
        assertEquals(0, calls.open)
    }
}
