// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Whether the server takes new traffic, answered by `/ready`. It becomes ready once started (migrations checked,
 * modules initialized) and stops being ready for good when it starts draining on SIGTERM, before the engine stops,
 * so the load balancer sends no new request to a replica that is going away.
 */
@OptIn(ExperimentalAtomicApi::class)
class Readiness {
    private val draining = AtomicBoolean(false)

    /** True once [drain] was called. */
    val isDraining: Boolean get() = draining.load()

    /** Stops taking new traffic: `/ready` answers 503 from now on. */
    fun drain() {
        draining.store(true)
    }
}
