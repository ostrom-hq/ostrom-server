// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.presentation

import run.ostrom.server.cloud.domain.CloudAdminGate
import run.ostrom.server.cloud.domain.CloudClustersUseCase
import run.ostrom.server.cloud.domain.CloudProfilesUseCase
import run.ostrom.server.cloud.domain.CloudRuntimeUseCase
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.secrets.domain.CallerResolver

/**
 * What the cloud administration routes need.
 *
 * @property gate Who administers the instance, checked before a body is read.
 * @property runtime The instance's runtime.
 * @property clusters The instance's clusters.
 * @property profiles The organizations' profiles.
 * @property callers Resolves the organization member of a request.
 * @property audit The organizations' audit log.
 * @property publicUrl The server's public URL, the callback when the runtime names none.
 */
class CloudAdminRoutesDependencies(
    val gate: CloudAdminGate,
    val runtime: CloudRuntimeUseCase,
    val clusters: CloudClustersUseCase,
    val profiles: CloudProfilesUseCase,
    val callers: CallerResolver,
    val audit: OrgAuditTrail,
    val publicUrl: String,
)
