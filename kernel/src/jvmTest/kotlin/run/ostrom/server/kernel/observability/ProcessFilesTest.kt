// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.observability

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The JVM reads its resident set and its container's limit from Linux, as the native binary did. */
class ProcessFilesTest {
    private val directory = createTempDirectory("ostrom-proc").toFile().apply { deleteOnExit() }

    private fun file(name: String, text: String): File = File(directory, name).apply {
        writeText(text)
        deleteOnExit()
    }

    @Test
    fun theResidentSetIsVmRssInBytes() {
        val status = file("status", "Name:\tjava\nVmPeak:\t 9000 kB\nVmRSS:\t  345678 kB\nThreads:\t64\n")
        assertEquals(345_678L * 1024, residentBytes(status))
    }

    @Test
    fun noVmRssNoReading() {
        assertNull(residentBytes(file("kernel-thread", "Name:\tkthreadd\nThreads:\t1\n")))
        assertNull(residentBytes(file("garbled", "VmRSS:\tlots kB\n")))
        assertNull(residentBytes(File(directory, "missing")))
    }

    @Test
    fun theLimitIsTheFirstLineOfItsFile() {
        assertEquals("1073741824", firstLine(file("memory.max", "1073741824\n")))
        assertEquals("max", firstLine(file("unbounded", "max\n")))
        assertNull(firstLine(file("empty", "")))
        assertNull(firstLine(File(directory, "none")))
    }

    @Test
    fun cgroupV1IsReadWhenV2HasNoLimit() {
        val v1 = file("memory.limit_in_bytes", "536870912\n")
        assertEquals("1073741824", memoryLimit(file("v2", "1073741824\n"), v1))
        assertEquals("536870912", memoryLimit(File(directory, "no-v2"), v1))
        assertNull(memoryLimit(File(directory, "no-v2"), File(directory, "no-v1")))
    }

    @Test
    fun theProcessFilesAreReadWhereTheyExist() {
        // Linux (CI, the image): a resident set and maybe a limit; macOS: neither, and no failure.
        val resident = processResidentBytes()
        assertEquals(File("/proc/self/status").exists(), resident != null)
        processMemoryLimit()
    }
}
