// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.notifications.domain.PushCredential
import dev.ostrom.server.notifications.presentation.pushCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PushCredentialTest {
    private val user = Uuid.random()
    private val credential = Uuid.random()

    private fun principal(
        subject: String = user.toString(),
        kind: PrincipalKind = PrincipalKind.USER,
        credentialId: String? = credential.toString(),
        channel: PrincipalChannel = PrincipalChannel.MOBILE_APP,
    ) = AuthenticatedPrincipal(subject, kind, emptySet(), credentialId = credentialId, channel = channel)

    @Test
    fun thePhoneAppsTokenAndABrowserSessionAreKeptWithTheirPerson() {
        assertEquals(PushCredential(user, credential, browser = false), principal().pushCredential())
        assertEquals(
            PushCredential(user, credential, browser = true),
            principal(channel = PrincipalChannel.BROWSER).pushCredential(),
        )
    }

    @Test
    fun aCredentialThatIsNotAPersonsOrHasNoIdIsNone() {
        assertNull(principal(kind = PrincipalKind.DEVICE).pushCredential())
        assertNull(principal(subject = "service-key").pushCredential())
        assertNull(principal(credentialId = null).pushCredential())
        assertNull(principal(credentialId = "not-a-uuid").pushCredential())
    }
}
