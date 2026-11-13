// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.storage

/**
 * The five operations artifacts need from an S3-compatible bucket (TeamCode's `ArtifactObjectStore`). Keys are full
 * object keys, prefix included ([ArtifactKeys]). A missing object is null, never an exception; any other answer of
 * the bucket is an [ObjectStoreException] that carries its status and its S3 error code, never its body.
 */
interface ArtifactObjectStore {
    /**
     * Writes an object, with `x-amz-server-side-encryption` when the connection asks for it.
     *
     * @param connection The bucket.
     * @param key The object key.
     * @param bytes The content.
     * @param contentType Its media type.
     *
     * @throws ObjectStoreException When the bucket refuses it or cannot be reached.
     */
    suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String)

    /**
     * Reads an object, or one inclusive byte range of it.
     *
     * @param connection The bucket.
     * @param key The object key.
     * @param range The bytes to read, or null for all of them.
     * @param maxBytes At most this many bytes are read.
     *
     * @return The object, or null when the bucket holds none under [key].
     *
     * @throws ObjectStoreException When the bucket refuses it, cannot be reached, the range cannot be satisfied
     *   (`416`, `InvalidRange`) or the object is larger than [maxBytes] (`object_too_large`).
     */
    suspend fun get(
        connection: ArtifactStorageConnection,
        key: String,
        range: LongRange? = null,
        maxBytes: Int = MAX_OBJECT_READ,
    ): StoredObject?

    /**
     * Reads an object's size and tag.
     *
     * @return Its head, or null when it does not exist.
     *
     * @throws ObjectStoreException When the bucket refuses it or cannot be reached.
     */
    suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead?

    /**
     * Lists the objects under a prefix (ListObjectsV2), one page.
     *
     * @param prefix The key prefix.
     * @param continuationToken The previous page's token, or null for the first.
     * @param maxKeys At most this many keys, 1 to 1000.
     *
     * @return The page.
     *
     * @throws ObjectStoreException When the bucket refuses it or cannot be reached.
     */
    suspend fun list(
        connection: ArtifactStorageConnection,
        prefix: String,
        continuationToken: String? = null,
        maxKeys: Int = MAX_KEYS,
    ): ObjectListing

    /**
     * Deletes up to 1000 objects (DeleteObjects); a missing key counts as deleted.
     *
     * @return The keys the bucket could not delete.
     *
     * @throws ObjectStoreException When the bucket refuses the whole request or cannot be reached.
     */
    suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String>

    /** The bounds of a call. */
    companion object {
        /** The most bytes one read takes: a file is at most 16 MiB of text. */
        const val MAX_OBJECT_READ: Int = 17 * 1024 * 1024

        /** The most keys a listing or a deletion carries. */
        const val MAX_KEYS: Int = 1000
    }
}

/** `x-amz-server-side-encryption` on every object written (R2 ignores it). */
enum class ServerSideEncryption(val wire: String) {
    /** No header. */
    NONE("none"),

    /** `AES256`. */
    AES256("AES256"),
    ;

    /** Reads the wire names. */
    companion object {
        /**
         * Reads a wire name.
         *
         * @return The value, or null when unknown.
         */
        fun parse(value: String): ServerSideEncryption? = entries.singleOrNull { it.wire == value }
    }
}

/**
 * An opened connection to the organization's bucket, its secret decrypted: for the object store only, never logged
 * nor serialized ([toString] hides the secret).
 *
 * @property endpoint `https://host[:port]`.
 * @property region The signing region (`us-east-1`, `auto`, `gra`).
 * @property bucket The bucket.
 * @property prefix The key prefix, empty for the whole bucket.
 * @property pathStyle True for `https://host/<bucket>/key`, false for `https://<bucket>.host/key`.
 * @property accessKeyId The key id.
 * @property secretAccessKey The secret.
 * @property serverSideEncryption What to ask on writes.
 * @property version The storage version it was read from, so a connection test records the version it used.
 */
data class ArtifactStorageConnection(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val prefix: String,
    val pathStyle: Boolean,
    val accessKeyId: String,
    val secretAccessKey: String,
    val serverSideEncryption: ServerSideEncryption,
    val version: Int = 0,
) {
    override fun toString(): String =
        "ArtifactStorageConnection(endpoint=$endpoint, bucket=$bucket, secretAccessKey=[REDACTED])"
}

/**
 * The bucket answered an error, or could not be reached. Only its status and code are kept, never a body.
 *
 * @property status The HTTP status, or null when nothing answered.
 * @property code The S3 error code (`AccessDenied`, `NoSuchBucket`...) or a short local one (`unreachable`, `timeout`,
 *   `invalid_endpoint`, `invalid_response`, `object_too_large`, `listing_too_large`, `http_<status>`).
 */
class ObjectStoreException(val status: Int?, val code: String) :
    Exception("The object store answered ${status ?: "nothing"} ($code).")

/**
 * An object read.
 *
 * @property bytes The bytes read (the range's, for a ranged read).
 * @property size The whole object's size.
 * @property contentRange The `Content-Range` of a ranged answer.
 * @property etag Its tag.
 */
class StoredObject(val bytes: ByteArray, val size: Long, val contentRange: String?, val etag: String?)

/**
 * An object's head.
 *
 * @property size Its size.
 * @property etag Its tag.
 */
data class ObjectHead(val size: Long, val etag: String?)

/**
 * One page of a listing.
 *
 * @property keys The objects.
 * @property nextContinuationToken The token of the next page, or null at the end.
 */
data class ObjectListing(val keys: List<ListedObject>, val nextContinuationToken: String?)

/**
 * A listed object.
 *
 * @property key Its key.
 * @property size Its size.
 */
data class ListedObject(val key: String, val size: Long)

/**
 * The bucket layout, TeamCode's: one folder per artifact, content-addressed inside it, so a republication sends only
 * changed files, a version costs a manifest and deleting an artifact is deleting one prefix.
 *
 * ```text
 * <prefix>/<org>/artifacts/<artifact>/blobs/<sha256>        content, immutable
 * <prefix>/<org>/artifacts/<artifact>/versions/<n>.json     copy of the manifest
 * ```
 */
object ArtifactKeys {
    /**
     * The organization's folder.
     *
     * @return `<prefix>/<org>/`, or `<org>/` without a prefix.
     */
    fun orgRoot(prefix: String, orgId: String): String = (if (prefix.isEmpty()) "" else "$prefix/") + "$orgId/"

    /**
     * An artifact's folder.
     *
     * @return `<prefix>/<org>/artifacts/<artifact>/`.
     */
    fun artifactRoot(prefix: String, orgId: String, artifactId: String): String =
        orgRoot(prefix, orgId) + "artifacts/$artifactId/"

    /**
     * A file's content.
     *
     * @return `<artifact folder>blobs/<sha256>`.
     */
    fun blob(prefix: String, orgId: String, artifactId: String, sha256: String): String =
        artifactRoot(prefix, orgId, artifactId) + "blobs/$sha256"

    /**
     * A version's manifest copy.
     *
     * @return `<artifact folder>versions/<n>.json`.
     */
    fun manifest(prefix: String, orgId: String, artifactId: String, version: Int): String =
        artifactRoot(prefix, orgId, artifactId) + "versions/$version.json"
}
