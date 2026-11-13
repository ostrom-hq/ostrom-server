// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts processes with `ProcessBuilder`: the argv goes to `execve` as given, with an environment built from scratch.
 *
 * @param table Signals the processes' descendants.
 */
class JvmProcessSpawner(private val table: ProcessTable) : ProcessSpawner {
    override fun resolve(command: String, path: String): String? = if ('/' in command) {
        command.takeIf { executable(Path.of(it)) }
    } else {
        path.split(':').filter { it.isNotEmpty() }.map { Path.of(it, command) }
            .firstOrNull { executable(it) }?.toString()
    }

    private fun executable(file: Path) = Files.isRegularFile(file) && Files.isExecutable(file)

    override fun spawn(
        program: String,
        argv: List<String>,
        environment: Map<String, String>,
        workDir: String,
    ): ChildProcess {
        // ProcessBuilder would look a bare command up on the host's own PATH: the program is resolved already.
        val builder = ProcessBuilder(listOf(program) + argv.drop(1)).directory(File(workDir))
        builder.environment().clear()
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (_: IOException) {
            throw ChildIoException("The process could not be started.")
        }
        return JvmChildProcess(process, table)
    }
}
