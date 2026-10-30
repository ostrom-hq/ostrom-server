// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets.infrastructure

/**
 * Creates a file readable by its owner only (mode 0600) from the first byte, never overwriting one: the instance's
 * secrets key is never readable by others, even briefly.
 *
 * @param path The file; its directory must exist.
 * @param content The content.
 *
 * @return True when created, false when [path] already existed (left untouched).
 */
internal expect fun createOwnerOnlyFile(path: String, content: String): Boolean
