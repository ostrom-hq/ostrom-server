// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets.presentation

import run.ostrom.server.secrets.domain.EnvironmentAssignment
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentVariant
import run.ostrom.server.secrets.domain.EnvironmentVariantUpdate
import run.ostrom.server.secrets.domain.InvalidEnvironmentVariantException
import run.ostrom.server.secrets.domain.NewEnvironmentVariant
import run.ostrom.server.secrets.domain.SecretValue

/** Maps a create request to the domain. */
internal fun EnvironmentVariantCreateRequest.toNew() = NewEnvironmentVariant(
    variable = variable,
    name = name,
    binding = binding?.let {
        EnvironmentBinding.fromWire(it) ?: throw InvalidEnvironmentVariantException("Use shared or perMember.")
    } ?: EnvironmentBinding.SHARED,
    value = value?.let(::SecretValue),
)

/** Maps an update request to the domain. */
internal fun EnvironmentVariantUpdateRequest.toUpdate() =
    EnvironmentVariantUpdate(expectedVersion, name, value?.let(::SecretValue))

/** Maps a variant to the API, never with its value. */
fun EnvironmentVariant.toResponse() = EnvironmentVariantResponse(
    id = id.toString(),
    variable = variable,
    name = name,
    binding = binding.wire,
    owner = ownerMemberId?.toString(),
    hasValue = hasValue,
    version = version,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/** Maps an assignment to the API. */
fun EnvironmentAssignment.toResponse() = EnvironmentAssignmentResponse(
    member = memberId.toString(),
    variable = variable,
    variantId = variantId.toString(),
    variantName = variantName,
    version = version,
    source = source.wire,
    personal = personal,
    hasMemberValue = hasMemberValue,
    assignedAt = assignedAt.toString(),
)
