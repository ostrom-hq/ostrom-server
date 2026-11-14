// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.infrastructure.AppJwt
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.infrastructure.credentials
import run.ostrom.server.integrations.github.infrastructure.oauthClient
import run.ostrom.server.integrations.github.infrastructure.WebhookSignature
import run.ostrom.server.kernel.config.MapConfigSource
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubCryptoTest {
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun theAppJwtIsAnRs256TokenGitHubAcceptsFromBothKeyFormats() = runTest {
        val rsa = CryptographyProvider.Default.get(RSA.PKCS1)
        val pair = rsa.keyPairGenerator(keySize = 2048.bits, digest = SHA256).generateKey()
        val formats = listOf(RSA.PrivateKey.Format.PEM.PKCS1, RSA.PrivateKey.Format.PEM.Generic)
        for (format in formats) {
            val pem = pair.privateKey.encodeToByteArray(format).decodeToString()
            val jwt = AppJwt("123", pem, SettableClock())
            val token = jwt.token()
            assertEquals(token, jwt.token(), "Same second, same token; the key is decoded once")
            val (header, claims, signature) = token.split('.')
            assertEquals("""{"alg":"RS256","typ":"JWT"}""", urlSafe.decode(header).decodeToString())
            val payload = Json.parseToJsonElement(urlSafe.decode(claims).decodeToString()).jsonObject
            assertEquals(NOW.epochSeconds - 60, payload.getValue("iat").jsonPrimitive.long)
            assertEquals(NOW.epochSeconds + 480, payload.getValue("exp").jsonPrimitive.long)
            assertEquals("123", payload.getValue("iss").jsonPrimitive.content)
            val verified = pair.publicKey.signatureVerifier()
                .tryVerifySignature("$header.$claims".encodeToByteArray(), urlSafe.decode(signature))
            assertTrue(verified, "Signed with the App's key")
        }
    }

    @Test
    fun onlyTheSignatureOfThisBodyWithThisSecretPasses() = runTest {
        val body = """{"action":"opened"}""".encodeToByteArray()
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, "s3cret".encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature(body)
        val good = "sha256=" + mac.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signature = WebhookSignature { "s3cret" }

        assertTrue(signature.verify(body, good))
        assertFalse(signature.verify("""{"action":"closed"}""".encodeToByteArray(), good), "Another body")
        assertFalse(WebhookSignature { "other" }.verify(body, good), "Another secret")
        assertFalse(signature.verify(body, good.dropLast(1)), "Shorter")
        assertFalse(signature.verify(body, good + "0"), "Longer")
        assertFalse(signature.verify(body, good.replaceFirst("sha256=", "sha1=")), "Another algorithm")
        assertFalse(signature.verify(body, null), "Unsigned")
        assertFalse(WebhookSignature { null }.verify(body, good), "No secret: nothing passes")
    }

    @Test
    fun theSettingsComeFromTheEnvironmentAndNeverShowTheKey() {
        val values = mapOf(
            "OSTROM_GITHUB_APP_ID" to " 123 ",
            "OSTROM_GITHUB_APP_PEM" to "-----BEGIN RSA PRIVATE KEY-----\\nabc\\n-----END RSA PRIVATE KEY-----",
            "OSTROM_GITHUB_WEBHOOK_SECRET" to "s3cret",
            "OSTROM_GITHUB_APP_SLUG" to "ostrom-dev",
            "OSTROM_WEB_ORIGIN" to "https://console.example/",
            "OSTROM_GITHUB_APP_CLIENT_ID" to "Iv1.abc",
            "OSTROM_GITHUB_APP_CLIENT_SECRET" to "client-s3cret",
            "OSTROM_EMPTY" to " ",
        )
        val settings = GitHubSettings.from(source(values))
        assertEquals("123", settings.appId)
        assertEquals("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----", settings.privateKeyPem)
        assertEquals("https://console.example", settings.webOrigin)
        assertTrue(settings.configured)
        assertEquals("Iv1.abc", settings.clientId)
        assertEquals("client-s3cret", settings.clientSecret)
        val app = settings.credentials()
        assertEquals("ostrom-dev", app.installSlug)
        assertEquals("Iv1.abc", app.oauthClient()?.id)
        assertEquals("client-s3cret", app.oauthClient()?.secret)
        assertFalse("abc" in settings.toString() || "s3cret" in settings.toString())
        // Without its OAuth client, the App is not installed: nothing would prove the installation is the person's.
        for (partial in listOf(app.copy(clientId = null), app.copy(clientSecret = null))) {
            assertNull(partial.installSlug)
            assertNull(partial.oauthClient())
        }
        assertNull(app.copy(slug = null).installSlug)

        val empty = GitHubSettings.from(source(mapOf("OSTROM_GITHUB_APP_ID" to " ")))
        assertEquals(GitHubSettings(null, null, null, null), empty)
        assertFalse(empty.configured)
        assertFalse(empty.copy(appId = "1").configured)
    }

    private fun source(values: Map<String, String>) = MapConfigSource(values)
}
