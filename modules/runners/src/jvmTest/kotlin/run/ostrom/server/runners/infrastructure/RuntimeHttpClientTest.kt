// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The runtime's client trusts the CA bundle it is given, and only it. */
class RuntimeHttpClientTest {
    private fun bundle(text: String): File = File.createTempFile("ostrom-ca", ".pem").apply {
        writeText(text)
        deleteOnExit()
    }

    @Test
    fun theBundleIsTheOnlyIssuerTrusted() {
        val trust = trustingOnly(bundle(TEST_CA))
        assertEquals(listOf("CN=Ostrom test runtime CA"), trust.acceptedIssuers.map { it.subjectX500Principal.name })
    }

    @Test
    fun aBundleWithoutCertificateIsRefused() {
        val error = assertFailsWith<IllegalArgumentException> { trustingOnly(bundle("")) }
        assertEquals(true, error.message?.contains("holds no certificate"))
    }

    @Test
    fun theBundleIsReadAtTheFirstHandshakeOnly() {
        val missing = File.createTempFile("ostrom-ca", ".pem").apply { delete() }
        runtimeHttpClient(missing.path, 1_000).close()
        val trust = BundleTrustManager(missing)
        assertFailsWith<FileNotFoundException> { trust.acceptedIssuers }
        missing.writeText(TEST_CA)
        missing.deleteOnExit()
        val issuer = trust.acceptedIssuers.single()
        trust.checkServerTrusted(arrayOf(issuer), "RSA")
        trust.checkClientTrusted(arrayOf(issuer), "RSA")
    }

    @Test
    fun aClientIsMadeWithOrWithoutABundle() {
        runtimeHttpClient(bundle(TEST_CA).path, 1_000).close()
        runtimeHttpClient(null, 1_000).close()
    }

    private companion object {
        /** A self-signed RSA certificate made for these tests (`openssl req -x509`), no key kept. */
        val TEST_CA = """
            -----BEGIN CERTIFICATE-----
            MIICwDCCAagCCQD+EaITR1TssDANBgkqhkiG9w0BAQsFADAhMR8wHQYDVQQDDBZP
            c3Ryb20gdGVzdCBydW50aW1lIENBMCAXDTI2MTExMzE2MjgzMFoYDzIxMjYxMDIw
            MTYyODMwWjAhMR8wHQYDVQQDDBZPc3Ryb20gdGVzdCBydW50aW1lIENBMIIBIjAN
            BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsgO8sBhvU3WQQAXjLsSUwMAEgofC
            qttuXS1Uq5SVEIU44gVQd6lEAfr4xjGRAXcwjQo6oMMloAVOaSQcif4HQprHWOQ+
            AKG02TFebBqeJa7m9NkYGtHJh20XVgakGABbzhmUl2LJ2zmqMMXUR9QxrWC4W/FZ
            prEWAfxqtAeXIzdfMbFOaCgN1n5M7oT7JES+LQYOXSgc+OPCKSLXS9HfS2n/lujc
            dMpzalJEpzo6wYTmbhOBstNCBjWzPhUw94q0YBMlsaDb4SRH8GSfgds9sv0JvSyQ
            I1GmtWEdLuqtKH4A5pI7H7rkOBwkEx+jAzq3qv9d6yaKe7LOCdEGL7ipmwIDAQAB
            MA0GCSqGSIb3DQEBCwUAA4IBAQCEjg0x5RquylbGsKy3e2qtNuIWy4hbzxYI2r1K
            sEbq3WWwrXfwsVgH5VGYyEaij+WYtqOLbdtsQcEmZAiqDLwdeGw+RZ6Z9hVAxmy6
            dXaAYEMkR5uG8WDyjtxHTk9xngYCtykv0Grf5H9vCoyBkRveCwzcbHn6xpg/MtzL
            TwgyZT0j1V4bV+NW53FNOu0JkX49rll0pWQBqqU001QiU0DSp+XZeAasJ2DDdIt9
            YypcXGrEqbbqBEnuTwt7+cAtJppsoRUwpV2zA9oVwIuhhFdD03STN6C1zx3AvdV+
            ivP9mnhp6+64YULT0VptL+UeU6NtZD47yIwTeDVNlCl7wsSP
            -----END CERTIFICATE-----
        """.trimIndent()
    }
}
