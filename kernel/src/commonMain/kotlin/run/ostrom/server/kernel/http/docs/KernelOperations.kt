// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import run.ostrom.core.api.models.Capabilities
import run.ostrom.server.kernel.http.HealthResponse

/** The tag of the server's own operations. */
internal const val META_TAG = "meta"

/** The kernel's own operations: the health probe at its API path and the capabilities. */
internal fun ApiCatalog.kernelOperations() {
    tag(META_TAG, "The server itself: whether it answers, and what it speaks and serves.")
    get("/api/v1/health") {
        operationId = "health"
        tag(META_TAG)
        summary = "Liveness"
        description = "200 `ok` as long as the process answers HTTP, without any check; a draining server stays " +
            "healthy. The same answer as the probe's `/health`."
        responses { ok<HealthResponse>("The server answers.") }
    }
    get("/api/v1/capabilities") {
        operationId = "capabilities"
        tag(META_TAG)
        summary = "The API version this server speaks, its version and the features it serves"
        description = "Read by every client after signing in, with any credential of a person, an agent or a " +
            "service. A client refuses a server whose `apiVersion` it does not speak, and hides a feature whose key " +
            "is absent from `features`: a key is listed only when the server serves that feature and it is " +
            "configured on this instance. Kept behind authentication, as TeamCode's: an anonymous call gets 401 " +
            "`unauthorized`, which tells a client it reached an Ostrom server and should sign in next."
        responses { ok<Capabilities>("What this server speaks and serves.") }
    }
}
