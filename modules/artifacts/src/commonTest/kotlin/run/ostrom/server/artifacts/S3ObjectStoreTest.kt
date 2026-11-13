// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.storage.ArtifactKeys
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConnection
import run.ostrom.server.artifacts.domain.storage.ListedObject
import run.ostrom.server.artifacts.domain.storage.ObjectHead
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.infrastructure.storage.S3ObjectStore
import run.ostrom.server.artifacts.infrastructure.storage.SigV4
import run.ostrom.server.artifacts.infrastructure.storage.Xml
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.MD5
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The S3 adapter against a mock engine: the exact requests it signs and sends, and what it keeps of an answer. */
class S3ObjectStoreTest {
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2013-05-24T00:00:00Z")
    }

    private fun connection(
        pathStyle: Boolean = false,
        sse: ServerSideEncryption = ServerSideEncryption.NONE,
        endpoint: String = "https://s3.example.test",
    ) = ArtifactStorageConnection(endpoint, "gra", "artifacts", "os", pathStyle, "AKIDEXAMPLE", SECRET, sse)

    private fun store(maxListingBytes: Int = 8 * 1024 * 1024, handler: MockRequestHandler) =
        S3ObjectStore(HttpClient(MockEngine(handler)) { followRedirects = false }, clock, maxListingBytes)

    private fun bytes(request: HttpRequestData) =
        (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)

    /** Recomputes the signature from what was actually sent, so a header changed after signing would fail. */
    private suspend fun assertSigned(request: HttpRequestData, expectedHost: String) {
        val authorization = request.headers[HttpHeaders.Authorization]!!
        val signedNames = Regex("SignedHeaders=([^,]+)").find(authorization)!!.groupValues[1].split(';')
        assertEquals(expectedHost, request.headers[HttpHeaders.Host])
        val headers = signedNames.associateWith { request.headers[it]!! }
        val payload = SigV4.sha256(bytes(request)).toHexString()
        assertEquals(payload, request.headers["x-amz-content-sha256"])
        val query = request.url.parameters.entries().flatMap { (name, values) -> values.map { name to it } }
        val signer = SigV4.Signer("AKIDEXAMPLE", SECRET, "gra", "20130524T000000Z")
        val sent = SigV4.Request(request.method.value, request.url.encodedPath, query, headers, payload)
        val expected = SigV4.signature(sent, signer)
        assertTrue(authorization.endsWith("Signature=$expected"), authorization)
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20130524/gra/s3/aws4_request, "))
    }

    @Test
    fun putIsSignedVirtualHostedWithEncryptionAndPathStyleWhenAsked() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val s3 = store { request ->
            seen += request
            respond("", HttpStatusCode.OK)
        }
        val key = "os/org/artifacts/a/blobs/abc"
        s3.put(connection(sse = ServerSideEncryption.AES256), key, "hello".encodeToByteArray(), "text/html")
        val put = seen.single()
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("https://artifacts.s3.example.test/os/org/artifacts/a/blobs/abc", put.url.toString())
        assertEquals("AES256", put.headers["x-amz-server-side-encryption"])
        val authorization = put.headers[HttpHeaders.Authorization]!!
        assertTrue("x-amz-server-side-encryption" in authorization, "The encryption is signed")
        assertContentEquals("hello".encodeToByteArray(), bytes(put))
        assertEquals("text/html", put.body.contentType.toString())
        assertSigned(put, "artifacts.s3.example.test")

        val minio = connection(pathStyle = true, endpoint = "https://minio.local:9000")
        s3.put(minio, "os/x y+z", ByteArray(0), "text/plain")
        val pathStyle = seen.last()
        assertEquals("/artifacts/os/x%20y%2Bz", pathStyle.url.encodedPath)
        assertNull(pathStyle.headers["x-amz-server-side-encryption"])
        assertSigned(pathStyle, "minio.local:9000")

        s3.put(connection(endpoint = "https://s3.example.test:443"), "k", ByteArray(1), "text/plain")
        assertSigned(seen.last(), "artifacts.s3.example.test")
    }

    @Test
    fun getSendsTheRangeAndMissingObjectsAreNull() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val s3 = store { request ->
            seen += request
            when (request.url.encodedPath) {
                "/k" -> respond("2345", HttpStatusCode.PartialContent, range("bytes 2-5/10"))
                "/odd" -> respond("2345", HttpStatusCode.PartialContent, range("bytes 2-5/*"))
                "/whole" -> respond("whole", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"e\""))
                "/gone-bucket" -> respond("<Error><Code>NoSuchBucket</Code></Error>", HttpStatusCode.NotFound)
                "/bare" -> respond("", HttpStatusCode.NotFound)
                "/range" -> respond("<Error><Code>InvalidRange</Code></Error>", HttpStatusCode.fromValue(416))
                "/denied" -> respond("<Error><Code>AccessDenied</Code></Error>", HttpStatusCode.Forbidden)
                else -> respond("<Error><Code>NoSuchKey</Code></Error>", HttpStatusCode.NotFound)
            }
        }
        val partial = assertNotNull(s3.get(connection(), "k", 2L..5L))
        assertEquals("bytes=2-5", seen.last().headers["range"])
        assertSigned(seen.last(), "artifacts.s3.example.test")
        assertEquals("2345", partial.bytes.decodeToString())
        assertEquals("bytes 2-5/10", partial.contentRange)
        assertEquals(10, partial.size, "The object's size, not the range's")
        val odd = assertNotNull(s3.get(connection(), "odd", 2L..5L))
        assertEquals(4, odd.size, "An unknown total is what was read")
        val whole = assertNotNull(s3.get(connection(), "whole"))
        assertNull(seen.last().headers["range"])
        assertEquals(5, whole.size)
        assertEquals("\"e\"", whole.etag)
        assertNull(whole.contentRange)
        val bucket = assertFailsWith<ObjectStoreException> { s3.get(connection(), "gone-bucket") }
        assertEquals("NoSuchBucket", bucket.code, "A missing bucket is a failure, not a missing object")
        assertNull(s3.get(connection(), "missing"))
        assertNull(s3.get(connection(), "bare"), "A 404 without a code is a missing object")
        val range = assertFailsWith<ObjectStoreException> { s3.get(connection(), "range", 0L..1L) }
        assertEquals("InvalidRange", range.code)
        val denied = assertFailsWith<ObjectStoreException> { s3.get(connection(), "denied") }
        assertEquals(403, denied.status)
        assertEquals("AccessDenied", denied.code)
        val tooLarge = assertFailsWith<ObjectStoreException> { s3.get(connection(), "whole", maxBytes = 2) }
        assertEquals("object_too_large", tooLarge.code)
    }

    @Test
    fun headReadsTheSizeAndTagAndAMissingObjectIsNull() = runTest {
        val s3 = store { request ->
            when (request.url.encodedPath) {
                "/k" -> respond("", HttpStatusCode.OK, headersOf(LENGTH to listOf("42"), ETAG to listOf("t")))
                "/odd" -> respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "many"))
                "/denied" -> respond("", HttpStatusCode.Forbidden)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        assertEquals(ObjectHead(42, "t"), s3.head(connection(), "k"))
        assertEquals(0, s3.head(connection(), "odd")?.size)
        assertNull(s3.head(connection(), "missing"))
        assertEquals("http_403", assertFailsWith<ObjectStoreException> { s3.head(connection(), "denied") }.code)
    }

    @Test
    fun errorsKeepOnlyTheS3CodeNeverTheBody() = runTest {
        val s3 = store {
            respond(
                "<Error><Code>AccessDenied</Code><Message>key AKIDEXAMPLE has no right on hidden-bucket</Message>" +
                    "</Error>",
                HttpStatusCode.Forbidden,
            )
        }
        val error = assertFailsWith<ObjectStoreException> { s3.put(connection(), "k", ByteArray(1), "text/plain") }
        assertEquals(403, error.status)
        assertEquals("AccessDenied", error.code)
        assertFalse("hidden-bucket" in error.message.orEmpty() || "AKIDEXAMPLE" in error.message.orEmpty())
        val odd = assertFailsWith<ObjectStoreException> {
            store { respond("<Error><Code>Not a <code></Code></Error>", HttpStatusCode.BadGateway) }
                .put(connection(), "k", ByteArray(1), "text/plain")
        }
        assertEquals("http_502", odd.code, "A code that is not a plain identifier is not echoed")
        val huge = assertFailsWith<ObjectStoreException> {
            val body = "<Error><Code>Big</Code></Error>" + "x".repeat(70_000)
            store { respond(body, HttpStatusCode.InternalServerError) }
                .put(connection(), "k", ByteArray(1), "text/plain")
        }
        assertEquals("http_500", huge.code, "An error body past 64 KiB is not read")
    }

    @Test
    fun anEndpointThatIsNoHttpsUrlOrABucketThatCannotBeReachedIsALocalCode() = runTest {
        var sent = 0
        val s3 = store {
            sent++
            respond("", HttpStatusCode.OK)
        }
        val endpoints = listOf(
            "http://s3.example.test",
            "not a url at all",
            "https://",
            "https://s3.example.test:port",
            "https://:443",
        )
        for (endpoint in endpoints) {
            val refused = assertFailsWith<ObjectStoreException> { s3.head(connection(endpoint = endpoint), "k") }
            assertEquals("invalid_endpoint", refused.code, endpoint)
            assertNull(refused.status)
        }
        assertEquals(0, sent)
        val timeout = store { throw HttpRequestTimeoutException("https://s3.example.test/k", 1_000) }
        assertEquals("timeout", assertFailsWith<ObjectStoreException> { timeout.head(connection(), "k") }.code)
        val down = store { throw IllegalStateException("connection refused to https://s3.example.test") }
        assertEquals("unreachable", assertFailsWith<ObjectStoreException> { down.head(connection(), "k") }.code)
        val cancelled = store { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { cancelled.head(connection(), "k") }
    }

    @OptIn(DelicateCryptographyApi::class)
    @Test
    fun listPaginatesAndDeleteReportsTheKeysItCouldNotRemove() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val s3 = store { request ->
            seen += request
            if (request.method == HttpMethod.Post) {
                respond(
                    """<?xml version="1.0"?><DeleteResult><Error><Key>b&amp;&lt;c&gt;</Key>""" +
                        "<Code>AccessDenied</Code>" +
                        "</Error><Error><Code>NoKey</Code></Error></DeleteResult>",
                )
            } else if (request.url.parameters["continuation-token"] == null) {
                respond("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>")
            } else {
                respond(
                    """<?xml version="1.0" encoding="UTF-8"?><ListBucketResult><IsTruncated>true</IsTruncated>""" +
                        "<Contents><Key>os/a</Key><Size>3</Size></Contents><Contents><Key>os/&#233;&#x41;&#0;</Key>" +
                        "</Contents><Contents><Key></Key></Contents><Contents><Size>1</Size></Contents>" +
                        "<NextContinuationToken>next+/=</NextContinuationToken></ListBucketResult>",
                )
            }
        }
        val listing = s3.list(connection(), "os/", continuationToken = "tok/1", maxKeys = 2_000)
        assertEquals(listOf(ListedObject("os/a", 3), ListedObject("os/éA", 0)), listing.keys)
        assertEquals("next+/=", listing.nextContinuationToken)
        val list = seen.single()
        assertEquals("2", list.url.parameters["list-type"])
        assertEquals("os/", list.url.parameters["prefix"])
        assertEquals("1000", list.url.parameters["max-keys"])
        assertEquals("tok/1", list.url.parameters["continuation-token"])
        assertSigned(list, "artifacts.s3.example.test")
        val last = s3.list(connection(), "os/")
        assertEquals(emptyList(), last.keys)
        assertNull(last.nextContinuationToken)

        assertEquals(listOf("b&<c>"), s3.delete(connection(), listOf("a", "b&<c>")))
        val delete = seen.last()
        assertEquals(HttpMethod.Post, delete.method)
        assertTrue(delete.url.parameters.contains("delete"))
        assertTrue("<Key>b&amp;&lt;c&gt;</Key>" in bytes(delete).decodeToString(), "Keys are escaped in the XML body")
        val md5 = CryptographyProvider.Default.get(MD5).hasher().hash(bytes(delete))
        assertEquals(Base64.encode(md5), delete.headers["content-md5"])
        assertSigned(delete, "artifacts.s3.example.test")
        assertEquals(emptyList(), s3.delete(connection(), emptyList()))
        assertFailsWith<IllegalArgumentException> { s3.delete(connection(), List(1_001) { "k$it" }) }
    }

    @Test
    fun aListingThatIsRefusedTooLargeOrDeclaresADtdIsAFailure() = runTest {
        val refused = store { respond("<Error><Code>AccessDenied</Code></Error>", HttpStatusCode.Forbidden) }
        val list = assertFailsWith<ObjectStoreException> { refused.list(connection(), "") }
        assertEquals("AccessDenied", list.code)
        val delete = assertFailsWith<ObjectStoreException> { refused.delete(connection(), listOf("k")) }
        assertEquals("AccessDenied", delete.code)
        val large = store(maxListingBytes = 10) { respond("<ListBucketResult></ListBucketResult>") }
        val tooLarge = assertFailsWith<ObjectStoreException> { large.list(connection(), "") }
        assertEquals("listing_too_large", tooLarge.code)
        val entity = store {
            respond(
                """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>""" +
                    "<ListBucketResult><Contents><Key>&e;</Key></Contents></ListBucketResult>",
            )
        }
        val declared = assertFailsWith<ObjectStoreException> { entity.list(connection(), "") }
        assertEquals("invalid_response", declared.code)
    }

    @Test
    fun theLayoutIsOneFolderPerArtifactAndAConnectionNeverPrintsItsSecret() {
        assertEquals("org/", ArtifactKeys.orgRoot("", "org"))
        assertEquals("p/q/org/artifacts/a/blobs/abc", ArtifactKeys.blob("p/q", "org", "a", "abc"))
        assertEquals("p/org/artifacts/a/versions/3.json", ArtifactKeys.manifest("p", "org", "a", 3))
        assertFalse(SECRET in connection().toString())
        assertEquals(ServerSideEncryption.AES256, ServerSideEncryption.parse("AES256"))
        assertNull(ServerSideEncryption.parse("aws:kms"))
        assertEquals("a &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;", Xml.escape("a & <b> \"c\" 'd'"))
        assertEquals("a & <b> \"c\" 'd'", Xml.unescape(Xml.escape("a & <b> \"c\" 'd'")))
        assertEquals("", Xml.unescape("&#x110000;"), "A reference beyond one character is dropped")
    }

    private fun range(value: String) = headersOf(HttpHeaders.ContentRange, value)

    private companion object {
        const val SECRET = "secret-access-key-example"
        const val LENGTH = HttpHeaders.ContentLength
        const val ETAG = HttpHeaders.ETag
    }
}
