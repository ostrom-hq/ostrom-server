// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.infrastructure.storage

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ListedObject
import run.ostrom.server.artifacts.domain.storage.ObjectHead
import run.ostrom.server.artifacts.domain.storage.ObjectListing
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.StoredObject
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.MD5
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrl
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * [ArtifactObjectStore] over the S3 REST API with a Ktor client and the in-house [SigV4] signer, as TeamCode's
 * `S3ObjectStore`: no SDK, five operations (PutObject, GetObject with Range, HeadObject, ListObjectsV2,
 * DeleteObjects), path-style or virtual-hosted, for AWS S3, OVHcloud, Cloudflare R2 and MinIO.
 *
 * The client is the server's outbound client for operator URLs, which resolves the host each request goes to
 * (`<bucket>.<host>` when virtual-hosted), checks every address and dials one it checked: a link-local, unspecified or
 * metadata address is never reached. Error bodies are read only for their `<Code>`; nothing else of them is kept,
 * logged or returned. Redirects are not followed.
 *
 * @param http The outbound client.
 * @param clock Signs requests at its time.
 * @param maxListingBytes The most bytes a listing or a deletion answer may hold.
 */
class S3ObjectStore(
    private val http: HttpClient,
    private val clock: Clock = Clock.System,
    private val maxListingBytes: Int = MAX_LISTING_BYTES,
) : ArtifactObjectStore {
    override suspend fun put(
        connection: ArtifactStorageConnection,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        val extra = buildMap {
            if (connection.serverSideEncryption == ServerSideEncryption.AES256) put(SSE_HEADER, "AES256")
        }
        val call = Call(HttpMethod.Put, key, emptyList(), extra, bytes, ContentType.parse(contentType))
        send(connection, call) { response ->
            if (!response.status.isSuccess()) throw failure(response)
        }
    }

    override suspend fun get(
        connection: ArtifactStorageConnection,
        key: String,
        range: LongRange?,
        maxBytes: Int,
    ): StoredObject? {
        val extra = buildMap { if (range != null) put("range", "bytes=${range.first}-${range.last}") }
        return send(connection, Call(HttpMethod.Get, key, emptyList(), extra)) { response ->
            when {
                response.status == HttpStatusCode.NotFound -> missingOrFailure(response)
                response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                    readAtMost(response, MAX_ERROR_BYTES)
                    throw ObjectStoreException(response.status.value, "InvalidRange")
                }
                !response.status.isSuccess() -> throw failure(response)
                else -> {
                    val bytes = readAtMost(response, maxBytes)
                        ?: throw ObjectStoreException(response.status.value, "object_too_large")
                    // A ranged answer's length is the range's; the object's size is the total of its Content-Range.
                    val contentRange = response.headers[HttpHeaders.ContentRange]
                    val size = contentRange?.substringAfterLast('/').toLongOr(bytes.size.toLong())
                    StoredObject(bytes, size, contentRange, response.headers[HttpHeaders.ETag])
                }
            }
        }
    }

    override suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead? =
        send(connection, Call(HttpMethod.Head, key, emptyList(), emptyMap())) { response ->
            when {
                response.status == HttpStatusCode.NotFound -> null
                !response.status.isSuccess() ->
                    throw ObjectStoreException(response.status.value, "http_${response.status.value}")
                else -> ObjectHead(
                    response.headers[HttpHeaders.ContentLength].toLongOr(0),
                    response.headers[HttpHeaders.ETag],
                )
            }
        }

    override suspend fun list(
        connection: ArtifactStorageConnection,
        prefix: String,
        continuationToken: String?,
        maxKeys: Int,
    ): ObjectListing {
        val query = buildList {
            add("list-type" to "2")
            add("prefix" to prefix)
            add("max-keys" to maxKeys.coerceIn(1, ArtifactObjectStore.MAX_KEYS).toString())
            if (continuationToken != null) add("continuation-token" to continuationToken)
        }
        return send(connection, Call(HttpMethod.Get, null, query, emptyMap())) { response ->
            if (!response.status.isSuccess()) throw failure(response)
            val document = document(response)
            val keys = Xml.elements(document, "Contents").mapNotNull { item ->
                val key = Xml.text(item, "Key")?.takeIf { it.isNotEmpty() }
                key?.let { ListedObject(it, Xml.text(item, "Size").toLongOr(0)) }
            }
            val truncated = Xml.text(document, "IsTruncated") == "true"
            ObjectListing(keys, if (truncated) Xml.text(document, "NextContinuationToken") else null)
        }
    }

    override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> {
        require(keys.size <= ArtifactObjectStore.MAX_KEYS) { "DeleteObjects takes at most 1000 keys." }
        if (keys.isEmpty()) return emptyList()
        val body = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<Delete xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Quiet>true</Quiet>""")
            keys.forEach { append("<Object><Key>").append(Xml.escape(it)).append("</Key></Object>") }
            append("</Delete>")
        }.encodeToByteArray()
        val md5 = Base64.encode(md5(body))
        val query = listOf("delete" to "")
        val call = Call(HttpMethod.Post, null, query, mapOf("content-md5" to md5), body, ContentType.Application.Xml)
        return send(connection, call) { response ->
            if (!response.status.isSuccess()) throw failure(response)
            Xml.elements(document(response), "Error").mapNotNull { Xml.text(it, "Key") }
        }
    }

    /** An XML answer, bounded; one with a declaration (a DTD, an entity, a comment) is refused: S3 sends none. */
    private suspend fun document(response: HttpResponse): String {
        val bytes = readAtMost(response, maxListingBytes)
            ?: throw ObjectStoreException(response.status.value, "listing_too_large")
        val text = bytes.decodeToString()
        if ("<!" in text) throw ObjectStoreException(response.status.value, "invalid_response")
        return text
    }

    /** One request: [key] null for the bucket itself. */
    private class Call(
        val method: HttpMethod,
        val key: String?,
        val query: List<Pair<String, String>>,
        val extraHeaders: Map<String, String>,
        val body: ByteArray? = null,
        val contentType: ContentType = ContentType.Application.OctetStream,
    )

    /** A signed request, ready to send. */
    private class Signed(val url: String, val headers: Map<String, String>)

    // Any failure to reach the bucket is one local code: the text of the exception may hold a URL, never shown.
    private suspend fun <T> send(
        connection: ArtifactStorageConnection,
        call: Call,
        read: suspend (HttpResponse) -> T,
    ): T {
        val signed = sign(connection, call)
        return try {
            http.prepareRequest(signed.url) {
                method = call.method
                signed.headers.forEach { (name, value) -> header(name, value) }
                if (call.body != null) setBody(ByteArrayContent(call.body, call.contentType))
            }.execute { read(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            throw storeFailure(failed)
        }
    }

    /** The URL and the headers of [call], signed with SigV4. */
    private suspend fun sign(connection: ArtifactStorageConnection, call: Call): Signed {
        val endpoint = parseUrl(connection.endpoint)
        // The endpoint was checked when it was saved (`https://host[:port]`); this only refuses another scheme.
        if (endpoint == null || endpoint.protocol.name != "https") {
            throw ObjectStoreException(null, "invalid_endpoint")
        }
        // The host the request goes to: with virtual-hosted addressing, <bucket>.<endpoint host>.
        val authorityHost = if (connection.pathStyle) endpoint.host else "${connection.bucket}.${endpoint.host}"
        val defaultPort = endpoint.specifiedPort == 0 || endpoint.specifiedPort == endpoint.protocol.defaultPort
        val hostHeader = if (defaultPort) authorityHost else "$authorityHost:${endpoint.port}"
        val rawPath = buildString {
            if (connection.pathStyle) append('/').append(connection.bucket)
            append('/')
            if (call.key != null) append(call.key)
        }
        val canonicalUri = SigV4.encodePath(rawPath)
        val payloadHash = SigV4.sha256(call.body ?: ByteArray(0)).toHexString()
        val amzDate = SigV4.amzDate(clock.now())
        val headers = mapOf("host" to hostHeader, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate) +
            call.extraHeaders
        val signer = SigV4.Signer(connection.accessKeyId, connection.secretAccessKey, connection.region, amzDate)
        val authorization = SigV4.authorization(
            SigV4.Request(call.method.value, canonicalUri, call.query, headers, payloadHash),
            signer,
        )
        val queryString = call.query.joinToString("&") { (name, value) ->
            "${SigV4.encode(name)}=${SigV4.encode(value)}"
        }
        val url = "https://$hostHeader$canonicalUri" + if (queryString.isEmpty()) "" else "?$queryString"
        return Signed(url, headers + (HttpHeaders.Authorization to authorization))
    }

    private suspend fun failure(response: HttpResponse): ObjectStoreException {
        val bytes = readAtMost(response, MAX_ERROR_BYTES)
        val match = if (bytes == null) null else ERROR_CODE.find(bytes.decodeToString())
        val code = if (match == null) "http_${response.status.value}" else match.groupValues[1]
        return ObjectStoreException(response.status.value, code)
    }

    /** A 404 is a missing object only when S3 says `NoSuchKey` (or nothing); `NoSuchBucket` is a storage failure. */
    private suspend fun missingOrFailure(response: HttpResponse): StoredObject? {
        val failure = failure(response)
        if (failure.code == "NoSuchKey" || failure.code == "http_404") return null
        throw failure
    }

    private companion object {
        const val SSE_HEADER = "x-amz-server-side-encryption"
        const val MAX_ERROR_BYTES = 64 * 1024
        const val MAX_LISTING_BYTES = 8 * 1024 * 1024

        /** The whole body, or null as soon as it holds more than [limit] bytes. */
        suspend fun readAtMost(response: HttpResponse, limit: Int): ByteArray? {
            val bytes = response.bodyAsChannel().readRemaining(limit.toLong() + 1).readByteArray()
            return bytes.takeIf { it.size <= limit }
        }

        // DeleteObjects requires Content-MD5; it is a checksum S3 asks for, not a security measure.
        @OptIn(DelicateCryptographyApi::class)
        suspend fun md5(bytes: ByteArray): ByteArray = CryptographyProvider.Default.get(MD5).hasher().hash(bytes)
    }
}

/** What a failure to reach the bucket is reported as: its own code, never the exception's text. */
private fun storeFailure(failed: Exception): ObjectStoreException = when (failed) {
    is ObjectStoreException -> failed
    is HttpRequestTimeoutException -> ObjectStoreException(null, "timeout")
    else -> ObjectStoreException(null, "unreachable")
}

/** The code of an S3 error, when it is a plain identifier. */
private val ERROR_CODE = Regex("<Code>([A-Za-z0-9.]{1,64})</Code>")

/** A number S3 sent, or [default] when it sent none or not a number. */
internal fun String?.toLongOr(default: Long): Long {
    if (this == null) return default
    return toLongOrNull() ?: default
}

/**
 * The little of XML an S3 answer needs: the elements of one name, the text of a child, the five entities and numeric
 * references. No DTD, no entity expansion: an S3 answer is plain XML, and anything else is read as nothing.
 */
internal object Xml {
    private const val HEX = 16
    private val NUMERIC = Regex("&#(x[0-9A-Fa-f]{1,6}|[0-9]{1,7});")

    /**
     * The inner texts of every element named [name], in order.
     *
     * @return The inner texts, raw.
     */
    fun elements(document: String, name: String): List<String> =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).findAll(document).map { it.groupValues[1] }.toList()

    /**
     * The unescaped text of the first element named [name].
     *
     * @return The text, or null when there is none.
     */
    fun text(fragment: String, name: String): String? = elements(fragment, name).firstOrNull()?.let(::unescape)

    /**
     * Escapes a text for an element.
     *
     * @return The escaped text.
     */
    fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /**
     * Reads the five entities and numeric references back.
     *
     * @return The text.
     */
    fun unescape(value: String): String = NUMERIC.replace(value) { match ->
        val reference = match.groupValues[1]
        val code = if (reference.startsWith("x")) reference.drop(1).toInt(HEX) else reference.toInt()
        if (code in 1..Char.MAX_VALUE.code) code.toChar().toString() else ""
    }.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
