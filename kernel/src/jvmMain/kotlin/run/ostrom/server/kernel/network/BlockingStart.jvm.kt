// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.network

import kotlinx.coroutines.runBlocking

internal actual fun <T> blockingStart(block: suspend () -> T): T = runBlocking { block() }
