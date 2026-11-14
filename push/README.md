# Ostrom Push relay

The relay sends the push notifications of the published Ostrom apps. Their Firebase project, and the APNs key behind
it, belong to the relay alone. An Ostrom server that chose the relay signs each request and sends an encrypted blob.
The relay sees an instance, an FCM token and that blob: never a member, a session or the text. The protocol, `push.v1`,
is below; its wire models are the `PushRelay` models of ostrom-core's `api` module, which this module uses as they are.

It is a Kotlin/Native server (`linuxX64`), with no JVM and no database. It depends on ostrom-core's `api` only. FCM is
reached through [flareon](https://github.com/nathanfallet/flareon), with the credentials of the Cloud Run service
account read from the metadata server, so there is no key file. Ported from TeamCode's `push/` (Apache-2.0, see
[NOTICE](../NOTICE)).

## Relay protocol (`push.v1`)

- **Identity.** An instance has an Ed25519 key pair and registers its public key:
  `POST /v1/instances {"publicKey": "<raw 32 bytes, base64url>"}` answers `{"instanceId": "..."}`. The same key gets the
  same id; the relay answers the key itself, so a server must not assume anything about the id's form.
- **Signature.** Every other request carries `Ostrom-Instance`, `Ostrom-Timestamp` (Unix seconds, at most 300 s off) and
  `Ostrom-Signature`: Ed25519 over `POST\n<path>\n<timestamp>\n<sha256 of the body, lowercase hex>`, base64url without
  padding.
- **Send.** `POST /v1/send {"token", "platform": "ios"|"android", "payload", "collapseKey", "ttlSeconds"}` answers
  `{"status": "sent"|"unregistered"|"retry"|"rejected", "retryAfterSeconds"?, "error"?}`.
- **What the provider sees.** The data message carries the encrypted payload (`p`) and its version (`v`). On iOS, the
  generic alert "Ostrom" / "New activity in Ostrom" is shown when the device cannot decrypt it.
- **Limits.** The body is at most 8 KiB, the token at most 512 characters, the payload base64 of at most 3000
  characters, the collapse key lowercase hex, and the TTL at most one day.
- **Outcomes.** `sent` delivers; `unregistered` clears the token; `retry`, 408, 429 and 5xx are retried; `401` (the
  relay no longer knows the key) registers the same key again at the next push; `403` (the instance is blocked) and
  other refusals fail.

## Choices

- **No registry.** An instance's id is its Ed25519 public key (raw, base64url). `POST /v1/instances` checks the key and
  answers it as the id. Any replica can check any signature, and a restart or a redeploy forgets nothing. A Firestore
  registry would add a database, a second credential path and a read per push, for no extra guarantee: registration is
  open by design, since every self-hosted server registers on its own.
- **Manual cut-off.** List an instance id in `OSTROM_PUSH_BLOCKED_INSTANCES` and redeploy: its requests get `403` and
  the server stops retrying.
- **Quotas.** Per minute, there are three: per instance (`OSTROM_PUSH_LIMIT_INSTANCE`, default 600), per token
  (`OSTROM_PUSH_LIMIT_TOKEN`, default 20) and for the whole relay (`OSTROM_PUSH_LIMIT_TOTAL`, default 6000). A request
  counts only when all three accept it; a refusal is `429` with `Retry-After`, and the server retries. With the replay
  guard (a signed request is sent once), they live in memory: the service runs one instance (`maxScale: 1`), far more
  than the apps need.
- **No "token belongs to instance" check.** One phone signed in to two Ostrom servers sends the same FCM token from
  both, so binding a token to the first instance that used it would break that case. The per-token quota bounds what
  another instance can do with a token it learnt. The blob is encrypted for the device, so such a sender can only make
  the generic "New activity in Ostrom" alert appear, at most 20 times a minute.
- **Nothing is logged** about a request: not its token, its payload or its instance. Errors are short codes, and an FCM
  answer is reduced to `sent`, `unregistered` (404), `retry` (401, 408, 429, 5xx, unreachable) or `rejected`.

## Build and check

```sh
./gradlew :push:jvmTest :push:linuxX64Test             # the tests, on the JVM (Kover 100 %) and natively
./gradlew :push:linkReleaseExecutableLinuxX64          # the binary that is deployed
docker build --platform linux/amd64 -f push/Dockerfile -t ostrom-push:dev push
python3 scripts/smoke-push-relay.py ostrom-push:dev    # the real binary: protocol, signatures, limits
```

CI runs the same: the JVM tests with Kover in the `ci-jvm` job, the native tests, the link, the image and the smoke
script in `ci-native`, and `push-image.yml` publishes `docker.io/ostromhq/ostrom-push` (amd64) from main and tags.

## Deploy (Cloud Run)

Not run by any pipeline: [`cloudrun.yaml`](cloudrun.yaml) is the service, and an operator deploys it. To set it up:

1. In the Firebase project of the published apps, create a service account `ostrom-push` with the **Firebase Cloud
   Messaging API Admin** role only, and upload the APNs authentication key in the Firebase console (Cloud Messaging,
   Apple app configuration).
2. Pick the image: CI publishes `docker.io/ostromhq/ostrom-push` (`latest` and `sha-<commit>` from `main`, the version
   from tags; amd64 only). Cloud Run pulls it through an Artifact Registry remote repository of Docker Hub, a cache
   that holds nothing of its own: `gcloud artifacts repositories create dockerhub --repository-format=docker
   --location REGION --mode=remote-repository --remote-docker-repo=DOCKER-HUB`. Pointed at `docker.io` directly, Cloud
   Run fails with "Container import failed". Prefer a `sha-` or version tag to `latest`, so a redeploy is a deliberate
   change.
3. Replace the placeholders in `cloudrun.yaml`, then run `gcloud run services replace push/cloudrun.yaml --region
   REGION` and allow unauthenticated invocations (the relay checks its own signatures).
4. Map the domain: `gcloud beta run domain-mappings create --service ostrom-push --domain <push domain>`, then add the
   CNAME it gives to `ghs.googlehosted.com` at the DNS provider, DNS only (not proxied).

Domain mappings exist only in some Cloud Run regions. The gen2 environment needs at least 512 MiB of memory. Cloud Run's
front end keeps paths ending in `z` for itself: `/healthz` answers the startup and liveness probes inside the service,
but returns Google's 404 from outside. To check a deployed relay from outside, use `POST /v1/instances`: a malformed key
gets the relay's 400.
