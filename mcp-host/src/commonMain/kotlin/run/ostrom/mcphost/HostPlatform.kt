// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlin.coroutines.CoroutineContext

/**
 * What the supervisor needs from the machine, replaced by fakes in tests.
 *
 * @property spawner Starts processes.
 * @property table Reads and signals the processes' descendants.
 * @property files The per-key directories.
 * @property events Where the host's events go.
 * @property io Where blocking work runs (pipes, files).
 * @property now The wall clock, epoch milliseconds.
 */
class HostPlatform(
    val spawner: ProcessSpawner,
    val table: ProcessTable,
    val files: HostFiles,
    val events: EventSink,
    val io: CoroutineContext,
    val now: () -> Long,
)
