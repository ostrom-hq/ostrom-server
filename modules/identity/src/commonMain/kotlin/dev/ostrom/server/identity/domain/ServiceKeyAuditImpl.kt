// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import dev.ostrom.server.kernel.auth.ServiceKeyAudit
import dev.ostrom.server.kernel.auth.ServiceKeyCall

/**
 * [ServiceKeyAudit] on the signed audit log: each call a service key makes is a `service_key.used` event of its
 * organization's chain, whose target is the key (no person acts), with the operation and the path.
 *
 * @param audit The audit log.
 */
class ServiceKeyAuditImpl(private val audit: AuditLog) : ServiceKeyAudit {
    override suspend fun record(call: ServiceKeyCall) {
        val details = mapOf("operation" to call.operation, "path" to call.target)
        audit.record(AuditEntry(call.orgId, null, "service_key.used", "service_key", call.keyId, details))
    }
}
