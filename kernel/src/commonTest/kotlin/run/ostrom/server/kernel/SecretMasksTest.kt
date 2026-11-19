// SPDX-License-Identifier: Apache-2.0
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
    fun aBareCredentialIsMaskedByItsShape() {
        // Audit SEC-01: an Ostrom token is 43 base64url characters after its prefix, never only hex; none of these
        // follows `Bearer ` or `X_TOKEN=`.
        val random = "Zq3-x_9LmNpQrStUvWxYz0123456789AbCdEfGhIjKl"
        val bare = listOf(
            "osp_$random", "osc_$random", "osg_$random", "osa_$random", "osm_$random", "oss_$random", "osb_$random",
            "opk_$random",
            "ostrom_runner_${"0a1b2c3d".repeat(8)}", "ostrom_term_$random", "whsec_$random", "wev_${"9f".repeat(32)}",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlMTIz",
            "ASIAABCDEFGHIJKLMNOP", "sk-ant-api03-abcdefghij0123456789",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----",
        )
        bare.forEach { secret -> assertEquals("echo *** done", SecretMasks.masked("echo $secret done"), secret) }
    }

    @Test
    fun namesThatOnlyLookLikePrefixesStayReadable() {
        val text = "git checkout osp_migration_notes && cat whsec_readme.md wev_short ostrom_runner_logs"
        assertEquals(text, SecretMasks.masked(text))
    }

    @Test
    fun aTextWithoutSecretsIsUnchanged() {
        val command = "git --git-dir=../other/.git push origin main"
        assertEquals(command, SecretMasks.masked(command))
    }
}
