# ee/

Code under this directory is **not** Apache-2.0: it is licensed under the Ostrom Enterprise Edition license
([`ee/LICENSE`](LICENSE)) and only runs in production with a valid license key. Everything outside `ee/` is
Apache-2.0 ([`../LICENSE`](../LICENSE)). Source files here start with
`// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise`.

```
ee/
  modules/<name>/            # an enterprise ServerModule, Gradle path :ee:modules:<name>
    build.gradle.kts         # plugins { id("ostrom.native-module") }, like modules/ (docs/MODULES.md)
    ee-module.properties     # serverModule=<fully qualified ServerModule object>
  tools/license/sign.py      # signs license keys (offline, private key outside the repository)
```

## How the build finds enterprise modules

- `settings.gradle.kts` includes every `ee/modules/<name>` that has a `build.gradle.kts`, as `:ee:modules:<name>`,
  exactly as it discovers `modules/`.
- `app/build.gradle.kts` depends on each of them and reads its `ee-module.properties`, then generates
  `app/build/generated/ee-modules/.../EeModules.kt`: `internal fun eeModules(): List<ServerModule>` listing those
  objects (sorted by directory name). `serverModules()` appends `eeModules()` after every Apache-2.0 module, so an
  enterprise module migrates and initializes after the modules it builds on (billing, instance). The list is written at
  build time, so no `ServiceLoader` or reflection is needed.
- **Apache-2.0 build**: delete `ee/`. Nothing is discovered, `eeModules()` is empty, and the server builds and runs with
  Apache-2.0 code only. Nothing outside `ee/` may reference `ee/`: Apache-2.0 modules expose ports (billing's `LicenseProvider`)
  that an enterprise module binds in Koin, and resolve them with `getOrNull()` and an Apache-2.0 default. CI proves it on
  every pull request: a lexical check that no file outside `ee/` names `run.ostrom.server.ee`, then
  `rm -rf ee && ./gradlew :app:compileKotlinJvm`.

Enterprise features are switched on by the license key, in code, never by an environment flag (CONVENTIONS § 6.2).

## The license module (`ee/modules/license`)

Binds billing's `LicenseProvider`. At start (`initialize`) it verifies the configured key once, offline, and caches
it; billing reads `features` (`sso`, `policies`, `retention`), `maxPeople` and `expiresAt` from it.

| Situation | Result |
| --- | --- |
| No `OSTROM_LICENSE_KEY` nor `OSTROM_LICENSE_KEY_FILE` | No license (billing's self-hosted defaults) |
| Valid key | Logged once: `license_id`, `customer`, `expires_at`, `max_people`, `features`, `key_id` (never the key) |
| Expired, within 14 days | Still valid; a warning at every start, with `grace_ends_at` |
| Grace period ends while running | Features off from that moment, one warning, no restart needed |
| Malformed, bad signature, unknown signing key, more than 14 days expired, not yet valid (5 min of clock skew allowed), bound to another instance, a sold license not bound to any | **The server refuses to start**, with the reason: an operator who set a key wants to know |

### Format

```
ostrom-license.v1.<base64url(payload)>.<base64url(signature)>
```

Base64url without padding. The signature is Ed25519 (RFC 8032, pure) over the payload bytes exactly as encoded,
never over a re-serialization. The payload is compact JSON:

```json
{"licenseId":"lic_…","customer":"Acme Corp","issuedAt":"2026-10-30T00:00:00Z","expiresAt":"2027-10-30T00:00:00Z",
 "maxPeople":50,"features":["policies","retention","sso"],"keyId":"5cceefe10dca26f3","tier":"enterprise","trial":false,"instanceId":"…"}
```

- `maxPeople`: `null` for unlimited. `keyId`: 16 hex of SHA-256 of the signing public key's SPKI DER, to pick the
  pinned key.
- `tier` (optional, `enterprise` when absent): the only tier today; any other value is refused. `trial` (optional,
  `false` when absent): a trial license.
- `instanceId` (optional in the format): the `id` of `GET /v1/instance`; the license then only works on that instance,
  and the server refuses to start on another one, with the instance id to send to sales@ostrom.run to have it
  re-issued. **Required for a license that is not a trial**: a sold Enterprise license without it is refused. A trial
  needs none, and is checked like any other when it has one.
- Signing: `sign.py --payload license.json --instance-id <id>` for a sold license, `--trial` for a trial (without
  `--trial`, `--instance-id` is required). The instance id, the tier and the trial flag are never read from the file.
- A field v1 does not define is refused (it could be a restriction a newer issuer added): a new field means `v2`.
- Bounded: a key longer than 8 192 characters is refused before decoding.

### Keys

The trusted public keys are **compiled in** (`PinnedLicenseKeys.kt`), never configurable: a key an operator could
set would let anyone sign their own license. Tests inject theirs through the `LicenseVerifierImpl` constructor.

| Key id | Created | Private key |
| --- | --- | --- |
| `5cceefe10dca26f3` | 30/10/2026 | `~/.config/ostrom/license-signing-ed25519.pem` on the issuer's machine (0600), never in a repository, a log or CI |

Rotation: generate a new key, add its line to `PinnedLicenseKeys.ALL`, release, sign new licenses with it; remove the
old key only once every license it signed has expired (its licenses then fail with "unknown key").

### Signing a license

`sign.py` needs Python 3.10+ and the `cryptography` package (Ed25519 is not in the standard library).

```sh
python3 ee/tools/license/sign.py --generate-keypair      # once; writes the private key 0600, prints the public key
python3 ee/tools/license/sign.py --print-public-key      # the line for PinnedLicenseKeys.kt, and the key id
echo '{"customer":"Acme Corp","expiresAt":"2027-10-30T00:00:00Z","maxPeople":50,"features":["sso","policies","retention"]}' \
  | python3 ee/tools/license/sign.py --payload -         # prints the license key
```

`licenseId` (a UUID) and `issuedAt` (now) are filled in when absent; instants are UTC to the second. The tool refuses
a private key file other users can read, and never prints the private key. Its tests
(`ee/tools/license/test_sign.py`) run in CI under coverage at 100 %, and a key it signed with a test key
(`SIGN_PY_FIXTURE`) is verified by the Kotlin tests: both sides agree byte for byte.
