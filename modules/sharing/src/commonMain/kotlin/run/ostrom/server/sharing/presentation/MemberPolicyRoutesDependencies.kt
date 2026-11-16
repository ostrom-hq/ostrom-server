// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.sharing.domain.MemberPoliciesUseCase

/**
 * What the member policy routes need.
 *
 * @property callers Resolves the member a credential acts for.
 * @property policies The members' policies.
 * @property audit Writes a change of a policy to the organization's audit log.
 */
data class MemberPolicyRoutesDependencies(
    val callers: CallerResolver,
    val policies: MemberPoliciesUseCase,
    val audit: OrgAuditTrail,
)
