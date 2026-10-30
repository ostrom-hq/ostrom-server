// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license

import dev.ostrom.server.billing.domain.LicenseGrant
import dev.ostrom.server.ee.license.domain.License
import dev.ostrom.server.ee.license.domain.LicenseInstanceMismatchException
import dev.ostrom.server.ee.license.domain.LicenseVerifier
import dev.ostrom.server.ee.license.domain.MalformedLicenseException
import dev.ostrom.server.ee.license.infrastructure.LicenseProviderImpl
import dev.ostrom.server.ee.license.infrastructure.LicenseVerifierImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Counts verifications and delegates to the real verifier. */
private class CountingVerifier(private val delegate: LicenseVerifier) : LicenseVerifier {
    var calls = 0

    override suspend fun verify(licenseKey: String, now: Instant): License {
        calls++
        return delegate.verify(licenseKey, now)
    }
}

class LicenseProviderTest {
    private val clock = MutableClock()
    private val logger = RecordingLogger()
    private val instance = FakeInstance()
    private val fixtureVerifier = CountingVerifier(LicenseVerifierImpl(listOf(SIGN_PY_TEST_PUBLIC_KEY)))

    private fun provider(key: String?, verifier: LicenseVerifier = fixtureVerifier) =
        LicenseProviderImpl(key, verifier, instance, clock, logger)

    @Test
    fun withoutAKeyThereIsNoLicenseAndNothingIsVerified() = runTest {
        val provider = provider(null)
        assertNull(provider.load())
        assertNull(provider.current())
        assertEquals(0, fixtureVerifier.calls)
        assertEquals(emptyList(), logger.lines)
    }

    @Test
    fun aValidKeyIsVerifiedOnceAndGrantsItsFeatures() = runTest {
        val provider = provider(SIGN_PY_FIXTURE)
        assertEquals("lic_fixture_0001", provider.load()?.licenseId)
        val grant = LicenseGrant(setOf("sso", "policies", "retention"), 50, Instant.parse("2027-10-30T00:00:00Z"))
        assertEquals(grant, provider.current())
        assertEquals(grant, provider.current())
        assertEquals(1, fixtureVerifier.calls, "verified once, then cached")
        assertEquals(0, instance.calls, "a license for any instance never reads the instance")
        assertEquals(
            listOf(
                "INFO license verified {license_id=lic_fixture_0001, customer=Acme Test Corp, " +
                    "expires_at=2027-10-30T00:00:00Z, " +
                    "max_people=50, features=policies,retention,sso, key_id=59f7291142778b67}",
            ),
            logger.lines,
        )
    }

    @Test
    fun theKeyIsNeverLogged() = runTest {
        provider(SIGN_PY_FIXTURE).load()
        val signature = SIGN_PY_FIXTURE.substringAfterLast('.')
        assertFalse(logger.lines.any { "ostrom-license" in it || signature in it })
    }

    @Test
    fun anInvalidKeyFailsTheLoadWithItsReason() = runTest {
        val error = assertFailsWith<MalformedLicenseException> { provider("not-a-license").load() }
        assertEquals("The license key is malformed: it does not start with ostrom-license.v1.", error.message)
        assertEquals(emptyList(), logger.lines)
    }

    @Test
    fun aLicenseInItsGracePeriodWorksAndWarnsAtStart() = runTest {
        clock.now = Instant.parse("2027-11-02T00:00:00Z")
        val provider = provider(SIGN_PY_FIXTURE)
        assertEquals(50, provider.current()?.maxPeople)
        assertEquals(2, logger.lines.size)
        assertTrue(
            logger.lines[1].startsWith("WARN license expired, in grace period {license_id=lic_fixture_0001"),
            logger.lines[1],
        )
        assertTrue(logger.lines[1].endsWith("grace_ends_at=2027-11-13T00:00:00Z}"), logger.lines[1])
    }

    @Test
    fun aLicenseWhoseGraceEndsWhileRunningStopsGrantingAndWarnsOnce() = runTest {
        val provider = provider(SIGN_PY_FIXTURE)
        assertEquals(50, provider.current()?.maxPeople)
        clock.now = Instant.parse("2027-11-13T00:00:00Z")
        assertEquals(50, provider.current()?.maxPeople, "the last instant of grace still grants")
        clock.now = Instant.parse("2027-11-13T00:00:01Z")
        assertNull(provider.current())
        assertNull(provider.current())
        val over = logger.lines.count { it.startsWith("WARN license grace period over, enterprise features disabled") }
        assertEquals(1, over)
        assertEquals(1, fixtureVerifier.calls)
    }

    @Test
    fun aLicenseBoundToThisInstanceIsAccepted() = runTest {
        val key = TestSigningKey.generate()
        val verifier = LicenseVerifierImpl(listOf(key.publicKey))
        val license = key.license("instanceId" to q(instance.id.toString().uppercase()))
        assertEquals(instance.id.toString().uppercase(), provider(license, verifier).load()?.instanceId)
        assertEquals(1, instance.calls)
    }

    @Test
    fun aSoldLicenseFromSignPyIsAcceptedOnItsInstance() = runTest {
        val license = provider(SIGN_PY_BOUND_FIXTURE).load()
        assertEquals(instance.id.toString(), license?.instanceId)
        assertEquals(1, instance.calls)
    }

    @Test
    fun aLicenseBoundToAnotherInstanceFailsTheLoad() = runTest {
        val key = TestSigningKey.generate()
        val verifier = LicenseVerifierImpl(listOf(key.publicKey))
        val error = assertFailsWith<LicenseInstanceMismatchException> {
            provider(key.license("instanceId" to q("11111111-1111-1111-1111-111111111111")), verifier).load()
        }
        assertEquals(
            "The license is for instance 11111111-1111-1111-1111-111111111111, not this instance (${instance.id}). " +
                "To have it re-issued for this instance, send this instance id (GET /api/v1/instance) " +
                "to sales@ostrom.run.",
            error.message,
        )
    }

    @Test
    fun loadWithCancellationPropagatesException() = runTest {
        val cancelling = object : LicenseVerifier {
            override suspend fun verify(licenseKey: String, now: Instant): License =
                throw CancellationException("stopping")
        }
        assertFailsWith<CancellationException> { provider(SIGN_PY_FIXTURE, cancelling).load() }
    }
}
