// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

/** Starts child processes, without a shell. */
interface ProcessSpawner {
    /**
     * The program a command names: itself when it holds a slash, else the first executable of that name on [path].
     *
     * @param command The command.
     * @param path A `PATH`.
     * @return Its absolute path, or null when there is none.
     */
    fun resolve(command: String, path: String): String?

    /**
     * Starts a program.
     *
     * @param program The absolute path of the program.
     * @param argv Its whole argv, the program's name first.
     * @param environment Its whole environment.
     * @param workDir Its working directory.
     * @throws ChildIoException When it cannot be started.
     */
    fun spawn(program: String, argv: List<String>, environment: Map<String, String>, workDir: String): ChildProcess
}
