// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress

internal actual val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO

/** The JDK's resolver, for the tests that run on the JVM (never shipped). */
internal actual fun systemLookup(host: String): List<String> =
    InetAddress.getAllByName(host).map { it.hostAddress.substringBefore('%') }
