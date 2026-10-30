#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
"""Signs Ostrom Enterprise license keys, offline, with the Ed25519 license signing key.

A license key is `ostrom-license.v1.<base64url(payload)>.<base64url(signature)>`: the payload is compact JSON, the
signature is Ed25519 (RFC 8032, pure) over the payload bytes exactly as encoded. The server verifies it with the
public keys compiled into `ee/modules/license` (`PinnedLicenseKeys.kt`); see `ee/README.md`.

    sign.py --generate-keypair                 # writes the private key (0600), prints the public key only
    sign.py --print-public-key                 # the public key and key id of the private key
    sign.py --payload license.json             # prints the license key ("-" reads the payload from stdin)

The private key defaults to ~/.config/ostrom/license-signing-ed25519.pem (`--key` to change). It is never printed,
never logged and never committed. Needs the `cryptography` package (Ed25519 is not in the standard library).
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import stat
import sys
import uuid
from typing import Any, Callable, Optional, TextIO

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

PREFIX = "ostrom-license.v1."
DEFAULT_KEY = os.path.join("~", ".config", "ostrom", "license-signing-ed25519.pem")
# Fixed width, so two instants compare as strings in time order.
INSTANT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
ALLOWED_FIELDS = {"licenseId", "customer", "issuedAt", "expiresAt", "maxPeople", "features", "instanceId"}


class LicenseToolError(Exception):
    """A refusal the operator can fix; printed on one line, exit code 2."""


def b64url(data: bytes) -> str:
    """Base64url without padding, the encoding of both parts of a license key."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def public_key_der(private_key: Ed25519PrivateKey) -> bytes:
    """The public key as DER SubjectPublicKeyInfo, the form compiled into the server."""
    return private_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )


def key_id(der: bytes) -> str:
    """16 hex characters of SHA-256 of the SPKI DER: the same key id as the instance signing keys."""
    return hashlib.sha256(der).hexdigest()[:16]


def generate_keypair(path: str) -> Ed25519PrivateKey:
    """Writes a new private key in PKCS#8 PEM, mode 0600, refusing to overwrite an existing file."""
    if os.path.exists(path):
        raise LicenseToolError(f"{path} already exists; refusing to overwrite a signing key.")
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, mode=0o700, exist_ok=True)
    private_key = Ed25519PrivateKey.generate()
    pem = private_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )
    # O_EXCL: never replace a key another run created meanwhile; 0600 from the first byte, never readable by others.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as file:
        file.write(pem)
    return private_key


def load_private_key(path: str) -> Ed25519PrivateKey:
    """Reads the private key, refusing a file other users can read."""
    if not os.path.exists(path):
        raise LicenseToolError(f"{path} does not exist; create it with --generate-keypair.")
    mode = stat.S_IMODE(os.stat(path).st_mode)
    if mode & 0o077:
        raise LicenseToolError(f"{path} is readable by other users (mode {mode:o}); run chmod 600 on it.")
    with open(path, "rb") as file:
        data = file.read()
    try:
        key = serialization.load_pem_private_key(data, password=None)
    except (ValueError, TypeError):
        raise LicenseToolError(f"{path} is not an unencrypted PEM private key.") from None
    if not isinstance(key, Ed25519PrivateKey):
        raise LicenseToolError(f"{path} is not an Ed25519 private key.")
    return key


def parse_instant(value: Any, name: str) -> str:
    """Checks a UTC instant to the second (`2026-10-30T00:00:00Z`), the one form both sides parse, and returns it."""
    if not isinstance(value, str) or not INSTANT.fullmatch(value):
        raise LicenseToolError(f"{name} must be a UTC instant such as 2026-10-30T00:00:00Z.")
    try:
        dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        raise LicenseToolError(f"{name} is not a valid date: {value}.") from None
    return value


def normalize_payload(raw: Any, signing_key_id: str, now: Callable[[], dt.datetime]) -> dict:
    """Validates the operator's payload and returns it in the field order the server documents."""
    if not isinstance(raw, dict):
        raise LicenseToolError("The payload must be a JSON object.")
    unknown = sorted(set(raw) - ALLOWED_FIELDS)
    if unknown:
        raise LicenseToolError(f"Unknown payload fields: {', '.join(unknown)}.")
    customer = raw.get("customer")
    if not isinstance(customer, str) or not customer.strip():
        raise LicenseToolError("customer is required.")
    license_id = raw.get("licenseId", str(uuid.uuid4()))
    if not isinstance(license_id, str) or not license_id.strip():
        raise LicenseToolError("licenseId must be a non-empty string (omit it to generate one).")
    issued_at = raw.get("issuedAt") or now().strftime("%Y-%m-%dT%H:%M:%SZ")
    parse_instant(issued_at, "issuedAt")
    expires_at = parse_instant(raw.get("expiresAt"), "expiresAt")
    if expires_at <= issued_at:
        raise LicenseToolError("expiresAt must be after issuedAt.")
    max_people = raw.get("maxPeople")
    if max_people is not None and (isinstance(max_people, bool) or not isinstance(max_people, int) or max_people < 1):
        raise LicenseToolError("maxPeople must be a positive integer, or null for unlimited.")
    features = raw.get("features", [])
    if not isinstance(features, list) or not all(isinstance(f, str) and f.strip() for f in features):
        raise LicenseToolError("features must be a list of non-empty strings.")
    instance_id = raw.get("instanceId")
    if instance_id is not None and (not isinstance(instance_id, str) or not instance_id.strip()):
        raise LicenseToolError("instanceId must be a non-empty string when set.")
    payload = {
        "licenseId": license_id,
        "customer": customer,
        "issuedAt": issued_at,
        "expiresAt": expires_at,
        "maxPeople": max_people,
        "features": sorted(set(features)),
        "keyId": signing_key_id,
    }
    if instance_id is not None:
        payload["instanceId"] = instance_id
    return payload


def read_payload(path: str) -> str:
    """Reads the payload file."""
    try:
        with open(path, encoding="utf-8") as file:
            return file.read()
    except OSError as error:
        raise LicenseToolError(f"Cannot read {path}: {error.strerror}.") from None


def sign_license(payload: dict, private_key: Ed25519PrivateKey) -> str:
    """Encodes the payload once and signs exactly those bytes."""
    data = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return PREFIX + b64url(data) + "." + b64url(private_key.sign(data))


def main(
    argv: Optional[list] = None,
    stdin: TextIO = sys.stdin,
    stdout: TextIO = sys.stdout,
    stderr: TextIO = sys.stderr,
    now: Callable[[], dt.datetime] = lambda: dt.datetime.now(dt.timezone.utc),
) -> int:
    """Runs the tool; returns the exit code (0, or 2 on a refusal)."""
    parser = argparse.ArgumentParser(description="Sign Ostrom Enterprise license keys.")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--generate-keypair", action="store_true", help="create the private key (0600)")
    mode.add_argument("--print-public-key", action="store_true", help="print the public key and key id")
    mode.add_argument("--payload", metavar="FILE", help="sign this JSON payload ('-' for stdin)")
    parser.add_argument("--key", default=DEFAULT_KEY, help=f"the private key (default {DEFAULT_KEY})")
    args = parser.parse_args(argv)
    path = os.path.expanduser(args.key)
    try:
        if args.generate_keypair:
            der = public_key_der(generate_keypair(path))
            print(f"private key written to {path} (mode 0600)", file=stderr)
            print(f"public key (SPKI DER, base64): {base64.b64encode(der).decode('ascii')}", file=stdout)
            print(f"key id: {key_id(der)}", file=stdout)
        elif args.print_public_key:
            der = public_key_der(load_private_key(path))
            print(f"public key (SPKI DER, base64): {base64.b64encode(der).decode('ascii')}", file=stdout)
            print(f"key id: {key_id(der)}", file=stdout)
        else:
            private_key = load_private_key(path)
            text = stdin.read() if args.payload == "-" else read_payload(args.payload)
            try:
                raw = json.loads(text)
            except json.JSONDecodeError as error:
                raise LicenseToolError(f"The payload is not JSON: {error.msg}.") from None
            payload = normalize_payload(raw, key_id(public_key_der(private_key)), now)
            print(sign_license(payload, private_key), file=stdout)
    except LicenseToolError as error:
        print(f"error: {error}", file=stderr)
        return 2
    return 0


if __name__ == "__main__":  # pragma: no cover - the entry point only forwards to main()
    sys.exit(main())
