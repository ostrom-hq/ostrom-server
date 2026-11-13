// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.network

import kotlinx.coroutines.runBlocking

internal actual fun <T> blockingStart(block: suspend () -> T): T = runBlocking { block() }
