// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.usecases.ApproversGraphUseCase
import run.ostrom.server.compiler.domain.usecases.GatePolicyUseCase
import run.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import run.ostrom.server.compiler.domain.usecases.PreviewPackageUseCase
import run.ostrom.server.compiler.domain.usecases.PublishPackageUseCase
import run.ostrom.server.compiler.domain.usecases.RevokePackageUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowSettingsUseCase
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.domain.services.GateActorResolver

/**
 * What the compiler routes need.
 *
 * @property actors Resolves callers (the gates module's resolver: the organization comes from the credential).
 * @property repository Reads versions, pins and the audit.
 * @property publish Compiles and pins.
 * @property preview Compiles without storing.
 * @property revoke Revokes and restores.
 * @property catalog The gates catalogue.
 * @property policy The catalogue, signed for a repository's hooks (S2-20).
 * @property approvers Who approves what, from the catalogue.
 * @property workflows Reads and saves workflows.
 * @property publicUrl The address sessions call back (`OSTROM_PUBLIC_URL`), else the request's origin.
 */
data class CompilerRoutesDependencies(
    val actors: GateActorResolver,
    val repository: CompilerRepository,
    val publish: PublishPackageUseCase,
    val preview: PreviewPackageUseCase,
    val revoke: RevokePackageUseCase,
    val catalog: GatesCatalogUseCase,
    val policy: GatePolicyUseCase,
    val approvers: ApproversGraphUseCase,
    val workflows: WorkflowsUseCase,
    val settings: WorkflowSettingsUseCase,
    val publicUrl: String?,
)
