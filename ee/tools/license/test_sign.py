# SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
"""Tests of sign.py: python3 -m unittest discover -s ee/tools/license (CI runs them under coverage, 100 %)."""

from __future__ import annotations

import base64
import datetime as dt
import io
import json
import os
import stat
import tempfile
import unittest

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

import sign

NOW = dt.datetime(2026, 10, 30, 9, 30, 0, tzinfo=dt.timezone.utc)
PAYLOAD = {
    "licenseId": "lic_1",
    "customer": "Acme",
    "issuedAt": "2026-10-30T00:00:00Z",
    "expiresAt": "2027-10-30T00:00:00Z",
    "maxPeople": 50,
    "features": ["sso", "retention", "sso"],
}


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class SignToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.key = os.path.join(self.directory.name, "nested", "key.pem")

    def tearDown(self) -> None:
        self.directory.cleanup()

    def run_tool(self, *args: str, stdin: str = "") -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        code = sign.main(list(args) + ["--key", self.key], stdin=io.StringIO(stdin), stdout=out, stderr=err, now=lambda: NOW)
        return code, out.getvalue(), err.getvalue()

    def payload_file(self, payload: object) -> str:
        path = os.path.join(self.directory.name, "payload.json")
        with open(path, "w", encoding="utf-8") as file:
            file.write(payload if isinstance(payload, str) else json.dumps(payload))
        return path

    def generate(self) -> str:
        code, out, err = self.run_tool("--generate-keypair")
        self.assertEqual(0, code, err)
        return out

    def test_generate_keypair_writes_a_private_file_and_prints_only_the_public_key(self) -> None:
        out = self.generate()
        self.assertEqual(0o600, stat.S_IMODE(os.stat(self.key).st_mode))
        with open(self.key, "rb") as file:
            pem = file.read()
        self.assertTrue(pem.startswith(b"-----BEGIN PRIVATE KEY-----"))
        self.assertNotIn("PRIVATE", out)
        self.assertRegex(out, r"public key \(SPKI DER, base64\): MCowBQYDK2VwAyEA\S{44}\nkey id: [0-9a-f]{16}\n")
        code, again, _ = self.run_tool("--print-public-key")
        self.assertEqual((0, out), (code, again))

    def test_generate_keypair_never_overwrites(self) -> None:
        self.generate()
        code, _, err = self.run_tool("--generate-keypair")
        self.assertEqual(2, code)
        self.assertIn("already exists; refusing to overwrite", err)

    def test_generate_keypair_in_the_current_directory(self) -> None:
        previous = os.getcwd()
        os.chdir(self.directory.name)
        try:
            self.key = "local.pem"
            self.generate()
            self.assertTrue(os.path.exists(os.path.join(self.directory.name, "local.pem")))
        finally:
            os.chdir(previous)

    def test_a_signed_license_verifies_with_the_public_key(self) -> None:
        out = self.generate()
        code, license_key, err = self.run_tool("--payload", self.payload_file(PAYLOAD))
        self.assertEqual(0, code, err)
        prefix, version, payload, signature = license_key.strip().split(".")
        self.assertEqual(("ostrom-license", "v1"), (prefix, version))
        data = b64url_decode(payload)
        key_id = out.split("key id: ")[1].strip()
        self.assertEqual(
            {
                "licenseId": "lic_1",
                "customer": "Acme",
                "issuedAt": "2026-10-30T00:00:00Z",
                "expiresAt": "2027-10-30T00:00:00Z",
                "maxPeople": 50,
                "features": ["retention", "sso"],
                "keyId": key_id,
            },
            json.loads(data),
        )
        self.assertNotIn(b" ", data, "compact JSON")
        public = base64.b64decode(out.split("base64): ")[1].split("\n")[0])
        key = serialization.load_der_public_key(public)
        assert isinstance(key, Ed25519PublicKey)
        key.verify(b64url_decode(signature), data)

    def test_defaults_and_instance_binding(self) -> None:
        self.generate()
        payload = {"customer": "Acme", "expiresAt": "2027-01-01T00:00:00Z", "instanceId": "6f1c2c47"}
        code, license_key, err = self.run_tool("--payload", "-", stdin=json.dumps(payload))
        self.assertEqual(0, code, err)
        decoded = json.loads(b64url_decode(license_key.strip().split(".")[2]))
        self.assertEqual("2026-10-30T09:30:00Z", decoded["issuedAt"])
        self.assertRegex(decoded["licenseId"], r"^[0-9a-f-]{36}$")
        self.assertEqual([], decoded["features"])
        self.assertIsNone(decoded["maxPeople"])
        self.assertEqual("6f1c2c47", decoded["instanceId"])

    def test_invalid_payloads_are_refused(self) -> None:
        self.generate()
        cases = {
            "not json": "The payload is not JSON",
            "[]": "must be a JSON object",
            json.dumps({**PAYLOAD, "seats": 3}): "Unknown payload fields: seats.",
            json.dumps({**PAYLOAD, "customer": " "}): "customer is required.",
            json.dumps({**PAYLOAD, "licenseId": 5}): "licenseId must be a non-empty string",
            json.dumps({**PAYLOAD, "licenseId": ""}): "licenseId must be a non-empty string",
            json.dumps({**PAYLOAD, "issuedAt": "2026-10-30"}): "issuedAt must be a UTC instant",
            json.dumps({**PAYLOAD, "expiresAt": 2027}): "expiresAt must be a UTC instant",
            json.dumps({**PAYLOAD, "expiresAt": "2027-02-30T00:00:00Z"}): "expiresAt is not a valid date",
            json.dumps({**PAYLOAD, "expiresAt": "2026-10-30T00:00:00Z"}): "expiresAt must be after issuedAt.",
            json.dumps({**PAYLOAD, "maxPeople": 0}): "maxPeople must be a positive integer",
            json.dumps({**PAYLOAD, "maxPeople": True}): "maxPeople must be a positive integer",
            json.dumps({**PAYLOAD, "maxPeople": "5"}): "maxPeople must be a positive integer",
            json.dumps({**PAYLOAD, "features": "sso"}): "features must be a list",
            json.dumps({**PAYLOAD, "features": ["sso", ""]}): "features must be a list",
            json.dumps({**PAYLOAD, "instanceId": " "}): "instanceId must be a non-empty string",
            json.dumps({**PAYLOAD, "instanceId": 3}): "instanceId must be a non-empty string",
        }
        for text, message in cases.items():
            with self.subTest(text=text):
                code, out, err = self.run_tool("--payload", "-", stdin=text)
                self.assertEqual((2, ""), (code, out))
                self.assertIn(message, err)

    def test_a_missing_payload_file_is_refused(self) -> None:
        self.generate()
        code, _, err = self.run_tool("--payload", os.path.join(self.directory.name, "absent.json"))
        self.assertEqual(2, code)
        self.assertIn("Cannot read", err)

    def test_the_private_key_must_exist_be_private_and_be_ed25519(self) -> None:
        code, _, err = self.run_tool("--print-public-key")
        self.assertEqual(2, code)
        self.assertIn("does not exist; create it with --generate-keypair", err)

        self.generate()
        os.chmod(self.key, 0o644)
        code, _, err = self.run_tool("--print-public-key")
        self.assertEqual(2, code)
        self.assertIn("is readable by other users (mode 644)", err)

        os.chmod(self.key, 0o600)
        with open(self.key, "wb") as file:
            file.write(b"garbage")
        code, _, err = self.run_tool("--print-public-key")
        self.assertEqual(2, code)
        self.assertIn("is not an unencrypted PEM private key", err)

        other = ec.generate_private_key(ec.SECP256R1())
        with open(self.key, "wb") as file:
            file.write(other.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        code, _, err = self.run_tool("--print-public-key")
        self.assertEqual(2, code)
        self.assertIn("is not an Ed25519 private key", err)

    def test_the_default_clock_is_utc_now(self) -> None:
        out = io.StringIO()
        self.generate()
        code = sign.main(
            ["--key", self.key, "--payload", "-"],
            stdin=io.StringIO(json.dumps({"customer": "Acme", "expiresAt": "2999-01-01T00:00:00Z"})),
            stdout=out,
            stderr=io.StringIO(),
        )
        self.assertEqual(0, code)
        issued = json.loads(b64url_decode(out.getvalue().strip().split(".")[2]))["issuedAt"]
        delta = abs(dt.datetime.now(dt.timezone.utc) - dt.datetime.strptime(issued, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc))
        self.assertLess(delta, dt.timedelta(minutes=1))


if __name__ == "__main__":
    unittest.main()
