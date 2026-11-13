// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.storage.ArtifactAdministrationUseCaseImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactAdministratorRequiredException
import run.ostrom.server.artifacts.domain.storage.ArtifactSettings
import run.ostrom.server.artifacts.domain.storage.ArtifactSettingsConflictException
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageConflictException
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInUseException
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageMissingException
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageSecretRequiredException
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageTestRunningException
import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.InactiveReason
import run.ostrom.server.artifacts.domain.storage.InvalidArtifactSettingsException
import run.ostrom.server.artifacts.domain.storage.InvalidArtifactStorageException
import run.ostrom.server.artifacts.domain.storage.LegacyContents
import run.ostrom.server.artifacts.domain.storage.ObjectStoreException
import run.ostrom.server.artifacts.domain.storage.PublishRefusal
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.StorageCaller
import run.ostrom.server.artifacts.domain.storage.StorageTestStep
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import run.ostrom.server.artifacts.fakes.InMemoryBucket
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.TestClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** TC-ART-1/3/4/5/25: the bucket, its connection test, the settings and the publication policy. */
class ArtifactAdministrationTest {
    private val admin = StorageCaller(ORG_A, "dave", administrator = true, person = true, mayWrite = true)
    private val clock = TestClock()
    private val storage = InMemoryStorage()
    private val bucket = InMemoryBucket()
    private val moved = mutableListOf<Uuid>()
    private val legacy = LegacyContents { moved += it }
    private val administration = ArtifactAdministrationUseCaseImpl(
        storage, bucket, legacy, { it.contentHashCode().toString() }, clock, { "probe" },
    )

    private fun input(
        endpoint: String = "https://s3.example.com/",
        bucket: String = "artifacts",
        prefix: String = "",
        secret: String? = "secret-access-key",
    ) = ArtifactStorageInput(endpoint, "auto", bucket, prefix, true, "AKIAEXAMPLE", secret, ServerSideEncryption.AES256)

    private suspend fun configured(): Int = administration.saveStorage(admin, 0, input()).version

    @Test
    fun onlyASignedInOwnerOrAdministratorConfigures() = runTest {
        val member = admin.copy(administrator = false)
        val key = admin.copy(person = false)
        for (caller in listOf(member, key)) {
            assertFailsWith<ArtifactAdministratorRequiredException> { administration.overview(caller) }
            assertFailsWith<ArtifactAdministratorRequiredException> { administration.saveStorage(caller, 0, input()) }
            assertFailsWith<ArtifactAdministratorRequiredException> { administration.deleteStorage(caller) }
            assertFailsWith<ArtifactAdministratorRequiredException> { administration.testStorage(caller) }
            assertFailsWith<ArtifactAdministratorRequiredException> {
                administration.saveSettings(caller, ArtifactSettings())
            }
        }
        val overview = administration.overview(admin)
        assertNull(overview.storage)
        assertEquals(InactiveReason.STORAGE_MISSING, overview.availability.reason)
    }

    @Test
    fun theFirstSaveNeedsTheSecretAndOneSpellingOfTheEndpointIsKept() = runTest {
        assertFailsWith<InvalidArtifactStorageException> { administration.saveStorage(admin, 0, input(bucket = "B")) }
        assertFailsWith<ArtifactStorageSecretRequiredException> {
            administration.saveStorage(admin, 0, input(secret = null))
        }
        val saved = administration.saveStorage(admin, 0, input())
        assertEquals("https://s3.example.com", saved.endpoint)
        assertEquals(1, saved.version)
        assertNull(saved.verifiedAt)
        assertFailsWith<ArtifactStorageConflictException> { administration.saveStorage(admin, 0, input()) }
        // Same place: the stored secret stays.
        assertEquals(2, administration.saveStorage(admin, 1, input(secret = null, prefix = "team")).version)
        assertEquals("secret-access-key", storage.connection(ORG_A)?.secretAccessKey)
    }

    @Test
    fun movingTheBucketNeedsTheSecretAgainAndIsRefusedWhileArtifactsExist() = runTest {
        configured()
        assertFailsWith<ArtifactStorageSecretRequiredException> {
            administration.saveStorage(admin, 1, input(endpoint = "https://other.example.com", secret = null))
        }
        assertFailsWith<ArtifactStorageSecretRequiredException> {
            administration.saveStorage(admin, 1, input(bucket = "other", secret = null))
        }
        storage.inUse = true
        assertFailsWith<ArtifactStorageInUseException> {
            administration.saveStorage(admin, 1, input(bucket = "other", secret = "another-secret"))
        }
        assertFailsWith<ArtifactStorageInUseException> { administration.saveStorage(admin, 1, input(prefix = "x")) }
        // Rotating the keys in place stays possible.
        assertEquals(2, administration.saveStorage(admin, 1, input(secret = "rotated-secret")).version)
        assertEquals("rotated-secret", storage.connection(ORG_A)?.secretAccessKey)
    }

    @Test
    fun theBucketIsRemovedOnlyWhenNoArtifactUsesIt() = runTest {
        assertFailsWith<ArtifactStorageMissingException> { administration.deleteStorage(admin) }
        configured()
        storage.inUse = true
        assertFailsWith<ArtifactStorageInUseException> { administration.deleteStorage(admin) }
        storage.inUse = false
        administration.deleteStorage(admin)
        assertNull(administration.overview(admin).storage)
    }

    @Test
    fun aPassedTestVerifiesTheBucketAndLeavesNoProbe() = runTest {
        assertFailsWith<ArtifactStorageMissingException> { administration.testStorage(admin) }
        configured()
        val result = administration.testStorage(admin)
        assertTrue(result.ok)
        assertEquals(listOf("put", "get", "list", "delete"), result.steps.map { it.step })
        assertTrue(bucket.objects.isEmpty())
        assertEquals(clock.now, administration.overview(admin).storage?.verifiedAt)
        assertEquals(listOf("put", "get", "list", "delete", "head"), bucket.calls)
    }

    @Test
    fun eachFailedReadBackIsNamedByItsCode() = runTest {
        configured()
        val cases = listOf(
            InMemoryBucket().apply { forget = true } to StorageTestStep("get", false, null, "missing"),
            InMemoryBucket().apply { corrupt = true } to StorageTestStep("get", false, null, "digest_mismatch"),
            InMemoryBucket().apply { hideListing = true } to StorageTestStep("list", false, null, "not_listed"),
            InMemoryBucket().apply { refuseDelete = true } to StorageTestStep("delete", false, null, "not_deleted"),
            InMemoryBucket().apply { keepDeleted = true } to StorageTestStep("delete", false, null, "still_present"),
        )
        for ((faulty, failed) in cases) {
            val result = ArtifactAdministrationUseCaseImpl(
                storage, faulty, legacy, { it.contentHashCode().toString() }, clock,
            ).testStorage(admin)
            assertFalse(result.ok)
            assertTrue(failed in result.steps, result.toString())
            assertNull(storage.overview(ORG_A).storage?.verifiedAt)
        }
    }

    @Test
    fun aRefusedWriteStopsTheTestWithTheBucketsStatus() = runTest {
        configured()
        bucket.beforeEach = { if (it == "put") throw ObjectStoreException(403, "AccessDenied") }
        val result = administration.testStorage(admin)
        assertEquals(listOf(StorageTestStep("put", false, 403, "AccessDenied")), result.steps)
        assertEquals(listOf("put"), bucket.calls)
    }

    @Test
    fun aWriteWithoutAnAnswerIsCleanedUpQuietly() = runTest {
        configured()
        val failures = listOf<suspend (String) -> Unit>(
            { if (it == "put") throw ObjectStoreException(null, "unreachable") },
            { if (it == "put") delay(81.seconds) else throw ObjectStoreException(null, "unreachable") },
            { delay(if (it == "put") 81.seconds else 11.seconds) },
        )
        for (failure in failures) {
            bucket.calls.clear()
            bucket.beforeEach = failure
            val result = administration.testStorage(admin)
            assertFalse(result.ok)
            assertEquals("put", result.steps.single().step)
            assertEquals(listOf("put", "delete"), bucket.calls)
        }
    }

    @Test
    fun aCancelledTestIsCancelledAndFreesTheNextOne() = runTest {
        configured()
        bucket.beforeEach = { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { administration.testStorage(admin) }
        bucket.beforeEach = {
            if (it == "put") throw ObjectStoreException(null, "unreachable") else throw CancellationException("stopped")
        }
        assertFailsWith<CancellationException> { administration.testStorage(admin) }
        bucket.beforeEach = {}
        assertTrue(administration.testStorage(admin).ok)
    }

    @Test
    fun oneTestRunsAtATimeAndASaveMeanwhileMakesItsResultMoot() = runTest {
        configured()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        bucket.beforeEach = {
            if (it == "put") {
                started.complete(Unit)
                release.await()
            }
        }
        val running = async { administration.testStorage(admin) }
        started.await()
        assertFailsWith<ArtifactStorageTestRunningException> { administration.testStorage(admin) }
        administration.saveStorage(admin, 1, input(secret = null, prefix = "moved"))
        release.complete(Unit)
        val result = running.await()
        assertFalse(result.ok)
        assertEquals(StorageTestStep("record", false, null, "storage_changed"), result.steps.last())
        assertNull(administration.overview(admin).storage?.verifiedAt)
    }

    @Test
    fun artifactsTurnOnOnlyOnAVerifiedBucket() = runTest {
        val on = ArtifactSettings(enabled = true)
        assertFailsWith<InvalidArtifactSettingsException> {
            administration.saveSettings(admin, on.copy(retention = on.retention.copy(maxVersionsPerArtifact = 0)))
        }
        assertEquals(
            InactiveReason.STORAGE_MISSING,
            assertFailsWith<ArtifactsInactiveException> { administration.saveSettings(admin, on) }.reason,
        )
        val off = administration.saveSettings(admin, ArtifactSettings(whoCanPublish = WhoCanPublish.ADMINS))
        assertEquals(1, off.version)
        assertEquals(clock.now, off.updatedAt)
        configured()
        assertEquals(
            InactiveReason.STORAGE_UNVERIFIED,
            assertFailsWith<ArtifactsInactiveException> { administration.saveSettings(admin, on.copy(version = 1)) }
                .reason,
        )
        assertEquals(emptyList(), moved)
        administration.testStorage(admin)
        assertFailsWith<ArtifactSettingsConflictException> { administration.saveSettings(admin, on) }
        assertTrue(administration.saveSettings(admin, on.copy(version = 1)).enabled)
        // What was published before the bucket moves there before artifacts turn on (again after a lost race).
        assertEquals(listOf(ORG_A, ORG_A), moved)
        assertTrue(administration.overview(admin).availability.active)
        administration.deleteStorage(admin)
        assertFalse(storage.overview(ORG_A).settings.enabled)
    }

    @Test
    fun thePolicyNamesTheFirstReasonAMemberCannotPublish() = runTest {
        val member = admin.copy(administrator = false, person = false)
        assertEquals(InactiveReason.STORAGE_MISSING.wire, administration.policy(member).reason)
        configured()
        assertEquals(InactiveReason.STORAGE_UNVERIFIED.wire, administration.policy(member).reason)
        administration.testStorage(admin)
        assertEquals(InactiveReason.DISABLED.wire, administration.policy(member).reason)
        administration.saveSettings(admin, ArtifactSettings(enabled = true))
        assertTrue(administration.policy(member).canPublish)
        assertEquals(PublishRefusal.CREDENTIAL_SCOPE.wire, administration.policy(member.copy(mayWrite = false)).reason)
        val adminsOnly = ArtifactSettings(enabled = true, whoCanPublish = WhoCanPublish.ADMINS, version = 1)
        administration.saveSettings(admin, adminsOnly)
        assertEquals(PublishRefusal.FORBIDDEN.wire, administration.policy(member).reason)
        assertNotNull(administration.policy(admin.copy(mayWrite = false)).reason)
        assertNull(administration.policy(admin).reason)
    }
}
