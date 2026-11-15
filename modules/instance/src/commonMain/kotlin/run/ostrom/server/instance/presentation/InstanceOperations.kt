// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.ok

/** The tag of the instance's operations, the kernel's: the server itself. */
private const val TAG = "meta"

/** The instance module's operations: the instance and its signing keys, read before signing in. */
internal fun ApiCatalog.instanceOperations() {
    get("/api/v1/instance") {
        operationId = "getInstance"
        tag(TAG)
        summary = "The instance and the key it signs with now; unauthenticated, clients verify signatures before " +
            "signing in."
        responses { ok<InstanceResponse>("The instance and its current signing key.") }
    }
    get("/api/v1/instance/keys") {
        operationId = "listInstanceKeys"
        tag(TAG)
        summary = "Every signing key the instance has used, oldest first."
        responses { ok<SigningKeysResponse>("The keys, oldest first.") }
    }
}
