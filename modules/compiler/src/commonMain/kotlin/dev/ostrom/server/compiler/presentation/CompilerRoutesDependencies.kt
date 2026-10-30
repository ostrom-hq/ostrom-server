// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.presentation

import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.usecases.GatePolicyUseCase
import dev.ostrom.server.compiler.domain.usecases.GatesCatalogUseCase
import dev.ostrom.server.compiler.domain.usecases.PreviewPackageUseCase
import dev.ostrom.server.compiler.domain.usecases.PublishPackageUseCase
import dev.ostrom.server.compiler.domain.usecases.RevokePackageUseCase
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import dev.ostrom.server.gates.domain.services.GateActorResolver

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
    val workflows: WorkflowsUseCase,
    val publicUrl: String?,
)
