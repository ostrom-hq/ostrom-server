// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.text.SecretMasks
import kotlin.test.Test
import kotlin.test.assertEquals

/** A command a workstation reports reaches channels and the audit log without the secrets it carried. */
class SecretMasksTest {
    @Test
    fun eachKindOfSecretIsMasked() {
        val cases = mapOf(
            "curl -H 'Authorization: Bearer abcdefgh1234'" to "curl -H 'Authorization: Bearer ***'",
            "git push https://ada:hunter22@github.com/acme/api.git" to "git push https://***@github.com/acme/api.git",
            "GITHUB_TOKEN=s3cr3t git push" to "GITHUB_TOKEN=*** git push",
            "deploy --password hunter22 --api-key=k3y" to "deploy --password *** --api-key=***",
            "echo ghp_0123456789abcdefghijABCDEFGHIJ" to "echo ***",
        )
        cases.forEach { (text, masked) -> assertEquals(masked, SecretMasks.masked(text), text) }
    }

    @Test
    fun aTextWithoutSecretsIsUnchanged() {
        val command = "git --git-dir=../other/.git push origin main"
        assertEquals(command, SecretMasks.masked(command))
    }
}
