// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

/**
 * Which artifacts a listing narrows to, as TeamCode's gallery does (tabs, origin and access filters, and the link to
 * everything one session published).
 *
 * @property scope What the caller lists: [ArtifactScope.TEAM] everything they read, [ArtifactScope.MINE] their own,
 *   [ArtifactScope.SHARED] what was shared with them.
 * @property origin Where the artifact was created, or null for any.
 * @property access The access it has, or null for any.
 * @property cloudSession Only what the launches of this cloud session published, or null.
 * @property launch Only what this launch published, or null.
 */
data class ArtifactFilter(
    val scope: ArtifactScope = ArtifactScope.TEAM,
    val origin: ArtifactOriginKind? = null,
    val access: ArtifactAccess? = null,
    val cloudSession: String? = null,
    val launch: String? = null,
) {
    /** Reading the query parameters of the listing. */
    companion object {
        /** An absent or empty parameter filters nothing. */
        private fun String?.orNone(): String? = this?.takeIf { it.isNotEmpty() }

        /** An id a filter may name: letters, digits, dashes, dots, colons and underscores (a UUID is one). */
        private val ID = Regex("[A-Za-z0-9._:-]{1,64}")

        /**
         * Reads the listing's query parameters; an absent or empty one filters nothing.
         *
         * @throws InvalidArtifactRequestException For a value that is none of the documented ones.
         */
        fun parse(
            scope: String?,
            origin: String?,
            access: String?,
            cloudSession: String?,
            launch: String?,
        ): ArtifactFilter {
            val cloudSessionId = cloudSession.orNone()
            val launchId = launch.orNone()
            ensureValid(listOfNotNull(cloudSessionId, launchId).all { ID.matches(it) }) {
                "cloudSession and launch are ids of up to 64 characters."
            }
            val originKind = origin.orNone()
            val accessWire = access.orNone()
            return ArtifactFilter(
                scope = ArtifactScope.fromWire(scope.orNone() ?: ArtifactScope.TEAM.wire)
                    ?: throw InvalidArtifactRequestException("scope is team, mine or shared."),
                origin = originKind?.let {
                    ArtifactOriginKind.fromWire(it)
                        ?: throw InvalidArtifactRequestException("origin is local_run, cloud_session or api.")
                },
                access = accessWire?.let { parseAccess(it) },
                cloudSession = cloudSessionId,
                launch = launchId,
            )
        }
    }
}

/**
 * What a listing lists.
 *
 * @property wire The contract's value (`listArtifacts`' `scope`).
 */
enum class ArtifactScope(val wire: String) {
    /** Everything the caller reads. */
    TEAM("team"),

    /** What the caller owns. */
    MINE("mine"),

    /** What was shared with the caller. */
    SHARED("shared"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /** The scope of a wire value, or null for another value. */
        fun fromWire(wire: String): ArtifactScope? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Where an artifact was created (TeamCode's `origin_kind`), told from its [ArtifactOrigin].
 *
 * @property wire The contract's value (`listArtifacts`' `origin`).
 */
enum class ArtifactOriginKind(val wire: String) {
    /** An agent of `ostrom <agent>` on a machine. */
    LOCAL_RUN("local_run"),

    /** An agent of a cloud session. */
    CLOUD_SESSION("cloud_session"),

    /** A person, or a program without an agent token. */
    API("api"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /** The kind of a wire value, or null for another value. */
        fun fromWire(wire: String): ArtifactOriginKind? = entries.firstOrNull { it.wire == wire }
    }
}
