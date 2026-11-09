// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license.infrastructure

/**
 * The public keys whose license keys this build trusts, as base64 of the DER SubjectPublicKeyInfo. Compiled in, never
 * read from the configuration: a key an operator could set would let anyone sign their own license.
 *
 * Rotation: add the new key here, release, sign new licenses with it, and remove the old key only once every license
 * it signed has expired (a removed key makes its licenses fail with "unknown key"). The private keys live outside
 * the repository (`ee/README.md`); `ee/tools/license/sign.py --print-public-key` prints the line to add.
 */
object PinnedLicenseKeys {
    /** Key id `5cceefe10dca26f3`, created on 30/10/2026. */
    const val ED25519_2026_10: String = "MCowBQYDK2VwAyEACIFioTGJviBXEX6sfYdbVtCcStqJoa7Fj7VXj/mp3VY="

    /** Every trusted key. */
    val ALL: List<String> = listOf(ED25519_2026_10)
}
