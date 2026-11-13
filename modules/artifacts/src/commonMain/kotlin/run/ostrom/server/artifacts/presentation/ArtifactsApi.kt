// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/artifacts` (what the caller reads, `?before=<id>&limit=`) and `POST` (publish), as in ostrom-core's
 * `api/openapi.yaml` (operations `listArtifacts`, `publishArtifact`).
 *
 * @property before The cursor of the previous page.
 * @property limit The page size, 1 to 100.
 */
@Serializable
@Resource("/api/v1/artifacts")
class ArtifactsApi(val before: String? = null, val limit: Int? = null) {
    /**
     * `GET`, `PATCH` and `DELETE /api/v1/artifacts/{id}`.
     *
     * @property parent The collection.
     * @property id The artifact id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: ArtifactsApi = ArtifactsApi(), val id: String) {
        /**
         * `GET` and `POST /api/v1/artifacts/{id}/versions`.
         *
         * @property parent The artifact.
         */
        @Serializable
        @Resource("versions")
        class Versions(val parent: Id) {
            /**
             * `GET /api/v1/artifacts/{id}/versions/{number}/file?path=`: a file, as an attachment.
             *
             * @property parent The versions.
             * @property number The version.
             * @property path The file; the entry page when absent.
             */
            @Serializable
            @Resource("{number}/file")
            class File(val parent: Versions, val number: String, val path: String? = null)
        }

        /**
         * `GET /api/v1/artifacts/{id}/members`.
         *
         * @property parent The artifact.
         */
        @Serializable
        @Resource("members")
        class Members(val parent: Id) {
            /**
             * `PUT` and `DELETE /api/v1/artifacts/{id}/members/{login}`.
             *
             * @property parent The members.
             * @property login The member's id.
             */
            @Serializable
            @Resource("{login}")
            class Member(val parent: Members, val login: String)
        }

        /**
         * `POST /api/v1/artifacts/{id}/tickets`.
         *
         * @property parent The artifact.
         */
        @Serializable
        @Resource("tickets")
        class Tickets(val parent: Id)
    }
}

/** `POST /api/v1/artifacts/mcp`: the artifacts MCP server (streamable HTTP, JSON answers). */
@Serializable
@Resource("/api/v1/artifacts/mcp")
class ArtifactsMcpApi

/**
 * A file sent for publication.
 *
 * @property path Its path in the version (`index.html`, `img/a.png`).
 * @property content Its content.
 * @property encoding `utf8` (default) or `base64`.
 */
@Serializable
data class FileDto(val path: String, val content: String, val encoding: String? = null)

/**
 * Publishes an artifact.
 *
 * @property title Its title.
 * @property access `personal` (default), `team` or `link`.
 * @property files Its files.
 */
@Serializable
data class PublishArtifactRequest(val title: String, val access: String = "personal", val files: List<FileDto>)

/**
 * Publishes a version.
 *
 * @property files Its files.
 */
@Serializable
data class PublishVersionRequest(val files: List<FileDto>)

/**
 * Changes an artifact's settings.
 *
 * @property title A new title.
 * @property access A new access level.
 */
@Serializable
data class UpdateArtifactRequest(val title: String? = null, val access: String? = null)

/**
 * Mints a ticket.
 *
 * @property version The version; the latest when absent.
 */
@Serializable
data class TicketRequest(val version: Int? = null)

/**
 * An artifact.
 *
 * @property id Its id.
 * @property title Its title.
 * @property owner The member it belongs to.
 * @property access `personal`, `team` or `link`.
 * @property relation What the caller is to it: `owner`, `viewer` or `team`.
 * @property latestVersion Its newest version.
 * @property url The console's page for it.
 * @property publicUrl Its public link, for `link` access on a server that serves pages.
 * @property createdAt ISO 8601.
 * @property updatedAt ISO 8601.
 */
@Serializable
data class ArtifactResponse(
    val id: String,
    val title: String,
    val owner: String,
    val access: String,
    val relation: String? = null,
    val latestVersion: Int,
    val url: String,
    val publicUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * A page of artifacts.
 *
 * @property items The artifacts.
 * @property next The cursor of the next page.
 */
@Serializable
data class ArtifactsResponse(val items: List<ArtifactResponse>, val next: String? = null)

/**
 * A version.
 *
 * @property number Its number.
 * @property files Its paths.
 * @property entry The page a reader opens first.
 * @property createdAt ISO 8601.
 * @property createdBy The member who published it.
 */
@Serializable
data class ArtifactVersionResponse(
    val number: Int,
    val files: List<String>,
    val entry: String,
    val createdAt: String,
    val createdBy: String,
)

/**
 * An artifact's versions.
 *
 * @property items Newest first.
 * @property next Always absent: every version is listed.
 */
@Serializable
data class ArtifactVersionsResponse(val items: List<ArtifactVersionResponse>, val next: String? = null)

/**
 * The members an artifact is shared with.
 *
 * @property members Their ids.
 */
@Serializable
data class ArtifactMembersResponse(val members: List<String>)

/**
 * A ticket.
 *
 * @property artifactId The artifact.
 * @property version The version.
 * @property url The entry page's address on the content origin.
 * @property contentUrl The same address (TeamCode's name).
 * @property expiresAt ISO 8601.
 */
@Serializable
data class ArtifactTicketResponse(
    val artifactId: String,
    val version: Int,
    val url: String,
    val contentUrl: String,
    val expiresAt: String,
)
