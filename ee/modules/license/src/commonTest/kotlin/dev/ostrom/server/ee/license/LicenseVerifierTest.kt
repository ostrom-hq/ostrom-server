// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license

import dev.ostrom.server.ee.license.domain.ExpiredLicenseException
import dev.ostrom.server.ee.license.domain.InvalidLicenseSignatureException
import dev.ostrom.server.ee.license.domain.License
import dev.ostrom.server.ee.license.domain.MalformedLicenseException
import dev.ostrom.server.ee.license.domain.NotYetValidLicenseException
import dev.ostrom.server.ee.license.domain.UnknownLicenseKeyException
import dev.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import dev.ostrom.server.ee.license.infrastructure.PinnedLicenseKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class LicenseVerifierTest {
    private val fixtureVerifier = LicenseVerifierImpl(listOf(SIGN_PY_TEST_PUBLIC_KEY))

    private suspend fun malformed(verifier: LicenseVerifierImpl, key: String): String =
        assertFailsWith<MalformedLicenseException> { verifier.verify(key, NOW) }.message.orEmpty()
            .removePrefix("The license key is malformed: ")

    @Test
    fun aLicenseSignedBySignPyVerifies() = runTest {
        val license = fixtureVerifier.verify(SIGN_PY_FIXTURE, NOW)
        assertEquals(
            License(
                licenseId = "lic_fixture_0001",
                customer = "Acme Test Corp",
                issuedAt = Instant.parse("2026-10-30T00:00:00Z"),
                expiresAt = Instant.parse("2027-10-30T00:00:00Z"),
                maxPeople = 50,
                features = setOf("policies", "retention", "sso"),
                instanceId = null,
                keyId = "59f7291142778b67",
            ),
            license,
        )
        assertEquals(Instant.parse("2027-11-13T00:00:00Z"), license.graceEndsAt, "14 days of grace")
    }

    @Test
    fun theProductionKeyIsPinnedAndRejectsTheTestFixture() = runTest {
        assertEquals(listOf("MCowBQYDK2VwAyEACIFioTGJviBXEX6sfYdbVtCcStqJoa7Fj7VXj/mp3VY="), PinnedLicenseKeys.ALL)
        // The default verifier trusts the production key only: the test key's licenses are unknown to it.
        val error = assertFailsWith<UnknownLicenseKeyException> { LicenseVerifierImpl().verify(SIGN_PY_FIXTURE, NOW) }
        assertEquals(
            "The license key was signed by an unknown key (59f7291142778b67); this build does not trust it.",
            error.message,
        )
        // The production key's id is 5cceefe10dca26f3: a forgery claiming it reaches the signature check, and fails.
        val forged = TestSigningKey.generate().license("keyId" to q("5cceefe10dca26f3"))
        assertFailsWith<InvalidLicenseSignatureException> { LicenseVerifierImpl().verify(forged, NOW) }
    }

    @Test
    fun aGeneratedKeyVerifiesWithEveryField() = runTest {
        val key = TestSigningKey.generate()
        val signed = key.license("maxPeople" to null, "features" to "[\"sso\",\"sso\"]", "instanceId" to q("abc"))
        val license = LicenseVerifierImpl(listOf(SIGN_PY_TEST_PUBLIC_KEY, key.publicKey)).verify(" $signed\n", NOW)
        assertNull(license.maxPeople, "absent is unlimited")
        assertEquals(setOf("sso"), license.features)
        assertEquals("abc", license.instanceId)
        assertEquals(key.keyId, license.keyId)
        assertEquals(setOf("sso"), license.toGrant().features)
    }

    @Test
    fun theFeaturesDefaultToNone() = runTest {
        val key = TestSigningKey.generate()
        val license = LicenseVerifierImpl(listOf(key.publicKey))
            .verify(key.license("maxPeople" to "null", "features" to null), NOW)
        assertEquals(emptySet(), license.features)
        assertNull(license.maxPeople)
    }

    @Test
    fun aKeyWithoutThePrefixIsMalformed() = runTest {
        val v2 = SIGN_PY_FIXTURE.replace("ostrom-license.v1.", "ostrom-license.v2.")
        assertEquals("it does not start with ostrom-license.v1.", malformed(fixtureVerifier, v2))
        assertEquals("it does not start with ostrom-license.v1.", malformed(fixtureVerifier, ""))
    }

    @Test
    fun aKeyTooLongIsMalformedBeforeAnyDecoding() = runTest {
        val long = "ostrom-license.v1." + "a".repeat(8_200)
        assertEquals("it is longer than 8192 characters.", malformed(LicenseVerifierImpl(), long))
    }

    @Test
    fun aKeyWithoutExactlyTwoPartsIsMalformed() = runTest {
        val expected = "it must be ostrom-license.v1.<payload>.<signature>."
        assertEquals(expected, malformed(fixtureVerifier, "ostrom-license.v1.abc"))
        assertEquals(expected, malformed(fixtureVerifier, "$SIGN_PY_FIXTURE.extra"))
    }

    @Test
    fun badBase64IsMalformed() = runTest {
        val (payload, signature) = SIGN_PY_FIXTURE.removePrefix("ostrom-license.v1.").split('.')
        assertEquals(
            "its payload is not base64url without padding.",
            malformed(fixtureVerifier, "ostrom-license.v1.$payload*.$signature"),
        )
        assertEquals(
            "its signature is not base64url without padding.",
            malformed(fixtureVerifier, "ostrom-license.v1.$payload.$signature=="),
        )
    }

    @Test
    fun aPayloadThatIsNotAV1PayloadIsMalformed() = runTest {
        val key = TestSigningKey.generate()
        val verifier = LicenseVerifierImpl(listOf(key.publicKey))
        val expected = "its payload is not a v1 license payload."
        assertEquals(expected, malformed(verifier, key.sign("not json")), "not JSON")
        assertEquals(expected, malformed(verifier, key.license("customer" to null)), "a missing field")
        assertEquals(expected, malformed(verifier, key.license("seats" to "3")), "a field v1 does not define")
        assertEquals(expected, malformed(verifier, key.license("maxPeople" to q("many"))), "a mistyped field")
    }

    @Test
    fun aKeyIdThisBuildDoesNotTrustIsUnknown() = runTest {
        val key = TestSigningKey.generate()
        val error = assertFailsWith<UnknownLicenseKeyException> {
            LicenseVerifierImpl(listOf(key.publicKey)).verify(key.license("keyId" to q("0000000000000000")), NOW)
        }
        assertEquals(
            "The license key was signed by an unknown key (0000000000000000); this build does not trust it.",
            error.message,
        )
    }

    @Test
    fun anAlteredPayloadHasAnInvalidSignature() = runTest {
        val key = TestSigningKey.generate()
        val signature = key.license().substringAfterLast('.')
        val raised = b64url(key.payload("maxPeople" to "5000").encodeToByteArray())
        val error = assertFailsWith<InvalidLicenseSignatureException> {
            LicenseVerifierImpl(listOf(key.publicKey)).verify("ostrom-license.v1.$raised.$signature", NOW)
        }
        assertEquals(
            "The license key's signature is invalid: the key was altered or not issued by Ostrom.",
            error.message,
        )
    }

    @Test
    fun aSignatureByAnotherKeyClaimingATrustedKeyIdIsInvalid() = runTest {
        val trusted = TestSigningKey.generate()
        val forged = TestSigningKey.generate().license("keyId" to q(trusted.keyId))
        assertFailsWith<InvalidLicenseSignatureException> {
            LicenseVerifierImpl(listOf(trusted.publicKey)).verify(forged, NOW)
        }
    }

    @Test
    fun aSignatureOfTheWrongSizeIsInvalid() = runTest {
        val key = TestSigningKey.generate()
        val verifier = LicenseVerifierImpl(listOf(key.publicKey))
        val payload = key.license().removePrefix("ostrom-license.v1.").substringBefore('.')
        assertFailsWith<InvalidLicenseSignatureException> {
            verifier.verify("ostrom-license.v1.$payload.${b64url(ByteArray(10))}", NOW)
        }
        assertFailsWith<InvalidLicenseSignatureException> { verifier.verify("ostrom-license.v1.$payload.", NOW) }
    }

    @Test
    fun invalidFieldsAreRefusedAfterTheSignature() = runTest {
        val key = TestSigningKey.generate()
        val verifier = LicenseVerifierImpl(listOf(key.publicKey))
        suspend fun field(vararg change: Pair<String, String?>) = malformed(verifier, key.license(*change))
        assertEquals("its licenseId is invalid.", field("licenseId" to q(" ")))
        assertEquals("its customer is invalid.", field("customer" to q("")))
        assertEquals("its maxPeople is invalid.", field("maxPeople" to "0"))
        assertEquals("its features is invalid.", field("features" to "[\"sso\",\" \"]"))
        assertEquals("its instanceId is invalid.", field("instanceId" to q("")))
        assertEquals("its issuedAt is invalid.", field("issuedAt" to q("yesterday")))
        assertEquals("its expiresAt is invalid.", field("expiresAt" to q("2027-02-30")))
        assertEquals("its expiresAt is invalid.", field("expiresAt" to q("2026-10-30T00:00:00Z")), "not after issued")
    }

    @Test
    fun aLicenseIsNotValidBeforeItWasIssuedBeyondFiveMinutesOfSkew() = runTest {
        val key = TestSigningKey.generate()
        val verifier = LicenseVerifierImpl(listOf(key.publicKey))
        val license = key.license("issuedAt" to q("2026-11-01T12:05:00Z"))
        assertEquals("lic_1", verifier.verify(license, Instant.parse("2026-11-01T12:00:00Z")).licenseId, "skew")
        val error = assertFailsWith<NotYetValidLicenseException> {
            verifier.verify(license, Instant.parse("2026-11-01T11:59:59Z"))
        }
        assertEquals("The license is not valid before 2026-11-01T12:05:00Z; check the server's clock.", error.message)
    }

    @Test
    fun anExpiredLicenseWorksForFourteenDaysThenIsRefused() = runTest {
        val inGrace = fixtureVerifier.verify(SIGN_PY_FIXTURE, Instant.parse("2027-11-05T00:00:00Z"))
        assertEquals("lic_fixture_0001", inGrace.licenseId)
        val lastInstant = fixtureVerifier.verify(SIGN_PY_FIXTURE, Instant.parse("2027-11-13T00:00:00Z"))
        assertEquals("lic_fixture_0001", lastInstant.licenseId, "the last instant of grace")
        val error = assertFailsWith<ExpiredLicenseException> {
            fixtureVerifier.verify(SIGN_PY_FIXTURE, Instant.parse("2027-11-13T00:00:01Z"))
        }
        assertEquals(
            "The license expired on 2027-10-30T00:00:00Z and its grace period ended on 2027-11-13T00:00:00Z; renew it.",
            error.message,
        )
    }
}
