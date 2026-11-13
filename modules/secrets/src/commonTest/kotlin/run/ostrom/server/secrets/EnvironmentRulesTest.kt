// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets

import run.ostrom.server.secrets.domain.AssignedEnvironmentValue
import run.ostrom.server.secrets.domain.AssignmentSource
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentRules
import run.ostrom.server.secrets.domain.EnvironmentValues
import run.ostrom.server.secrets.domain.EnvironmentVariant
import run.ostrom.server.secrets.domain.InvalidEnvironmentVariantException
import run.ostrom.server.secrets.domain.SecretValue
import run.ostrom.server.secrets.domain.StoredEnvironmentVariant
import run.ostrom.server.secrets.domain.VariantChange
import run.ostrom.server.secrets.presentation.EnvironmentAssignRequest
import run.ostrom.server.secrets.presentation.EnvironmentValuesResponse
import run.ostrom.server.secrets.presentation.EnvironmentVariantCreateRequest
import run.ostrom.server.secrets.presentation.EnvironmentVariantUpdateRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The rules of environment variants, their wire names and what never reaches a log. */
class EnvironmentRulesTest {
    @Test
    fun aBindingOrASourceThisVersionDoesNotKnowReadsAsNoneOrTheWeakest() {
        assertEquals(EnvironmentBinding.PER_MEMBER, EnvironmentBinding.fromWire("perMember"))
        assertNull(EnvironmentBinding.fromWire("future"))
        assertEquals(AssignmentSource.DIRECT, AssignmentSource.fromWire("direct"))
        assertEquals(AssignmentSource.BULK, AssignmentSource.fromWire("group"))
        assertTrue(AssignmentSource.DIRECT.precedence > AssignmentSource.BULK.precedence)
    }

    @Test
    fun namesThatWouldTakeOverTheProcessAreReserved() {
        for (name in listOf("PATH", "BASH_ENV", "LD_PRELOAD", "OSTROM_TOKEN", "CLAUDE_ENV_FILE", "GIT_CONFIG_COUNT")) {
            assertTrue(EnvironmentRules.isReserved(name), name)
            assertFailsWith<InvalidEnvironmentVariantException> { EnvironmentRules.requireVariable(name) }
        }
        assertFalse(EnvironmentRules.isReserved("GITHUB_TOKEN"))
        EnvironmentRules.requireVariable("GITHUB_TOKEN")
        assertFailsWith<InvalidEnvironmentVariantException> { EnvironmentRules.requireVariable("A".repeat(129)) }
    }

    @Test
    fun aValueAndANameKeepTheirBounds() {
        EnvironmentRules.requireValue(SecretValue("x".repeat(EnvironmentRules.MAX_VALUE_LENGTH)))
        assertFailsWith<InvalidEnvironmentVariantException> {
            EnvironmentRules.requireValue(SecretValue("x".repeat(EnvironmentRules.MAX_VALUE_LENGTH + 1)))
        }
        EnvironmentRules.requireName("x".repeat(EnvironmentRules.MAX_NAME_LENGTH))
        assertFailsWith<InvalidEnvironmentVariantException> { EnvironmentRules.requireName("a\nb") }
    }

    @Test
    fun noValueOrEnvelopeReachesALog() {
        val now = Instant.parse("2026-11-13T00:00:00Z")
        val variant = EnvironmentVariant(
            Uuid.random(), Uuid.random(), "TOKEN", "t", EnvironmentBinding.SHARED, null, true, 1, now, now,
        )
        val texts = listOf(
            StoredEnvironmentVariant(variant, "envelope-secret").toString(),
            AssignedEnvironmentValue("TOKEN", variant.id, EnvironmentBinding.SHARED, "envelope-secret").toString(),
            EnvironmentValues(mapOf("TOKEN" to SecretValue("value-secret")), listOf("OTHER")).toString(),
            EnvironmentVariantCreateRequest("TOKEN", "t", value = "value-secret").toString(),
            EnvironmentVariantUpdateRequest(1, value = "value-secret").toString(),
            EnvironmentAssignRequest("id", "value-secret").toString(),
            EnvironmentValuesResponse(mapOf("TOKEN" to "value-secret"), emptyList()).toString(),
            VariantChange(1, "TOKEN", "envelope-secret", now).toString(),
        )
        for (text in texts) {
            assertFalse("secret" in text, text)
            assertTrue("TOKEN" in text || "id" in text || "expectedVersion" in text, text)
        }
        assertFalse(variant.personal)
        assertEquals(emptyMap(), EnvironmentValues.NONE.values)
    }
}
