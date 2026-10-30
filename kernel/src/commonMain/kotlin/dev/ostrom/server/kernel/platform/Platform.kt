// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.platform

/**
 * Reads an environment variable of the process.
 *
 * @param name The variable name.
 *
 * @return Its value, or null when it is not set.
 */
expect fun getEnv(name: String): String?

/**
 * Writes a file readable by its owner only (mode 0600), atomically and without ever overwriting: the content goes to
 * a temporary file created with that mode (never readable by others, even briefly), flushed to disk, then hard-linked
 * to [path], which fails if [path] already exists. The temporary file is removed in every case.
 *
 * @param path The file to create; its directory must exist.
 * @param content The content.
 *
 * @return True when the file was created, false when [path] already existed (its content is left untouched).
 */
internal expect fun createPrivateFile(path: String, content: String): Boolean
