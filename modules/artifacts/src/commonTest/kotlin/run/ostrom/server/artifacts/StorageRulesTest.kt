// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.storage.ArtifactQuotas
import run.ostrom.server.artifacts.domain.storage.ArtifactRetention
import run.ostrom.server.artifacts.domain.storage.ArtifactSettings
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageHostNotAllowedException
import run.ostrom.server.artifacts.domain.storage.ArtifactStorageInput
import run.ostrom.server.artifacts.domain.storage.InvalidArtifactSettingsException
import run.ostrom.server.artifacts.domain.storage.InvalidArtifactStorageException
import run.ostrom.server.artifacts.domain.storage.ServerSideEncryption
import run.ostrom.server.artifacts.domain.storage.StorageRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TC-ART-4: the bucket's fields and the settings' ranges, TeamCode's. */
class StorageRulesTest {
    private val fine = ArtifactStorageInput(
        "https://s3.gra.io.cloud.ovh.net", "gra", "ostrom-artifacts", "", false, "AKIAEXAMPLE", "secret-access-key",
        ServerSideEncryption.NONE,
    )

    private fun input(endpoint: String = fine.endpoint, secret: String? = fine.secretAccessKey) =
        fine.copy(endpoint = endpoint, secretAccessKey = secret)

    @Test
    fun aWellFormedBucketPasses() {
        StorageRules.validate(input())
        StorageRules.validate(
            fine.copy(secretAccessKey = null, prefix = "teams/ostrom", endpoint = "https://minio.local:9000/"),
        )
    }

    @Test
    fun eachMalformedFieldIsRefused() {
        val malformed = listOf(
            input(endpoint = "http://s3.example.com"),
            fine.copy(region = "Europe West"),
            fine.copy(bucket = "Bucket"),
            fine.copy(bucket = "a..b"),
            fine.copy(prefix = "../up"),
            fine.copy(accessKeyId = "AK IA"),
            input(secret = "short"),
            input(secret = "a secret with spaces"),
            input(secret = "x".repeat(513)),
        )
        malformed.forEach {
            assertFailsWith<InvalidArtifactStorageException>(it.toString()) { StorageRules.validate(it) }
        }
        assertFailsWith<ArtifactStorageHostNotAllowedException> {
            StorageRules.validate(input(endpoint = "https://169.254.169.254"))
        }
    }

    @Test
    fun theSecretNeverReachesALog() {
        val printed = input(secret = "hunter2-secret").toString()
        assertFalse("hunter2" in printed)
        assertTrue("[REDACTED]" in printed)
    }

    @Test
    fun anEndpointIsHttpsAndAnAuthorityOnly() {
        assertTrue(StorageRules.validEndpoint("https://s3.example.com"))
        assertTrue(StorageRules.validEndpoint("https://s3.example.com:8443/"))
        listOf(
            "http://s3.example.com",
            "https://" + "a".repeat(512),
            "https://s3.example.com\n",
            "https://",
            "https:///",
            "https://s3.example.com/bucket",
            "https://s3.example.com?x",
            "https://s3.example.com#x",
            "https://user@s3.example.com",
            "https://s3.example.com\\x",
        ).forEach { assertFalse(StorageRules.validEndpoint(it), it) }
    }

    @Test
    fun metadataLinkLocalAndUnspecifiedHostsAreRefusedLoopbackAndPrivateAreNot() {
        val allowed = listOf(
            "https://s3.example.com",
            "https://s3.1",
            "https://127.0.0.1:9000",
            "https://10.0.0.5",
            "https://169.1.2.3",
            "https://[::1]:9000",
            "https://[fd00::1]",
            "https://[fe00::1]",
        )
        allowed.forEach { assertTrue(StorageRules.allowedHost(it), it) }
        val refused = listOf(
            "https://:443",
            "https://metadata.google.internal.",
            "https://METADATA",
            "https://instance-data",
            "https://169.254.169.254",
            "https://0.0.0.0",
            "https://0.1.2.3",
            "https://2852039166",
            "https://0xa9fea9fe",
            "https://0251.0376.0251.0376",
            "https://[::]",
            "https://[fe80::1]",
            "https://[fd00:ec2::254]",
            "https://[::ffff:169.254.169.254]",
            "https://[zz::1]",
        )
        refused.forEach { assertFalse(StorageRules.allowedHost(it), it) }
    }

    @Test
    fun aPrefixIsEmptyOrUpToEightPlainSegments() {
        assertTrue(StorageRules.validPrefix(""))
        assertTrue(StorageRules.validPrefix("a/b.c/d_e-f"))
        assertFalse(StorageRules.validPrefix("a".repeat(201)))
        assertFalse(StorageRules.validPrefix(List(9) { "s" }.joinToString("/")))
        assertFalse(StorageRules.validPrefix("a//b"))
        assertFalse(StorageRules.validPrefix("A"))
        assertFalse(StorageRules.validPrefix("a/./b"))
        assertFalse(StorageRules.validPrefix("a/.."))
    }

    @Test
    fun settingsStayWithinTeamCodesRanges() {
        val valid = ArtifactSettings(retention = ArtifactRetention(personalDays = 30, sharedDays = 3650))
        StorageRules.validate(valid)
        StorageRules.validate(ArtifactSettings())
        val quotas = ArtifactQuotas()
        val tooMany = 101L * 1024 * ArtifactQuotas.GIB
        val outOfRange = listOf(
            valid.copy(quotas = quotas.copy(memberBytes = 1)),
            valid.copy(quotas = quotas.copy(memberBytes = tooMany)),
            valid.copy(quotas = quotas.copy(orgBytes = 1)),
            valid.copy(quotas = quotas.copy(orgBytes = tooMany)),
            valid.copy(quotas = quotas.copy(artifactsPerMember = 0)),
            valid.copy(quotas = quotas.copy(artifactsPerMember = 100_001)),
            valid.copy(quotas = quotas.copy(publishesPerHour = 0)),
            valid.copy(quotas = quotas.copy(publishesPerHour = 10_001)),
            valid.copy(retention = ArtifactRetention(personalDays = 0)),
            valid.copy(retention = ArtifactRetention(personalDays = 3651)),
            valid.copy(retention = ArtifactRetention(sharedDays = 0)),
            valid.copy(retention = ArtifactRetention(sharedDays = 3651)),
            valid.copy(retention = ArtifactRetention(maxVersionsPerArtifact = 0)),
            valid.copy(retention = ArtifactRetention(maxVersionsPerArtifact = 1001)),
        )
        outOfRange.forEach {
            assertFailsWith<InvalidArtifactSettingsException>(it.toString()) { StorageRules.validate(it) }
        }
        assertEquals(2L * ArtifactQuotas.GIB, ArtifactQuotas().memberBytes)
    }
}
