// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets

import dev.ostrom.server.secrets.domain.InvalidSecretException
import dev.ostrom.server.secrets.domain.SecretCursor
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.presentation.SecretWriteRequest
import dev.ostrom.server.secrets.presentation.personalScope
import dev.ostrom.server.secrets.presentation.toIdOr
import dev.ostrom.server.secrets.presentation.toSecretCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PresentationTest {
    @Test
    fun theScopeIsOrgByDefaultOrPersonal() {
        assertEquals(false, personalScope(null))
        assertEquals(false, personalScope("org"))
        assertEquals(true, personalScope("personal"))
        assertFailsWith<InvalidSecretException> { personalScope("everyone") }
    }

    @Test
    fun aMalformedIdIsTheSameAsAnUnknownOne() {
        val id = Uuid.random()
        assertEquals(id, id.toString().toIdOr(::SecretNotFoundException))
        assertFailsWith<SecretNotFoundException> { "not-an-id".toIdOr(::SecretNotFoundException) }
    }

    @Test
    fun aWriteRequestNeverPrintsItsValue() {
        val printed = SecretWriteRequest("TOKEN", "glsa_very_secret_value", "org").toString()
        assertFalse("glsa_very_secret_value" in printed, printed)
        assertTrue("TOKEN" in printed)
    }

    @Test
    fun aCursorReadsBackTheNameAndIdOfTheLastSecret() {
        val id = Uuid.random()
        assertEquals(SecretCursor("A.b-c", id), "A.b-c/$id".toSecretCursor())
        for (bad in listOf("A", "A/x", "")) assertFailsWith<InvalidSecretException>(bad) { bad.toSecretCursor() }
    }
}
