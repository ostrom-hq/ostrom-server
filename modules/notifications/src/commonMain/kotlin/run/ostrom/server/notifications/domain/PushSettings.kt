// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/PushSettings.kt; see NOTICE.
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Who sends the instance's `fcm` pushes: nobody, or the instance's own Firebase project. */
enum class PushProvider(val wire: String) {
    /** Nothing is sent; phones still register. */
    OFF("off"),

    /** The Firebase project whose service account the administrator saved: the one the apps are built with. */
    FIREBASE("firebase"),
    ;

    /** Reads a stored provider. */
    companion object {
        /**
         * @param value The stored value.
         *
         * @return Its provider; [OFF] for none or an unknown one.
         */
        fun of(value: String?): PushProvider = entries.firstOrNull { it.wire == value } ?: OFF
    }
}

/** The field of [PUSH_SETTINGS] that names the provider. */
const val PROVIDER_FIELD = "provider"

/** The secret field of [PUSH_SETTINGS] that holds the Firebase service account JSON key. */
const val FIREBASE_SERVICE_ACCOUNT_FIELD = "firebaseServiceAccount"

/**
 * A Google service account JSON key, checked: what the sender needs to sign its token requests. Never logged.
 *
 * @property projectId The Firebase project.
 * @property clientEmail The service account.
 * @property privateKeyId The key's id, in the token request's header.
 * @property privateKey The PKCS#8 PEM private key.
 */
class FirebaseServiceAccount(
    val projectId: String,
    val clientEmail: String,
    val privateKeyId: String,
    val privateKey: String,
) {
    override fun toString(): String = "FirebaseServiceAccount(projectId=$projectId, clientEmail=$clientEmail)"

    /** Reads a key. */
    companion object {
        /**
         * Reads a Google service account JSON key (TeamCode's `parseServiceAccount`): `type` `service_account`, a
         * project, a client e-mail, a private key and its id, and Google's token endpoint, the only one the server
         * posts a signed assertion to.
         *
         * @param json The key.
         *
         * @return It, or null when it is not such a key.
         */
        fun parse(json: String): FirebaseServiceAccount? {
            val root = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
            fun field(name: String) = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val valid = field("type") == "service_account" && field("token_uri") == GOOGLE_TOKEN_URI
            val project = field("project_id")?.takeIf { valid && PROJECT_ID.matches(it) }
            val email = field("client_email")?.takeIf(CLIENT_EMAIL::matches)
            // The private key with its id: the assertion's signature and the header naming it.
            val signer = field("private_key")?.takeIf { "PRIVATE KEY-----" in it }
                ?.let { key -> field("private_key_id")?.takeIf { it.isNotEmpty() }?.let { key to it } }
            return if (project != null && email != null && signer != null) {
                FirebaseServiceAccount(project, email, signer.second, signer.first)
            } else {
                null
            }
        }
    }
}

/**
 * The instance's push settings (Admin → Settings → `push`), stored and sealed by the admin module: `provider` (`off`,
 * the default, or `firebase`) and the Firebase project's service account JSON key, a write-only secret sealed with the
 * secrets key. Never an environment variable nor a Helm value.
 */
val PUSH_SETTINGS: SettingsSection = SettingsSection(
    "push",
    listOf(
        SettingsField(PROVIDER_FIELD, check = { value ->
            if (PushProvider.entries.any { it.wire == value }) null else "off or firebase."
        }),
        SettingsField(FIREBASE_SERVICE_ACCOUNT_FIELD, secret = true, check = { value ->
            if (FirebaseServiceAccount.parse(value) == null) SERVICE_ACCOUNT_REFUSAL else null
        }),
    ),
)

/** The only token endpoint a stored key may name: the server posts its signed assertion there. */
const val GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"

private const val SERVICE_ACCOUNT_REFUSAL = "paste the JSON key of a Google service account of the Firebase project " +
    "(type service_account, token_uri $GOOGLE_TOKEN_URI)."
private val PROJECT_ID = Regex("[a-z][a-z0-9-]{4,62}")
private val CLIENT_EMAIL = Regex("[A-Za-z0-9._%+-]{1,128}@[A-Za-z0-9.-]{1,128}")
