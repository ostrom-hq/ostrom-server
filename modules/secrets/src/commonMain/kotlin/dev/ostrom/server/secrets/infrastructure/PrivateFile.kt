// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets.infrastructure

/**
 * Creates a file readable by its owner only (mode 0600) from the first byte, never overwriting one: the instance's
 * secrets key is never readable by others, even briefly. The content is written and flushed to a temporary file, which
 * is then hard-linked to [path]: [path] appears complete or not at all, so another replica starting at the same time,
 * or a start after a crash mid-write, never reads a half-written key.
 *
 * @param path The file; its directory must exist.
 * @param content The content.
 *
 * @return True when created, false when [path] already existed (left untouched).
 */
internal expect fun createOwnerOnlyFile(path: String, content: String): Boolean
