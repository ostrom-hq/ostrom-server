// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.presentation

import run.ostrom.server.hooks.domain.EffectiveHook
import run.ostrom.server.hooks.domain.Hook
import run.ostrom.server.hooks.domain.HookDefinition
import run.ostrom.server.hooks.domain.HookDetail
import run.ostrom.server.hooks.domain.HookDownload
import run.ostrom.server.hooks.domain.HookEvent
import run.ostrom.server.hooks.domain.HookLimits
import run.ostrom.server.hooks.domain.HookPublication
import run.ostrom.server.hooks.domain.HookVersion
import run.ostrom.server.skills.domain.InvalidCatalogRequestException
import run.ostrom.server.skills.presentation.ArtifactFileContentDto
import kotlin.io.encoding.Base64

/**
 * Reads a definition: each event once, among the known ones; 60 seconds without a timeout.
 *
 * @throws InvalidCatalogRequestException `invalid_hook_definition` otherwise.
 */
fun HookDefinitionDto.toDomain(): HookDefinition {
    val parsed = events.map { wire ->
        HookEvent.fromWire(wire) ?: throw InvalidCatalogRequestException(
            "invalid_hook_definition",
            "Unknown event. Use ${HookEvent.entries.joinToString { it.wire }}.",
        )
    }
    if (parsed.toSet().size != parsed.size) {
        throw InvalidCatalogRequestException("invalid_hook_definition", "List each event once.")
    }
    return HookDefinition(parsed.toSet(), matcher, command, timeoutSeconds ?: HookLimits.DEFAULT_TIMEOUT_SECONDS)
}

/** The contract's `HookDefinition`, its events sorted. */
fun HookDefinition.toDto(): HookDefinitionDto = HookDefinitionDto(wireEvents, matcher, command, timeoutSeconds)

/** The contract's `HookEntry`. */
fun Hook.toDto(): HookEntryDto =
    HookEntryDto(id.toString(), name, pinnedVersion, allMembers, createdAt.toString(), updatedAt.toString())

/** The contract's `HookVersion`, [pinned] when it is its hook's pinned version. */
fun HookVersion.toDto(pinned: Boolean): HookVersionDto = HookVersionDto(
    version = version,
    digest = digest,
    definition = definition.toDto(),
    sizeBytes = sizeBytes,
    fileCount = fileCount,
    publishedBy = publishedBy,
    publishedAt = publishedAt.toString(),
    pinned = pinned,
)

/** The contract's `HookDetail`. */
fun HookDetail.toDto(): HookDetailDto =
    HookDetailDto(hook.toDto(), versions.map { it.toDto(it.id == hook.pinnedVersionId) })

/** The contract's `HookPublication`. */
fun HookPublication.toDto(): HookPublicationDto =
    HookPublicationDto(hook.toDto(), version.toDto(version.id == hook.pinnedVersionId), created)

/** The contract's `EffectiveHook`. */
fun EffectiveHook.toDto(): EffectiveHookDto = EffectiveHookDto(
    name = name,
    version = version.version,
    digest = version.digest,
    definition = version.definition.toDto(),
    sizeBytes = version.sizeBytes,
    fileCount = version.fileCount,
)

/** The contract's `HookArtifact`. */
fun HookDownload.toDto(): HookArtifactDto = HookArtifactDto(
    name = name,
    version = version.version,
    digest = version.digest,
    definition = version.definition.toDto(),
    files = entries.zip(files).map { (entry, file) ->
        val content = Base64.Default.encode(file.content)
        ArtifactFileContentDto(entry.path, entry.mode.wire, entry.size, entry.sha256, content)
    },
)
