"""Integration test against stripe-mock (Stripe's official mock, validated against Stripe's OpenAPI): every request
the provisioning sends must be accepted as Stripe would accept it. Required in CI (STRIPE_MOCK_REQUIRED=true)."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision  # noqa: E402

URL = os.environ.get("STRIPE_MOCK_URL")

pytestmark = pytest.mark.skipif(
    not URL and os.environ.get("STRIPE_MOCK_REQUIRED") != "true",
    reason="STRIPE_MOCK_URL not set (CI sets it and makes this test required)",
)


def test_everyProvisioningRequestIsAcceptedByStripeMock(tmp_path: Path) -> None:
    assert URL, "STRIPE_MOCK_URL is required in CI"
    catalog = provision.json.loads((Path(provision.__file__).parent / "catalog.json").read_text())
    api = provision.StripeApi("sk_test_123", catalog["apiVersion"], URL)
    secrets: list[str] = []
    # stripe-mock answers fixtures, not state: the product lookup succeeds with a fixture, so every product is
    # "updated", and lists are fixtures too. What matters is that no request is refused (400).
    report = provision.Provisioner(api, catalog, False, secrets.append).run()
    assert report.ids["ostrom_team"] == "ostrom_team"
    assert report.changes, "stripe-mock fixtures differ from the catalog, so changes are sent and validated"
