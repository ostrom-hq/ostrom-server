// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.secrets

import dev.ostrom.server.secrets.domain.InvalidSecretException
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.secrets.presentation.personalScope
import dev.ostrom.server.secrets.presentation.toIdOr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
