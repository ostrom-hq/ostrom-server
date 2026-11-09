// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)

package run.ostrom.server.app

import run.ostrom.server.app.threads.ostrom_name_thread
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.concurrent.Worker

/** Whether this thread already carries its pool's name. */
@ThreadLocal
private var named = false

/**
 * Gives the calling thread the name of its Kotlin/Native worker, shortened to what Linux keeps (15 characters):
 * `io-17` for the 18th thread of Dispatchers.IO, `default-2` for one of Dispatchers.Default. Kotlin/Native starts its
 * workers without a name, so every thread of the server showed as `ostrom-server` and a load test could not tell a
 * pool that grows from one that does not (CHARGE 41672759077). The number is the order in which the pool started the
 * thread: the highest says how many it ever started. Once per thread; a failure only leaves the old name.
 */
internal fun nameThisThread() {
    if (named) return
    named = true
    ostrom_name_thread(threadName(Worker.current.name))
}

/**
 * The thread name of the worker named [worker].
 *
 * @param worker The worker's name, `Dispatchers.IO-17` for one of the threads kotlinx.coroutines starts.
 *
 * @return At most 15 characters.
 */
internal fun threadName(worker: String): String =
    worker.replace("Dispatchers.IO-", "io-").replace("Dispatchers.Default-", "default-").take(MAX_THREAD_NAME)

private const val MAX_THREAD_NAME = 15
