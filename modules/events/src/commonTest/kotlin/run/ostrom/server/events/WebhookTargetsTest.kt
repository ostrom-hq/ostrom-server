// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import run.ostrom.server.events.domain.InvalidWebhookException
import run.ostrom.server.events.domain.WebhookTargets
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where a webhook may point, judged on its URL. */
class WebhookTargetsTest {
    private val public = WebhookTargets(allowPrivate = false)
    private val private = WebhookTargets(allowPrivate = true)

    @Test
    fun aPublicHttpsTargetPassesAndItsShapeIsChecked() {
        val fine = listOf(
            "https://hooks.example.com/ostrom",
            "https://93.184.215.14:8443/x?a=1",
            "https://[2606:4700::1]/",
        )
        for (url in fine) {
            public.requireWellFormed(url)
            assertTrue(public.allows(url), url)
        }
        val malformed = listOf(
            "http://hooks.example.com/",
            "https://user:pw@hooks.example.com/",
            "https://hooks.example.com/#frag",
            "https://hooks.example.com/a b",
            "https:///path",
            "https://" + "a".repeat(2_048) + ".com/",
            "https://169.254.169.254/latest",
            "https://metadata.google.internal/",
        )
        for (url in malformed) assertFailsWith<InvalidWebhookException>(url) { public.requireWellFormed(url) }
    }

    @Test
    fun privateLoopbackAndNumericSpellingsAreRefusedUnlessTheOperatorAllowsThem() {
        val off = listOf(
            "https://localhost/",
            "https://api.localhost./",
            "https://127.0.0.1/",
            "https://10.0.0.8:8443/",
            "https://[::1]/",
            "https://[fd00::5]/",
            "https://2130706433/",
            "https://0x7f.1/",
            "https://127.1/",
        )
        for (url in off) {
            assertFalse(public.allows(url), url)
            assertTrue(private.allows(url), "Allowed by the operator: $url")
        }
        for (url in listOf("https://169.254.169.254/", "https://[fe80::1]/", "ftp://hooks.example.com/")) {
            assertFalse(private.allows(url), "Never: $url")
        }
    }
}
