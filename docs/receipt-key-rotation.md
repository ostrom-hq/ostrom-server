# Rotating the receipt signing key

The instance signs receipts and compiled packages with one Ed25519 key, `OSTROM_SIGNING_KEY_FILE`. Every key it has
ever signed with stays listed by `GET /api/v1/receipts/keys` (table `instance_signing_keys`, never emptied), so a
receipt signed before a change still verifies.

The phone app pins the keys of a server the first time it sees them (app#33, S2-18). It trusts a new key only if a key
it already trusts handed it over. That hand-over is the `rotation` field of the new key:

```json
{
  "kid": "9f2c…",
  "alg": "ed25519",
  "publicKeyPem": "-----BEGIN PUBLIC KEY-----…",
  "rotation": { "kid": "41ab…", "signature": "base64…" }
}
```

- `rotation.kid` is the id of the key that the new key replaces.
- `rotation.signature` is that key's Ed25519 signature, in base64, over the UTF-8 text
  `ostrom-receipt-key-rotation/v1\n` followed by the lowercase hex of the new key's 32 raw bytes.

A key changed without its previous key has no `rotation`. The server still lists it and `POST /receipts/verify`
accepts it, but a phone shows its receipts as signed by an unknown key.

## Procedure

1. **Make the new key.** It is a PKCS#8 PEM file, mode 0600:

   ```sh
   openssl genpkey -algorithm ed25519 -out instance-key-2.pem
   chmod 600 instance-key-2.pem
   ```

   Make it with `openssl` and mount it. Do not let the server create it during a rotation: a mistyped
   `OSTROM_SIGNING_KEY_FILE` would silently create yet another key, and replicas without a shared volume would each
   create their own.

2. **Keep the current key file.** It becomes the previous key. Configure both files:

   ```sh
   OSTROM_SIGNING_KEY_FILE=/data/instance-key-2.pem
   OSTROM_PREVIOUS_SIGNING_KEY_FILE=/data/instance-key.pem
   ```

   With Helm, mount the two keys as two secrets.

3. **Restart every replica.** At start, the server:
   - records the new key;
   - signs the rotation with the previous key;
   - stores the rotation with the new key.

   During a rolling restart, the replicas that still run the old key keep signing with it. Both keys verify.

   A replica that starts on the new key without `OSTROM_PREVIOUS_SIGNING_KEY_FILE` records the key with no rotation and
   fixes nothing. The first start that names the previous key fills it in. A forgotten variable is mended by
   restarting with it.

4. **Check the hand-over.** `GET /api/v1/receipts/keys` lists the new key last, with `rotation.kid` set to the previous
   key's id.

   The server records no rotation, and logs a warning `The previous signing key hands nothing over`, when:
   - the previous key is not a key this instance signed with: a key from another instance never vouches for this one;
   - the previous key is the current key itself.

   A missing previous key file stops the start with a configuration error.

5. **Remove `OSTROM_PREVIOUS_SIGNING_KEY_FILE`.** The rotation is recorded once and never changes, so later starts do
   not need the previous key. Keep the previous private key offline, or destroy it: the server only needs its public
   key, which stays listed.

Rotate again the same way: each new key is handed over by the key just before it. A phone that skipped a step follows
the chain, because every key and its rotation stay listed. The server only checks that it signed with the previous key
before, not that this key was the latest: handing over from an older key works, and leaves the key in between without
a successor.

## A leaked key

A rotation proves only that the holder of the previous key agreed to the new one. During a rotation, the previous
private key sits on the server's disk: step 5 removes it. If the previous key leaked, whoever
holds it can also hand over a key of their own. Then a rotation does not restore trust: the phones must forget the
server's pinned keys and pin them again, and the leak must be announced to the organizations of the instance.
