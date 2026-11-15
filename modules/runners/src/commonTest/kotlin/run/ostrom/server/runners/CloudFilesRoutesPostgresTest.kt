// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.artifacts.domain.storage.ArtifactObjectStore
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageRepository
import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.domain.CloudConversationStore
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudFile
import run.ostrom.server.runners.domain.CloudFileBegin
import run.ostrom.server.runners.domain.CloudFileKeys
import run.ostrom.server.runners.domain.CloudFileRules
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.toRef
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.FakeArtifactStorage
import run.ostrom.server.runners.fakes.FakeBucket
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.CloudConversationMaintenance
import run.ostrom.server.runners.infrastructure.CloudFileStoreImpl
import run.ostrom.server.runners.presentation.CloudFileDto
import run.ostrom.server.runners.presentation.CloudFilePolicyDto
import run.ostrom.server.runners.presentation.CloudInputDto
import run.ostrom.server.runners.presentation.CloudInputPageDto
import run.ostrom.server.runners.presentation.RunnerInputsResponse
import run.ostrom.server.runners.presentation.SendCloudMessageBody
import run.ostrom.server.runners.presentation.toDto
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.module.Module
import org.koin.dsl.module
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Files sent to sessions over HTTP on real PostgreSQL (TeamCode's `CloudFilePersistenceTest` and the files of its
 * `CloudConversationRoutesTest`): the upload and its refusals, the quotas, a message that carries files, the runner's
 * download, the release and the sweep, and another organization's 404.
 */
class CloudFilesRoutesPostgresTest {
    private val bucket = FakeBucket()
    private val storage = FakeArtifactStorage()
    private val audit = mutableListOf<OrgAuditRecord>()
    private var runtime = CloudRuntime(fileMaxMebibytes = 2, messageFileMebibytes = 3)

    private fun overrides(withBucket: Boolean = true): Module = module {
        includes(testIdentities)
        if (withBucket) {
            single<ArtifactObjectStore> { bucket }
            single<ArtifactStorageRepository> { storage }
        }
        single<CloudRuntimeSource> {
            object : CloudRuntimeSource {
                override suspend fun current(): CloudRuntime = runtime
            }
        }
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
    }

    private class Running(val session: CloudSession, val token: String) {
        val path = "/api/v1/cloud/sessions/${session.id}"
    }

    private suspend fun TestServer.running(): Running {
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val now = Clock.System.now()
        val created = session(state = SessionState.RUNNING, ownerId = ALICE.toString(), createdAt = now)
            .copy(mode = SessionMode.CONVERSATION, epoch = 2, runnerTokenHash = tokens.hash(token))
            .copy(executionStartedAt = now)
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(created, 100))
        return Running(created, token)
    }

    @Suppress("LongParameterList") // The parts of the request a test changes.
    private suspend fun TestServer.upload(
        path: String,
        bytes: ByteArray,
        name: String? = "notes.txt",
        caller: String = "alice",
        key: String? = null,
        streamed: Boolean = false,
    ): HttpResponse = client.post("$path/files" + name?.let { "?name=${it.encodeURLParameter()}" }.orEmpty()) {
        bearerAuth(caller)
        key?.let { header("Idempotency-Key", it) }
        if (streamed) {
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.OctetStream

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(bytes)
                    }
                },
            )
        } else {
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
    }

    private suspend fun TestServer.send(path: String, body: SendCloudMessageBody, key: String? = null) =
        client.post("$path/messages") {
            bearerAuth("alice")
            key?.let { header("Idempotency-Key", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.runner(path: String, token: String, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/$path") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.download(
        running: Running,
        id: String,
        epoch: Long = 2,
        token: String? = running.token,
    ): HttpResponse = client.get("/api/v1/cloud/runner/files/$id?sessionId=${running.session.id}&epoch=$epoch") {
        token?.let { bearerAuth(it) }
    }

    private suspend fun TestServer.policy(caller: String = "alice"): CloudFilePolicyDto =
        client.get("/api/v1/cloud/files/policy") { bearerAuth(caller) }.body()

    private suspend fun TestServer.messageOf(running: Running, text: String, vararg files: String): CloudInputDto =
        send(running.path, SendCloudMessageBody(text, fileIds = files.toList())).body()

    private fun completedFile(id: String, sessionId: Uuid, author: String) =
        "INSERT INTO runners_session_files (id, org_id, session_id, author_id, name, size, created_at, " +
            "completed_at, expires_at) VALUES ('$id', '$ORG_A', '$sessionId', '$author', 'd', 1, now(), now(), " +
            "now() + interval '1 day')"

    private suspend fun HttpResponse.type(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type")
            .jsonPrimitive.content

    private suspend fun TestServer.scalar(sql: String): String? {
        val rows = database.superuser.fetchAll("SELECT ($sql)::text AS v").getOrThrow()
        return rows.map { it.get("v").asStringOrNull() }.single()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private fun png(size: Int) = ByteArray(size).also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
    }

    @Test
    fun thePolicySaysWhetherTheBucketIsReady() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val policy = server.policy("reader")
        assertTrue(policy.available)
        assertNull(policy.reason)
        assertEquals(2 * CloudFileRules.MEBIBYTE, policy.maxFileBytes)
        assertEquals(3 * CloudFileRules.MEBIBYTE, policy.maxMessageBytes)
        assertEquals(5, policy.maxFilesPerMessage)
        assertTrue("image/png" in policy.types)
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.get("/api/v1/cloud/files/policy").status,
        )

        // A bucket that failed its test, or changed since it passed, is not ready.
        storage.tested = false
        val untested = server.policy()
        assertEquals(false to "file_storage_not_configured", untested.available to untested.reason)
        val running = server.running()
        val refused = server.upload(running.path, "hi".encodeToByteArray())
        assertEquals(HttpStatusCode.Conflict to "file_storage_not_configured", refused.status to refused.type())
        storage.tested = true
        storage.opens = false
        assertFalse(server.policy().available, "Its secret no longer opens")
        storage.opens = true
        storage.connectionVersion = 2
        assertFalse(server.policy().available)
        storage.stored = false
        assertFalse(server.policy().available)
    }

    @Test
    fun withoutTheArtifactsModuleNoFileIsServed() = serverTest(listOf(RunnersModule), overrides(false)) { server ->
        val policy = server.client.get("/api/v1/cloud/files/policy") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.NotFound, policy.status)
        val running = server.running()
        val message = server.send(running.path, SendCloudMessageBody("x", fileIds = listOf(Uuid.random().toString())))
        assertEquals("files_unsupported", message.type())
    }

    @Test
    fun anUploadIsCleanedTypedStoredAndAudited() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val text = "# Notes\nÉté ✓\n".encodeToByteArray()
        val sent = server.upload(running.path, text, name = "../../etc/notes.md", key = "u1")
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
        assertNull(sent.headers["Idempotency-Replayed"])
        val file = sent.body<CloudFileDto>()
        assertEquals("notes.md" to "text/markdown", file.name to file.contentType)
        assertEquals(text.size.toLong() to sha(text), file.size to file.sha256)
        assertEquals(running.session.id.toString(), file.sessionId)
        val key = CloudFileKeys.part(FakeArtifactStorage.PREFIX, ORG_A, running.session.id, Uuid.parse(file.id), 0)
        assertEquals("ostrom/session-files/$ORG_A/${running.session.id}/${file.id}/0", key)
        assertContentEquals(text, bucket.objects.getValue(key))
        val record = audit.single { it.event == "cloud.file.uploaded" }
        assertEquals(file.id, record.subjectId)
        assertFalse("Été" in record.details.toString(), "Never the content")

        // The same file under the same key is the first upload; other bytes are a conflict.
        val replay = server.upload(running.path, text, name = "../../etc/notes.md", key = "u1")
        assertEquals(HttpStatusCode.OK to "true", replay.status to replay.headers["Idempotency-Replayed"])
        assertEquals(file.id, replay.body<CloudFileDto>().id)
        val conflict = server.upload(running.path, "other".encodeToByteArray(), name = "notes.md", key = "u1")
        assertEquals(HttpStatusCode.UnprocessableEntity to "idempotency_conflict", conflict.status to conflict.type())
        val large = ByteArray(3 * 1024 * 1024) { 'a'.code.toByte() }
        val tooLargeReplay = server.upload(running.path, large, key = "u1", streamed = true)
        assertEquals("file_too_large", tooLargeReplay.type())
        assertEquals(1, bucket.objects.size, "A replay stores nothing")
        // A key whose upload still runs.
        server.database.superuser.execute(
            "INSERT INTO runners_session_files (id, org_id, session_id, author_id, name, size, client_key, " +
                "created_at, expires_at) VALUES (gen_random_uuid(), '$ORG_A', '${running.session.id}', '$ALICE', " +
                "'f', 0, 'u2', now(), now() + interval '1 hour')",
        ).getOrThrow()
        val running2 = server.upload(running.path, text, key = "u2")
        assertEquals(HttpStatusCode.Conflict to "file_upload_in_progress", running2.status to running2.type())

        // The type comes from the bytes: an image keeps its extension only when it is its own, text never an image's.
        val image = server.upload(running.path, png(1_500_000), name = "photo.exe").body<CloudFileDto>()
        assertEquals("photo.exe.png" to "image/png", image.name to image.contentType)
        assertEquals(2, bucket.objects.keys.count { image.id in it }, "Two parts of 1 MiB at most")
        val fake = server.upload(running.path, "plain".encodeToByteArray(), name = "fake.png").body<CloudFileDto>()
        assertEquals("fake.png.txt" to "text/plain", fake.name to fake.contentType)
        val unnamed = server.upload(running.path, "{}".encodeToByteArray(), name = null).body<CloudFileDto>()
        assertEquals("file", unnamed.name)
    }

    @Test
    fun anUploadIsRefusedForItsBytesItsNameOrItsCaller() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val path = running.path
        val exe = server.upload(path, byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00))
        assertEquals(HttpStatusCode.UnsupportedMediaType to "file_type_not_allowed", exe.status to exe.type())
        // Text that turns binary after its first part, and text cut inside a character.
        val turned = ByteArray(CloudFileRules.PART_BYTES + 10) { 'a'.code.toByte() }.also { it[it.size - 1] = 0 }
        assertEquals("file_type_not_allowed", server.upload(path, turned).type())
        assertEquals("file_type_not_allowed", server.upload(path, byteArrayOf(0x61, 0xE2.toByte())).type())
        val empty = server.upload(path, ByteArray(0))
        assertEquals(HttpStatusCode.BadRequest to "empty_file", empty.status to empty.type())
        val large = ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val announced = server.upload(path, large)
        assertEquals(HttpStatusCode.PayloadTooLarge to "file_too_large", announced.status to announced.type())
        assertEquals("file_too_large", server.upload(path, large, streamed = true).type())
        val twice = server.client.post("$path/files?name=a&name=b") { bearerAuth("alice") }
        assertEquals("invalid_file_name", twice.type())
        assertEquals("invalid_file_name", server.upload(path, "x".encodeToByteArray(), name = "a".repeat(1_025)).type())
        assertEquals("invalid_idempotency_key", server.upload(path, "x".encodeToByteArray(), key = "with space").type())
        // The failed uploads left their parts for the sweep, marked.
        assertEquals(
            "0",
            server.scalar("SELECT count(*) FROM runners_session_files WHERE deleting_since IS NULL"),
            "Every failed upload is marked for the sweep",
        )

        // Who may send: the owner only while its agent uses the owner's GitHub access; a member who does not own it and
        // another organization: 404.
        assertEquals(HttpStatusCode.Forbidden, server.upload(path, "x".encodeToByteArray(), caller = "dana").status)
        assertEquals(HttpStatusCode.NotFound, server.upload(path, "x".encodeToByteArray(), caller = "bob").status)
        assertEquals(HttpStatusCode.NotFound, server.upload(path, "x".encodeToByteArray(), caller = "carol").status)
        assertEquals(HttpStatusCode.Forbidden, server.upload(path, "x".encodeToByteArray(), caller = "reader").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.post("$path/files") { setBody("x") }.status)
        val nowhere = server.upload("/api/v1/cloud/sessions/nope", "x".encodeToByteArray())
        assertEquals(HttpStatusCode.NotFound, nowhere.status)

        // The bucket fails: 503, and the upload is marked for the sweep.
        bucket.failing += "put"
        val unavailable = server.upload(path, "x".encodeToByteArray())
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("file_storage_unavailable", unavailable.type())
        bucket.failing.clear()

        // An ended session takes no file.
        val id = running.session.id
        server.database.superuser.execute("UPDATE runners_sessions SET desired_state = 'terminated' WHERE id = '$id'")
            .getOrThrow()
        assertEquals(HttpStatusCode.Conflict, server.upload(path, "x".encodeToByteArray()).status)
    }

    @Test
    fun theQuotasOfTheSessionAndTheDayAnswer429() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val id = running.session.id
        fun rows(count: Int, size: Long) =
            "INSERT INTO runners_session_files (id, org_id, session_id, author_id, name, size, created_at, " +
                "completed_at, expires_at) SELECT gen_random_uuid(), '$ORG_A', '$id', '$ALICE', 'f', $size, " +
                "now(), now(), now() + interval '1 day' FROM generate_series(1, $count)"
        server.database.superuser.execute(rows(20, 1)).getOrThrow()
        val unsent = server.upload(running.path, "x".encodeToByteArray())
        assertEquals(HttpStatusCode.TooManyRequests to "upload_quota", unsent.status to unsent.type())
        assertNull(unsent.headers[HttpHeaders.RetryAfter], "A session's bound clears when its files are sent")
        server.database.superuser.execute("DELETE FROM runners_session_files").getOrThrow()
        server.database.superuser.execute(rows(1, CloudFileRules.SESSION_BUFFER_BYTES)).getOrThrow()
        assertEquals("upload_quota", server.upload(running.path, "x".encodeToByteArray()).type())
        server.database.superuser.execute("DELETE FROM runners_session_files").getOrThrow()

        val day = Clock.System.now().toEpochMilliseconds() / CloudFileRules.DAY_MILLIS
        server.database.superuser.execute(
            "INSERT INTO runners_file_usage (org_id, member_id, day, bytes, files) " +
                "VALUES ('$ORG_A', '$ALICE', $day, 0, 500)",
        ).getOrThrow()
        val daily = server.upload(running.path, "x".encodeToByteArray())
        assertEquals("upload_quota", daily.type())
        val retry = assertNotNull(daily.headers[HttpHeaders.RetryAfter]).toLong()
        assertTrue(retry in 1..86_400, "Until the UTC day ends: $retry")
        // Bytes count too, and an upload is counted in its author's day.
        server.database.superuser.execute(
            "UPDATE runners_file_usage SET files = 0, bytes = ${CloudFileRules.MEMBER_DAILY_BYTES - 3}",
        ).getOrThrow()
        assertEquals(HttpStatusCode.Created, server.upload(running.path, "ab".encodeToByteArray()).status)
        assertEquals("1", server.scalar("SELECT files FROM runners_file_usage"))
        assertEquals("upload_quota", server.upload(running.path, "ab".encodeToByteArray()).type())
    }

    @Test
    fun aMessageNamesItsAuthorsUnsentFiles() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val path = running.path
        val first = server.upload(path, "Hello file".encodeToByteArray(), name = "a.txt").body<CloudFileDto>()
        val second = server.upload(path, png(1_200_000), name = "b.png").body<CloudFileDto>()
        // Another author's file in the same session.
        val danas = Uuid.random().toString()
        server.database.superuser.execute(completedFile(danas, running.session.id, "dana")).getOrThrow()

        // What a message may name: at most five distinct files, the author's, unsent, small enough together.
        val six = server.send(path, SendCloudMessageBody("x", fileIds = List(6) { Uuid.random().toString() }))
        assertEquals(HttpStatusCode.BadRequest to "invalid_files", six.status to six.type())
        val twice = SendCloudMessageBody("x", fileIds = listOf(first.id, first.id))
        assertEquals("invalid_files", server.send(path, twice).type())
        for (bad in listOf("nope", Uuid.random().toString(), danas)) {
            val refused = server.send(path, SendCloudMessageBody("x", fileIds = listOf(bad)))
            assertEquals(HttpStatusCode.Conflict to "file_unavailable", refused.status to refused.type(), bad)
        }
        runtime = runtime.copy(messageFileMebibytes = 1)
        val heavy = server.send(path, SendCloudMessageBody("x", fileIds = listOf(first.id, second.id)))
        assertEquals(HttpStatusCode.PayloadTooLarge to "files_too_large", heavy.status to heavy.type())
        runtime = runtime.copy(messageFileMebibytes = 3)

        // Files alone make a message; a retry with the same files is the same message, other files a conflict.
        val body = SendCloudMessageBody("", fileIds = listOf(second.id, first.id))
        val sent = server.send(path, body, key = "m1")
        assertEquals(HttpStatusCode.Accepted, sent.status, sent.bodyAsText())
        val input = sent.body<CloudInputDto>()
        assertEquals(listOf(first.id, second.id), input.fileIds)
        val replay = server.send(path, body, key = "m1")
        assertEquals("true" to input.id, replay.headers["Idempotency-Replayed"] to replay.body<CloudInputDto>().id)
        assertEquals(listOf(first.id, second.id), replay.body<CloudInputDto>().fileIds)
        val other = server.send(path, body.copy(fileIds = listOf(first.id)), key = "m1")
        assertEquals("idempotency_conflict", other.type())
        val again = server.send(path, SendCloudMessageBody("again", fileIds = listOf(first.id)))
        assertEquals("file_unavailable", again.type(), "Already sent")
        val blank = server.send(path, SendCloudMessageBody(" ", fileIds = listOf(first.id)))
        assertEquals("invalid_message", blank.type(), "Files allow no text, never a blank one")
        val listed = server.client.get("$path/messages") { bearerAuth("alice") }.body<CloudInputPageDto>()
        assertEquals(listOf(first.id, second.id), listed.inputs.single().fileIds)
        val one = server.client.get("$path/messages/${input.id}") { bearerAuth("alice") }.body<CloudInputDto>()
        assertEquals(listOf(first.id, second.id), one.fileIds)
    }

    @Test
    fun theRunnerDownloadsWhatItsMessageCarries() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val path = running.path
        val text = "Hello file".encodeToByteArray()
        val first = server.upload(path, text, name = "a.txt").body<CloudFileDto>()
        val image = png(1_200_000)
        val second = server.upload(path, image, name = "b.png").body<CloudFileDto>()
        val danas = Uuid.random().toString()
        server.database.superuser.execute(completedFile(danas, running.session.id, "dana")).getOrThrow()
        val input = server.messageOf(running, "Look", first.id, second.id)

        // Not downloadable before its message is delivered to this execution.
        assertEquals(HttpStatusCode.NotFound, server.download(running, first.id).status)
        val claim = """"sessionId":"${running.session.id}","epoch":2"""
        val polled = server.runner("inputs", running.token, """{$claim,"acceptsFiles":true}""")
            .body<RunnerInputsResponse>()
        val files = polled.inputs.single().files
        assertEquals(listOf(first.id, second.id), files.map { it.id })
        assertEquals(listOf("a.txt" to "text/plain", "b.png" to "image/png"), files.map { it.name to it.contentType })
        assertEquals(sha(image) to image.size.toLong(), files[1].sha256 to files[1].size)

        val got = server.download(running, second.id)
        assertEquals(HttpStatusCode.OK, got.status)
        assertEquals(sha(image), got.headers["X-Ostrom-Sha256"])
        assertEquals("attachment", got.headers[HttpHeaders.ContentDisposition])
        assertEquals(ContentType.Application.OctetStream, got.contentType()?.withoutParameters())
        assertContentEquals(image, got.bodyAsBytes())
        assertContentEquals(text, server.download(running, first.id).bodyAsBytes())

        // The credential first, then the execution's fence, then the file.
        assertEquals(HttpStatusCode.Unauthorized, server.download(running, first.id, token = null).status)
        val stale = server.download(running, first.id, epoch = 1)
        assertEquals(HttpStatusCode.Conflict to "execution_fenced", stale.status to stale.type())
        assertEquals(HttpStatusCode.Conflict, server.download(running, first.id, token = "ostrom_runner_wrong").status)
        assertEquals(HttpStatusCode.NotFound, server.download(running, "nope").status)
        assertEquals(HttpStatusCode.NotFound, server.download(running, danas).status)
        bucket.failing += "get"
        val failing = server.download(running, first.id)
        assertEquals(HttpStatusCode.ServiceUnavailable to "file_storage_unavailable", failing.status to failing.type())
        bucket.failing.clear()

        // Consumed: the transcript names the files, which are released for the sweep.
        val ack = """{$claim,"acks":[{"id":"${input.id}","status":"consumed"}]}"""
        assertEquals(HttpStatusCode.NoContent, server.runner("inputs/ack", running.token, ack).status)
        val said = server.koin.get<CloudConversationStore>()
            .events(ORG_A, running.session.id, null, null, 10, null).events
        val user = said.single { it.type == CloudEventType.USER_MESSAGE }
        assertTrue("\"name\":\"b.png\"" in user.data && "\"name\":\"a.txt\"" in user.data, user.data)
        assertEquals(HttpStatusCode.NotFound, server.download(running, first.id).status, "Acknowledged: gone")
        assertEquals("2", server.scalar("SELECT count(*) FROM runners_session_files WHERE consumed_at IS NOT NULL"))

        // The sweep removes the bytes, then the rows; Dana's unsent file stays.
        server.koin.get<CloudConversationMaintenance>().run()
        assertTrue(bucket.objects.isEmpty())
        assertEquals(danas, server.scalar("SELECT id FROM runners_session_files"))
    }

    @Test
    fun aRunnerWithoutFilesRejectsTheMessageAndItsFilesAreReleased() =
        serverTest(listOf(RunnersModule), overrides()) { server ->
            val running = server.running()
            val file = server.upload(running.path, "x".encodeToByteArray()).body<CloudFileDto>()
            val input = server.messageOf(running, "see", file.id)
            val claim = """"sessionId":"${running.session.id}","epoch":2"""
            val polled = server.runner("inputs", running.token, """{$claim}""").body<RunnerInputsResponse>()
            assertTrue(polled.inputs.isEmpty(), "Rejected, never handed")
            val stored = server.client.get("${running.path}/messages/${input.id}") { bearerAuth("alice") }
                .body<CloudInputDto>()
            assertEquals("rejected", stored.status)
            assertEquals(
                "1",
                server.scalar(
                    "SELECT count(*) FROM runners_session_files " +
                        "WHERE deleting_since IS NOT NULL AND consumed_at IS NULL",
                ),
            )
        }

    @Test
    fun theSweepExpiresRetriesAndForgets() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val sweep = suspend { server.koin.get<CloudConversationMaintenance>().run() }
        val kept = server.upload(running.path, "keep".encodeToByteArray()).body<CloudFileDto>()
        val old = server.upload(running.path, "old".encodeToByteArray()).body<CloudFileDto>()
        server.database.superuser.execute(
            "UPDATE runners_session_files SET expires_at = now() - interval '1 minute' WHERE id = '${old.id}'",
        ).getOrThrow()
        // An upload that never completed: its parts are listed, then deleted.
        val draft = Uuid.random()
        server.database.superuser.execute(
            "INSERT INTO runners_session_files (id, org_id, session_id, author_id, name, size, created_at, " +
                "expires_at) " +
                "VALUES ('$draft', '$ORG_A', '${running.session.id}', '$ALICE', 'f', 0, now() - interval '2 hours', " +
                "now() - interval '1 hour')",
        ).getOrThrow()
        val draftKey = CloudFileKeys.part(FakeArtifactStorage.PREFIX, ORG_A, running.session.id, draft, 0)
        bucket.objects[draftKey] = "x".encodeToByteArray()
        // A past day's counter goes.
        server.database.superuser.execute(
            "INSERT INTO runners_file_usage (org_id, member_id, day, bytes, files) VALUES ('$ORG_A', 'old', 1, 1, 1)",
        ).getOrThrow()

        // The bucket fails: nothing is forgotten, the next pass retries.
        bucket.failing += "list"
        bucket.failing += "delete"
        sweep()
        assertEquals("3", server.scalar("SELECT count(*) FROM runners_session_files"))
        bucket.failing.clear()
        val oldKey = CloudFileKeys.part(FakeArtifactStorage.PREFIX, ORG_A, running.session.id, Uuid.parse(old.id), 0)
        bucket.stuck += oldKey
        sweep()
        assertEquals(setOf(kept.id, old.id), server.ids())
        assertFalse(draftKey in bucket.objects)
        assertEquals("0", server.scalar("SELECT count(*) FROM runners_file_usage WHERE member_id = 'old'"))
        bucket.stuck.clear()
        sweep()
        assertEquals(setOf(kept.id), server.ids())

        // No bucket left: there is nothing to remove, the rows go.
        server.database.superuser.execute("UPDATE runners_session_files SET deleting_since = now()").getOrThrow()
        storage.stored = false
        sweep()
        assertEquals(emptySet(), server.ids())
    }

    private suspend fun TestServer.ids(): Set<String> =
        database.superuser.fetchAll("SELECT id FROM runners_session_files").getOrThrow()
            .map { it.get("id").asString() }.toSet()

    @Test
    fun aDownloadAnswersForTheBytesItFinds() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val file = server.upload(running.path, "abc".encodeToByteArray()).body<CloudFileDto>()
        val input = server.messageOf(running, "x", file.id)
        val claim = """"sessionId":"${running.session.id}","epoch":2"""
        server.runner("inputs", running.token, """{$claim,"acceptsFiles":true}""")
        bucket.objects.clear()
        val gone = server.download(running, file.id)
        assertEquals(HttpStatusCode.Gone to "file_expired", gone.status to gone.type())
        storage.stored = false
        assertEquals(HttpStatusCode.NotFound, server.download(running, file.id).status)
        assertNotNull(input.id)
    }

    @Test
    fun theStoreRefusesWhatChangedUnderTheUpload() = serverTest(listOf(RunnersModule), overrides()) { server ->
        val running = server.running()
        val store = CloudFileStoreImpl(server.koin.get(), server.koin.get(), server.koin.get())
        val now = Clock.System.now()
        fun draft(sessionId: Uuid = running.session.id, key: String? = null) = CloudFile(
            Uuid.random(), ORG_A, sessionId, ALICE.toString(), null, "f", null, 1, null, key, null, null, now, null,
            now + 1.hours,
        )
        suspend fun code(block: suspend () -> Unit) = assertFailsWith<OstromException> { block() }.type
        assertEquals("not_found", code { store.begin(draft(Uuid.random()), ALICE.toString(), 1) })
        assertEquals("session_busy", code { store.begin(draft(), "bob", 1) }, "The owner changed since it was read")
        assertEquals("file_expired", code { store.complete(ORG_A, Uuid.random(), "f", "text/plain", 1, "h", null) })

        // A failed upload frees its key for the retry; a completed or abandoned one cannot be completed.
        val failed = draft(key = "k")
        assertEquals(CloudFileBegin.Started, store.begin(failed, ALICE.toString(), 1))
        store.abandon(ORG_A, failed.id)
        assertEquals("file_expired", code { store.complete(ORG_A, failed.id, "f", "text/plain", 1, "h", null) })
        val retry = draft(key = "k")
        assertEquals(CloudFileBegin.Started, store.begin(retry, ALICE.toString(), 1))
        store.complete(ORG_A, retry.id, "f", "text/plain", 1, "h", null)
        assertEquals("file_expired", code { store.complete(ORG_A, retry.id, "f", "text/plain", 1, "h", null) })
        assertIs<CloudFileBegin.Existing>(store.begin(draft(key = "k"), ALICE.toString(), 1))
        // The session ended meanwhile, then was deleted.
        val id = running.session.id
        val sql = server.database.superuser
        sql.execute("UPDATE runners_sessions SET state = 'terminated' WHERE id = '$id'").getOrThrow()
        assertEquals("session_not_running", code { store.begin(draft(), ALICE.toString(), 1) })
        sql.execute("UPDATE runners_sessions SET state = 'running', desired_state = 'terminated' WHERE id = '$id'")
            .getOrThrow()
        assertEquals("session_not_running", code { store.begin(draft(), ALICE.toString(), 1) })
        sql.execute("UPDATE runners_sessions SET desired_state = 'running' WHERE id = '$id'").getOrThrow()

        val orphan = draft()
        store.begin(orphan, ALICE.toString(), 1)
        server.database.superuser.execute("DELETE FROM runners_sessions WHERE id = '${running.session.id}'")
            .getOrThrow()
        assertEquals("file_expired", code { store.complete(ORG_A, orphan.id, "f", "text/plain", 1, "h", null) })
        store.forget(ORG_A, emptyList())
    }

    @Test
    fun aFileIsShownWhateverItsUploadKnowsYet() {
        val now = Clock.System.now()
        val draft = CloudFile(
            Uuid.random(), ORG_A, Uuid.random(), "alice", null, "f", null, 0, null, null, null, null, now, null, now,
        )
        assertEquals("" to "", draft.toDto().let { it.contentType to it.sha256 })
        assertEquals("" to "", draft.toRef().let { it.contentType to it.sha256 })
        val done = draft.copy(contentType = "text/plain", sha256 = "h")
        assertEquals("text/plain" to "h", done.toDto().let { it.contentType to it.sha256 })
        assertEquals("text/plain" to "h", done.toRef().toDto().let { it.contentType to it.sha256 })
        val root = CloudFileKeys.root("", ORG_A, draft.sessionId, draft.id)
        assertEquals("session-files/$ORG_A/${draft.sessionId}/${draft.id}/", root, "A bucket without a prefix")
    }
}
