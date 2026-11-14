// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/test/kotlin/me/nathanfallet/teamcode/domain/notifications/PushTest.kt; see NOTICE.
package run.ostrom.server.notifications

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.EncryptedPushSender
import run.ostrom.server.notifications.domain.FIREBASE_SERVICE_ACCOUNT_FIELD
import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.FirebaseServiceAccount
import run.ostrom.server.notifications.domain.PROVIDER_FIELD
import run.ostrom.server.notifications.domain.PUSH_SETTINGS
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushEncryption
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushNotSentException
import run.ostrom.server.notifications.domain.PushProvider
import run.ostrom.server.notifications.domain.PushRejectedException
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.notifications.infrastructure.CachedFirebaseSenders
import run.ostrom.server.notifications.infrastructure.EciesPushEncryption
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The encryption, the provider settings and the sender of the `fcm` pushes, without a provider ever called. */
class EncryptedPushTest {
    private val encryption = EciesPushEncryption()

    @Test
    fun aPushIsEncryptedForThePhonesKeyAndOnlyThePhoneReadsIt() = runTest {
        val phone = phoneKeys()
        val key = phone.publicKey()
        assertTrue(encryption.acceptsKey(key))

        val first = encryption.encrypt(key, """{"title":"A request waits"}""")
        val second = encryption.encrypt(key, """{"title":"A request waits"}""")

        assertEquals("""{"title":"A request waits"}""", phone.decrypt(first))
        assertNotEquals(first, second, "A new ephemeral key and nonce each time")
        val bytes = Base64.decode(first)
        assertEquals(1, bytes[0].toInt(), "Format 1")
        assertEquals(4, bytes[1].toInt(), "An uncompressed ephemeral point")
        assertEquals(1 + 65 + 12 + """{"title":"A request waits"}""".length + 16, bytes.size)
        assertFailsWith<Exception>("Another phone cannot read it") { phoneKeys().decrypt(first) }
    }

    @Test
    fun onlyAP256PublicKeyInDerBase64IsAccepted() = runTest {
        val p384 = CryptographyProvider.Default.get(ECDH).keyPairGenerator(EC.Curve.P384).generateKey()
        val other = Base64.encode(p384.publicKey.encodeToByteArray(EC.PublicKey.Format.DER))

        assertFalse(encryption.acceptsKey(other), "Another curve")
        assertFalse(encryption.acceptsKey("bm90IGEga2V5"), "Not a key")
        assertFalse(encryption.acceptsKey("not base64 at all!"))
        assertFalse(encryption.acceptsKey("A".repeat(256)), "Too long")
        assertFailsWith<IllegalArgumentException> { encryption.encrypt("bm90IGEga2V5", "{}") }
    }

    @Test
    fun theCollapseKeyIsAnOpaqueStableHash() = runTest {
        val key = encryption.collapseKey("org:approval:a1")

        assertEquals(key, encryption.collapseKey("org:approval:a1"))
        assertNotEquals(key, encryption.collapseKey("org:approval:a2"))
        assertTrue(Regex("[0-9a-f]{32}").matches(key), key)
    }

    /** Encrypts nothing: the payload is the plaintext, so a test reads it. */
    private class PlainEncryption : PushEncryption {
        override suspend fun acceptsKey(publicKey: String): Boolean = true

        override suspend fun encrypt(publicKey: String, plaintext: String): String =
            if (publicKey == "stale") error("does not open") else plaintext

        override suspend fun collapseKey(key: String): String = "ck:$key"
    }

    private class Settings(var values: Map<String, String>) : InstanceSettings {
        val read = mutableListOf<String>()

        override suspend fun read(section: String): SettingsValues {
            read += section
            return SettingsValues(values)
        }
    }

    private class Fcm(var answer: suspend (EncryptedPush) -> PushResult = { PushResult.SENT }) : FcmSender {
        val sent = mutableListOf<EncryptedPush>()

        override suspend fun send(push: EncryptedPush): PushResult {
            sent += push
            return answer(push)
        }
    }

    private val firebase = mapOf(PROVIDER_FIELD to "firebase", FIREBASE_SERVICE_ACCOUNT_FIELD to "{key}")

    private fun sender(
        settings: Settings,
        fcm: Fcm,
        devices: InMemoryPushDevicesRepository,
        origin: String = "https://ostrom.example",
    ) = EncryptedPushSender(
        settings, { account -> fcm.also { assertEquals("{key}", account) } }, PlainEncryption(), devices, origin,
        TestClock(),
    )

    private fun phone(token: String, key: String? = "key") = testPhone(token, "fcm", "android", key)

    private fun approval(device: PushDevice) = PushMessage(
        device,
        "A request waits for your yes",
        "Open Ostrom to read it.",
        mapOf("kind" to "approval", "approvalId" to "a1"),
    )

    @Test
    fun theFirebaseProviderGetsAnEncryptedPayloadNamingTheAccountAndTheRequest() = runTest {
        val fcm = Fcm()
        val settings = Settings(firebase)
        val device = phone("fcm-token-1")

        sender(settings, fcm, InMemoryPushDevicesRepository()).send(listOf(approval(device)))

        assertEquals(listOf("push"), settings.read)
        val push = fcm.sent.single()
        assertEquals("fcm-token-1" to "android", push.token to push.platform)
        assertEquals("ck:${device.orgId}:approval:a1", push.collapseKey)
        assertEquals(3600L, push.ttlSeconds)
        assertFalse(push.toString().contains("fcm-token-1"), "The token is never printed")
        val payload = Json.parseToJsonElement(push.payload).jsonObject
        assertEquals(
            mapOf(
                "v" to "1", "origin" to "https://ostrom.example", "orgId" to device.orgId.toString(),
                "memberId" to device.memberId.toString(), "kind" to "approval",
                "title" to "A request waits for your yes", "body" to "Open Ostrom to read it.", "approvalId" to "a1",
            ),
            payload.filterKeys { it != "id" && it != "at" }.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(TestClock().now.toEpochMilliseconds(), payload.getValue("at").jsonPrimitive.long)
        assertNotNull(payload["id"])
    }

    @Test
    fun aPushWithoutKindOrRequestIsAnApprovalWithoutRequest() = runTest {
        val fcm = Fcm()
        val device = phone("fcm-token-1")

        sender(Settings(firebase), fcm, InMemoryPushDevicesRepository())
            .send(listOf(PushMessage(device, "t", "b", emptyMap())))

        val payload = Json.parseToJsonElement(fcm.sent.single().payload) as JsonObject
        assertEquals("approval", payload.getValue("kind").jsonPrimitive.content)
        assertNull(payload["approvalId"], "Left out")
        assertEquals("ck:${device.orgId}:approval:", fcm.sent.single().collapseKey)
    }

    @Test
    fun whilePushIsOffNothingIsSentNorTriedAgain() = runTest {
        val fcm = Fcm()
        for (values in listOf(emptyMap(), mapOf(PROVIDER_FIELD to "off"), mapOf(PROVIDER_FIELD to "relay"))) {
            sender(Settings(values), fcm, InMemoryPushDevicesRepository()).send(listOf(approval(phone("t"))))
        }

        assertTrue(fcm.sent.isEmpty())
    }

    @Test
    fun firebaseWithoutItsServiceAccountIsRefusedForGood() = runTest {
        val refused = assertFailsWith<PushRejectedException> {
            sender(Settings(mapOf(PROVIDER_FIELD to "firebase")), Fcm(), InMemoryPushDevicesRepository())
                .send(listOf(approval(phone("t"))))
        }

        assertEquals("push_firebase_credentials_required", refused.message)
    }

    @Test
    fun aTokenFcmNoLongerKnowsIsForgottenAndAFailureIsTriedAgainOrGivenUp() = runTest {
        val devices = InMemoryPushDevicesRepository()
        val gone = phone("fcm-token-gone")
        devices.devices += gone
        val fcm = Fcm { PushResult.UNREGISTERED }

        sender(Settings(firebase), fcm, devices).send(listOf(approval(gone)))
        assertEquals(listOf(gone.id to "fcm-token-gone"), devices.forgotten)
        assertTrue(devices.devices.isEmpty())

        val answers = listOf(
            PushResult.retry("fcm_unavailable") to "fcm_unavailable",
            PushResult(PushResult.Status.RETRY) to "retry",
        )
        for ((answer, reason) in answers) {
            fcm.answer = { answer }
            val failed = assertFailsWith<PushNotSentException> {
                sender(Settings(firebase), fcm, devices).send(listOf(approval(phone("t"))))
            }
            assertFalse(failed is PushRejectedException)
            assertEquals(reason, failed.message)
        }
        val refusals = listOf(
            PushResult.rejected("fcm_403") to "fcm_403",
            PushResult(PushResult.Status.REJECTED) to "rejected",
        )
        for ((answer, reason) in refusals) {
            fcm.answer = { answer }
            val refused = assertFailsWith<PushRejectedException> {
                sender(Settings(firebase), fcm, devices).send(listOf(approval(phone("t"))))
            }
            assertEquals(reason, refused.message)
        }
    }

    @Test
    fun aPhoneWithoutKeyIsRefusedAndAProviderThatHangsIsARetry() = runTest {
        val fcm = Fcm()
        val refused = assertFailsWith<PushRejectedException> {
            sender(Settings(firebase), fcm, InMemoryPushDevicesRepository()).send(listOf(approval(phone("t", null))))
        }
        assertEquals("push_key_missing", refused.message)
        val stale = assertFailsWith<PushRejectedException> {
            sender(Settings(firebase), fcm, InMemoryPushDevicesRepository()).send(listOf(approval(phone("t", "stale"))))
        }
        assertEquals("push_key_invalid", stale.message)
        assertTrue(fcm.sent.isEmpty(), "Nothing in clear ever leaves")
        val nowhere = assertFailsWith<PushRejectedException> {
            sender(Settings(firebase), fcm, InMemoryPushDevicesRepository(), origin = "")
                .send(listOf(approval(phone("t"))))
        }
        assertEquals("push_public_url_required", nowhere.message, "Without OSTROM_PUBLIC_URL a push names no account")

        fcm.answer = { awaitCancellation() }
        val late = assertFailsWith<PushNotSentException> {
            sender(Settings(firebase), fcm, InMemoryPushDevicesRepository()).send(listOf(approval(phone("t"))))
        }
        assertEquals("timeout", late.message)
    }

    @Test
    fun thePushSectionTakesOffOrFirebaseAndAServiceAccountKeyOfGooglesOnly() {
        assertEquals("push", PUSH_SETTINGS.name)
        val provider = assertNotNull(PUSH_SETTINGS.field(PROVIDER_FIELD))
        assertFalse(provider.secret)
        assertNull(provider.check("off"))
        assertNull(provider.check("firebase"))
        assertEquals("off or firebase.", provider.check("relay"))
        val account = assertNotNull(PUSH_SETTINGS.field(FIREBASE_SERVICE_ACCOUNT_FIELD))
        assertTrue(account.secret, "Sealed, never answered back")
        assertNull(account.check(SERVICE_ACCOUNT))
        val refusal = assertNotNull(account.check("{}"))
        assertFalse(refusal.contains("{}"), "Never echoes the value")

        assertEquals(PushProvider.FIREBASE, PushProvider.of("firebase"))
        assertEquals(PushProvider.OFF, PushProvider.of(null))
        assertEquals(PushProvider.OFF, PushProvider.of("relay"))
    }

    @Test
    fun aServiceAccountKeyIsReadOnlyWhenItIsGooglesAndComplete() {
        val read = assertNotNull(FirebaseServiceAccount.parse(SERVICE_ACCOUNT))
        assertEquals("ostrom-app" to "push@ostrom-app.iam.gserviceaccount.com", read.projectId to read.clientEmail)
        assertEquals("kid-1", read.privateKeyId)
        assertFalse(read.toString().contains("PRIVATE KEY"), "The key is never printed")

        val refused = listOf(
            "not json", "[]", SERVICE_ACCOUNT.replace("service_account", "authorized_user"),
            SERVICE_ACCOUNT.replace("\"ostrom-app\"", "\"Bad_Project\""),
            SERVICE_ACCOUNT.replace("push@ostrom-app.iam.gserviceaccount.com", "not-an-email"),
            SERVICE_ACCOUNT.replace("-----BEGIN PRIVATE KEY-----", "x"),
            SERVICE_ACCOUNT.replace("\"kid-1\"", "\"\""),
            SERVICE_ACCOUNT.replace("https://oauth2.googleapis.com/token", "https://evil.example/token"),
            SERVICE_ACCOUNT.replace("\"kid-1\"", "1"),
        )
        refused.forEach { assertNull(FirebaseServiceAccount.parse(it), it) }
    }

    @Test
    fun theFirebaseSenderIsKeptWhileTheServiceAccountIsTheSame() = runTest {
        val made = mutableListOf<FirebaseServiceAccount>()
        val senders = CachedFirebaseSenders { account -> made += account; Fcm() }

        val first = senders.sender(SERVICE_ACCOUNT)
        assertSame(first, senders.sender(SERVICE_ACCOUNT))
        val other = SERVICE_ACCOUNT.replace("kid-1", "kid-2")
        assertNotEquals(first, senders.sender(other), "Another key, another sender")
        assertEquals(listOf("kid-1", "kid-2"), made.map { it.privateKeyId })

        val invalid = senders.sender("{}")
        val answer = invalid.send(EncryptedPush("t", "ios", "p", "c", 1))
        assertEquals(PushResult.rejected("push_firebase_credentials_invalid"), answer)
        assertEquals(2, made.size, "No sender for a key that does not parse")
    }

    companion object {
        /** A service account key of the shape Google gives, with a placeholder key (never a real one). */
        val SERVICE_ACCOUNT: String = """
            {"type":"service_account","project_id":"ostrom-app","private_key_id":"kid-1",
            "private_key":"-----BEGIN PRIVATE KEY-----\nplaceholder\n-----END PRIVATE KEY-----\n",
            "client_email":"push@ostrom-app.iam.gserviceaccount.com","token_uri":"https://oauth2.googleapis.com/token"}
        """.trimIndent()
    }
}
