// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The real processes, process table and files of the JVM platform, through `/bin/sh`. */
class JvmPlatformTest {
    private val table = JvmProcessTable()
    private val spawner = JvmProcessSpawner(table)
    private val root: Path = Files.createTempDirectory("ostrom-mcp-platform")

    private fun sh(script: String, env: Map<String, String> = mapOf("PATH" to "/usr/bin:/bin")) =
        spawner.spawn("/bin/sh", listOf("sh", "-c", script), env, root.toString())

    @Test
    fun aProcessReadsWritesAndExitsWithItsCode() = runBlocking {
        withTimeout(20.seconds) {
            val script = "read line; echo \"got \$line \$GREETING\"; echo oops >&2; exit 4"
            val child = sh(script, mapOf("GREETING" to "hi"))
            assertTrue(child.isAlive)
            child.write("abc\n".encodeToByteArray())
            assertEquals("got abc hi\n", child.readStdout()!!.decodeToString())
            assertEquals("oops\n", child.readStderr()!!.decodeToString())
            assertEquals(4, child.awaitExit())
            assertNull(child.readStdout())
            assertNull(child.readStderr())
            assertFalse(child.isAlive)
            child.closeStdin()
        }
    }

    @Test
    fun aClosedPipeFailsTheWriteAndTheRead() = runBlocking {
        withTimeout(20.seconds) {
            val process = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
            val child = JvmChildProcess(process, table)
            child.awaitExit()
            assertFailsWith<ChildIoException> { child.write(ByteArray(1 shl 20)) }
            process.outputStream.write(1)
            child.closeStdin()
            process.inputStream.close()
            assertNull(child.readStdout())
        }
    }

    @Test
    fun aGroupSignalStopsTheProcessAndItsChildren() = runBlocking {
        withTimeout(20.seconds) {
            val child = sh("sleep 60 & sleep 60 & echo started; wait")
            assertEquals("started\n", child.readStdout()!!.decodeToString())
            val children = table.descendants(child.pid)
            assertEquals(2, children.size)
            assertTrue(children.all { table.alive(it) })
            child.signalGroup(force = false)
            child.awaitExit()
            children.forEach { pid -> repeat(50) { if (table.alive(pid)) Thread.sleep(100) } }
            assertTrue(children.none { table.alive(it) })
            val stubborn = sh("trap '' TERM; echo ready; while true; do sleep 1; done")
            assertEquals("ready\n", stubborn.readStdout()!!.decodeToString())
            table.signal(stubborn.pid, force = false)
            stubborn.signalGroup(force = true)
            assertEquals(137, stubborn.awaitExit())
        }
    }

    @Test
    fun anAbsentProcessIsNeitherAliveNorSignalled() {
        assertFalse(table.alive(Long.MAX_VALUE))
        assertEquals(emptyList(), table.descendants(Long.MAX_VALUE))
        table.signal(Long.MAX_VALUE, force = true)
        assertNull(table.rssBytes(emptyList()))
    }

    @Test
    fun memoryIsReadFromProcOrFromPs() {
        val proc = Files.createDirectories(root.resolve("proc"))
        Files.createDirectories(proc.resolve("self"))
        Files.createDirectories(proc.resolve("1"))
        Files.writeString(proc.resolve("1/status"), "Name: x\nVmRSS:\t  2048 kB\n")
        Files.createDirectories(proc.resolve("2"))
        Files.writeString(proc.resolve("2/status"), "Name: y\n")
        Files.createDirectories(proc.resolve("3/status"))
        Files.createDirectories(proc.resolve("5"))
        Files.writeString(proc.resolve("5/status"), "VmRSS: lots\n")
        assertEquals(2048L * 1024, JvmProcessTable(proc).rssBytes(listOf(1, 2, 3, 4, 5)))
        val ps = root.resolve("ps")
        Files.writeString(ps, "#!/bin/sh\necho ' 10'\necho ' 20'\necho junk\n")
        ps.toFile().setExecutable(true)
        assertEquals(30L * 1024, JvmProcessTable(root.resolve("none"), ps.toString()).rssBytes(listOf(1)))
        val stuck = root.resolve("stuck")
        Files.writeString(stuck, "#!/bin/sh\nexec sleep 30\n")
        stuck.toFile().setExecutable(true)
        assertNull(JvmProcessTable(root.resolve("none"), stuck.toString(), 1).rssBytes(listOf(1)))
        assertNull(JvmProcessTable(root.resolve("none"), root.resolve("missing").toString()).rssBytes(listOf(1)))
    }

    @Test
    fun commandsResolveOnTheProcessPathOnly() {
        val bin = Files.createDirectories(root.resolve("bin"))
        val tool = bin.resolve("tool")
        Files.writeString(tool, "#!/bin/sh\n")
        assertNull(spawner.resolve("tool", bin.toString()))
        assertNull(spawner.resolve(tool.toString(), ""))
        tool.toFile().setExecutable(true)
        assertEquals(tool.toString(), spawner.resolve("tool", "::$bin"))
        assertEquals(tool.toString(), spawner.resolve(tool.toString(), ""))
        assertNull(spawner.resolve("tool", "/nonexistent"))
        assertFailsWith<ChildIoException> {
            spawner.spawn(tool.toString(), listOf("tool"), emptyMap(), root.resolve("missing").toString())
        }
    }

    @Test
    fun aTreeIsRemovedWithoutFollowingALink() = runBlocking {
        val files = JvmHostFiles()
        val outside = Files.createDirectories(root.resolve("outside"))
        Files.writeString(outside.resolve("keep"), "x")
        val key = root.resolve("data/key")
        files.createDirectories(key.resolve("home/deep").toString())
        Files.writeString(key.resolve("home/file"), "x")
        Files.createSymbolicLink(key.resolve("home/link"), outside)
        val locked = Files.createDirectories(key.resolve("locked"))
        Files.writeString(locked.resolve("file"), "x")
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        assertEquals(listOf("key"), files.list(root.resolve("data").toString()))
        assertEquals(emptyList(), files.list(root.resolve("absent").toString()))
        files.deleteRecursively(key.resolve("home").toString())
        assertFalse(Files.exists(key.resolve("home")))
        assertTrue(Files.exists(outside.resolve("keep")))
        files.deleteRecursively(key.toString())
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"))
        assertTrue(Files.exists(locked.resolve("file")))
        files.deleteRecursively(key.toString())
        assertFalse(Files.exists(key))
        files.deleteRecursively(key.toString())
    }
}
