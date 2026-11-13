// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

/** The per-key directories under the host's data directory. */
interface HostFiles {
    /**
     * Makes a directory and its parents, private to the host's user.
     *
     * @param path The directory.
     */
    suspend fun createDirectories(path: String)

    /**
     * Removes a tree without following a link out of it.
     *
     * @param path The tree.
     */
    fun deleteRecursively(path: String)

    /**
     * The names in a directory, or none when it does not exist.
     *
     * @param path The directory.
     */
    fun list(path: String): List<String>
}
