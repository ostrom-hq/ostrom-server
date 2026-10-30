"""Unit tests of the Stripe provisioning: a stateful fake Stripe proves idempotency, a local HTTP server proves the
wire format (auth, version, form encoding, errors). The stripe-mock integration test lives in test_stripe_mock.py."""
from __future__ import annotations

import copy
import http.server
import json
import os
import stat
import sys
import threading
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import provision  # noqa: E402

CATALOG = json.loads((Path(provision.__file__).parent / "catalog.json").read_text())


class FakeStripe(provision.StripeApi):
    """An in-memory Stripe holding just what the provisioning reads and writes."""

    def __init__(self) -> None:
        super().__init__("sk_test_fake", CATALOG["apiVersion"], "http://unused")
        self.tax_settings: dict[str, Any] = {"status": "pending", "head_office": None, "defaults": {}}
        self.registrations: list[dict[str, Any]] = []
        self.meters: list[dict[str, Any]] = []
        self.products: dict[str, dict[str, Any]] = {}
        self.prices: list[dict[str, Any]] = []
        self.portals: list[dict[str, Any]] = []
        self.webhooks: list[dict[str, Any]] = []
        self.writes: list[tuple[str, str]] = []
        self.page_size = 100
        self.counter = 0

    def next_id(self, prefix: str) -> str:
        self.counter += 1
        return f"{prefix}_{self.counter}"

    def page(self, items: list[dict[str, Any]], params: dict[str, Any]) -> dict[str, Any]:
        start = 0
        if "starting_after" in params:
            start = [item["id"] for item in items].index(params["starting_after"]) + 1
        chunk = items[start : start + self.page_size]
        return {"object": "list", "data": chunk, "has_more": start + self.page_size < len(items)}

    def call(self, method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        params = params or {}
        if method == "POST":
            self.writes.append((method, path))
        if path == "/v1/tax/settings":
            if method == "POST":
                self.tax_settings = {"status": "active", "head_office": params["head_office"], "defaults": params["defaults"]}
            return self.tax_settings
        if path == "/v1/tax/registrations":
            if method == "POST":
                self.registrations.append({"id": self.next_id("taxreg"), "country": params["country"], "status": "active"})
                return self.registrations[-1]
            return self.page([r for r in self.registrations if r["status"] == params["status"]], params)
        if path == "/v1/billing/meters":
            if method == "POST":
                self.meters.append(dict(params, id=self.next_id("mtr"), status="active"))
                return self.meters[-1]
            return self.page(self.meters, params)
        if path == "/v1/products":
            self.products[params["id"]] = dict(params, active=True)
            return self.products[params["id"]]
        if path.startswith("/v1/products/"):
            product_id = path.rsplit("/", 1)[1]
            if product_id not in self.products:
                raise provision.StripeError(404, "No such product")
            if method == "POST":
                self.products[product_id].update(params)
            return self.products[product_id]
        if path == "/v1/prices":
            if method == "POST":
                for price in self.prices:
                    if price["lookup_key"] == params["lookup_key"]:
                        price["lookup_key"] = None
                self.prices.append(dict(copy.deepcopy(params), id=self.next_id("price"), active=True))
                return self.prices[-1]
            return self.page([p for p in self.prices if p["active"] and p["lookup_key"] in params["lookup_keys"]], params)
        if path.startswith("/v1/prices/"):
            price = next(p for p in self.prices if p["id"] == path.rsplit("/", 1)[1])
            price.update(params)
            return price
        if path == "/v1/billing_portal/configurations":
            if method == "POST":
                self.portals.append(dict(copy.deepcopy(params), id=self.next_id("bpc"), active=True))
                return self.portals[-1]
            return self.page(self.portals, params)
        if path.startswith("/v1/billing_portal/configurations/"):
            portal = next(p for p in self.portals if p["id"] == path.rsplit("/", 1)[1])
            portal.update(copy.deepcopy(params))
            return portal
        if path == "/v1/webhook_endpoints":
            if method == "POST":
                self.webhooks.append(
                    {"id": self.next_id("we"), "url": params["url"], "enabled_events": params["enabled_events"],
                     "status": "enabled", "metadata": params["metadata"], "secret": "whsec_fake"}
                )
                return self.webhooks[-1]
            return self.page(self.webhooks, params)
        if path.startswith("/v1/webhook_endpoints/"):
            endpoint = next(w for w in self.webhooks if w["id"] == path.rsplit("/", 1)[1])
            endpoint.update(url=params["url"], enabled_events=params["enabled_events"], status="enabled")
            return endpoint
        raise AssertionError(f"unexpected call {method} {path}")


def provision_with(fake: FakeStripe, dry_run: bool = False, catalog: dict[str, Any] | None = None) -> tuple[provision.Report, list[str]]:
    secrets: list[str] = []
    report = provision.Provisioner(fake, catalog or CATALOG, dry_run, secrets.append).run()
    return report, secrets


def test_aFirstRunCreatesEverythingAndSavesTheWebhookSecret() -> None:
    fake = FakeStripe()
    report, secrets = provision_with(fake)
    assert len(report.changes) == 12, "tax settings, FR, meter, 3 products, 4 prices, portal, webhook"
    assert secrets == ["whsec_fake"]
    assert report.ids["ostrom_team"] == "ostrom_team"
    assert fake.tax_settings["defaults"] == {"tax_behavior": "exclusive", "tax_code": "txcd_10103001"}
    team_monthly = next(p for p in fake.prices if p["lookup_key"] == "ostrom_team_monthly")
    assert team_monthly["unit_amount"] == 3500
    assert team_monthly["recurring"] == {"interval": "month", "usage_type": "metered", "meter": "mtr_2"}
    business_yearly = next(p for p in fake.prices if p["lookup_key"] == "ostrom_business_yearly")
    assert business_yearly["unit_amount"] == 62400
    assert business_yearly["recurring"] == {"interval": "year", "usage_type": "licensed"}
    assert fake.portals[0]["features"]["subscription_update"] == {"enabled": False}


def test_aSecondRunChangesNothing() -> None:
    fake = FakeStripe()
    first, _ = provision_with(fake)
    writes = len(fake.writes)
    second, secrets = provision_with(fake)
    assert second.changes == []
    assert secrets == []
    assert len(fake.writes) == writes
    assert second.ids == first.ids | {"taxSettingsStatus": "active"}


def test_aDryRunWritesNothing() -> None:
    fake = FakeStripe()
    report, secrets = provision_with(fake, dry_run=True)
    assert fake.writes == []
    assert secrets == []
    assert len(report.changes) == 12
    assert report.ids["meter"] is None
    assert report.ids["ostrom_team_monthly"] is None
    assert report.ids["portalConfiguration"] is None
    assert report.ids["webhookEndpoint"] is None


def test_aChangedAmountReplacesThePriceAndArchivesTheOldOne() -> None:
    fake = FakeStripe()
    first, _ = provision_with(fake)
    catalog = copy.deepcopy(CATALOG)
    catalog["prices"][0]["unitAmount"] = 3900
    report, _ = provision_with(fake, catalog=catalog)
    assert report.changes == [
        "replace the price ostrom_team_monthly",
        f"archive the previous price {first.ids['ostrom_team_monthly']}",
    ]
    old = next(p for p in fake.prices if p["id"] == first.ids["ostrom_team_monthly"])
    assert old["active"] is False
    assert report.ids["ostrom_team_monthly"] != first.ids["ostrom_team_monthly"]


@pytest.mark.parametrize(
    "field,value",
    [("product", "ostrom_business"), ("currency", "eur"), ("tax_behavior", "inclusive")],
)
def test_anyPriceDriftReplacesThePrice(field: str, value: str) -> None:
    fake = FakeStripe()
    provision_with(fake)
    fake.prices[0][field] = value
    report, _ = provision_with(fake)
    assert report.changes[0] == "replace the price ostrom_team_monthly"


@pytest.mark.parametrize("field,value", [("interval", "year"), ("usage_type", "licensed"), ("meter", "mtr_other")])
def test_aRecurringDriftReplacesThePrice(field: str, value: str) -> None:
    fake = FakeStripe()
    provision_with(fake)
    fake.prices[0]["recurring"][field] = value
    report, _ = provision_with(fake)
    assert report.changes[0] == "replace the price ostrom_team_monthly"


def test_aPriceWithoutRecurringIsReplaced() -> None:
    fake = FakeStripe()
    provision_with(fake)
    fake.prices[0]["recurring"] = None
    report, _ = provision_with(fake)
    assert report.changes[0] == "replace the price ostrom_team_monthly"


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p.update(name="Old"),
        lambda p: p.update(description="Old"),
        lambda p: p.update(tax_code="txcd_00000000"),
        lambda p: p.update(metadata={}),
        lambda p: p.update(metadata=None),
        lambda p: p.update(active=False),
    ],
)
def test_aProductDriftUpdatesTheProduct(change: Any) -> None:
    fake = FakeStripe()
    provision_with(fake)
    change(fake.products["ostrom_business"])
    report, _ = provision_with(fake)
    assert report.changes == ["update the product ostrom_business"]
    assert fake.products["ostrom_business"]["active"] is True


def test_aProductReadErrorOtherThanNotFoundStops() -> None:
    class Broken(FakeStripe):
        def call(self, method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
            if path.startswith("/v1/products/"):
                raise provision.StripeError(500, "boom")
            return super().call(method, path, params)

    with pytest.raises(provision.StripeError, match="500"):
        provision_with(Broken())


@pytest.mark.parametrize(
    "settings",
    [
        {"status": "active", "head_office": {"address": {**CATALOG["headOffice"], "city": "Lyon"}}, "defaults": {"tax_behavior": "exclusive", "tax_code": "txcd_10103001"}},
        {"status": "active", "head_office": {"address": CATALOG["headOffice"]}, "defaults": {"tax_behavior": "inclusive", "tax_code": "txcd_10103001"}},
        {"status": "active", "head_office": {"address": CATALOG["headOffice"]}, "defaults": {"tax_behavior": "exclusive", "tax_code": "txcd_x"}},
        {"status": "active", "head_office": {"address": None}, "defaults": None},
    ],
)
def test_taxSettingsDriftIsCorrected(settings: dict[str, Any]) -> None:
    fake = FakeStripe()
    provision_with(fake)
    fake.tax_settings = settings
    report, _ = provision_with(fake)
    assert report.changes == ["update the Stripe Tax settings (head office, exclusive prices, tax code)"]


def test_aRegistrationWithoutOptionsSendsNone() -> None:
    fake = FakeStripe()
    catalog = copy.deepcopy(CATALOG)
    catalog["taxRegistrations"] = [{"country": "US", "type": "state_sales_tax"}]
    calls: list[dict[str, Any]] = []
    original = fake.call

    def spy(method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        if path == "/v1/tax/registrations" and method == "POST":
            calls.append(params or {})
        return original(method, path, params)

    fake.call = spy  # type: ignore[method-assign]
    provision_with(fake, catalog=catalog)
    assert calls[0]["country_options"] == {"us": {"type": "state_sales_tax", "state_sales_tax": None}}


@pytest.mark.parametrize(
    "change",
    [
        lambda c: c["business_profile"].update(headline="Old"),
        lambda c: c.update(business_profile=None),
        lambda c: c.update(default_return_url="https://old"),
        lambda c: c["features"]["invoice_history"].update(enabled=False),
        lambda c: c["features"].update(payment_method_update=None),
        lambda c: c["features"]["subscription_cancel"].update(enabled=False),
        lambda c: c["features"]["subscription_cancel"].update(mode="immediately"),
        lambda c: c["features"].update(subscription_cancel=None),
        lambda c: c["features"]["subscription_update"].update(enabled=True),
        lambda c: c["features"].update(subscription_update=None),
        lambda c: c.update(features=None),
    ],
)
def test_aPortalDriftUpdatesTheSameConfiguration(change: Any) -> None:
    fake = FakeStripe()
    first, _ = provision_with(fake)
    change(fake.portals[0])
    report, _ = provision_with(fake)
    assert report.changes == [f"update the portal configuration {first.ids['portalConfiguration']}"]
    assert len(fake.portals) == 1


def test_aPortalNotOursIsIgnored() -> None:
    fake = FakeStripe()
    fake.portals.append({"id": "bpc_other", "metadata": None, "active": True})
    provision_with(fake)
    assert [p["id"] for p in fake.portals] == ["bpc_other", "bpc_7"]


@pytest.mark.parametrize(
    "change",
    [
        lambda w: w.update(url="https://old.example/webhook"),
        lambda w: w.update(enabled_events=["invoice.paid"]),
        lambda w: w.update(enabled_events=None),
        lambda w: w.update(status="disabled"),
    ],
)
def test_aWebhookDriftUpdatesTheEndpointWithoutANewSecret(change: Any) -> None:
    fake = FakeStripe()
    first, _ = provision_with(fake)
    change(fake.webhooks[0])
    report, secrets = provision_with(fake)
    assert report.changes == [f"update the webhook endpoint {first.ids['webhookEndpoint']}"]
    assert secrets == []
    assert fake.webhooks[0]["url"] == CATALOG["webhook"]["url"]


def test_aWebhookUrlOverrideIsApplied() -> None:
    fake = FakeStripe()
    secrets: list[str] = []
    provision.Provisioner(fake, CATALOG, False, secrets.append, "https://staging.example/v1/billing/stripe/webhook").run()
    assert fake.webhooks[0]["url"] == "https://staging.example/v1/billing/stripe/webhook"


def test_endpointsAndMetersNotOursAreIgnored() -> None:
    fake = FakeStripe()
    fake.webhooks.append({"id": "we_other", "url": "https://x", "enabled_events": [], "status": "enabled", "metadata": None})
    fake.meters.append({"id": "mtr_other", "event_name": "something_else", "status": "active"})
    report, _ = provision_with(fake)
    assert report.ids["webhookEndpoint"] != "we_other"
    assert report.ids["meter"] != "mtr_other"


def test_listsAreReadAcrossPages() -> None:
    fake = FakeStripe()
    fake.page_size = 1
    fake.webhooks += [
        {"id": f"we_other{i}", "url": "https://x", "enabled_events": [], "status": "enabled", "metadata": {}}
        for i in range(3)
    ]
    report, _ = provision_with(fake)
    assert report.ids["webhookEndpoint"] == "we_8"
    again, _ = provision_with(fake)
    assert again.changes == []


def test_anEmptyPageWithMoreStopsTheListing() -> None:
    class Odd(FakeStripe):
        def call(self, method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
            if path == "/v1/webhook_endpoints" and method == "GET":
                return {"data": [], "has_more": True}
            return super().call(method, path, params)

    assert Odd().list_all("/v1/webhook_endpoints") == []


def test_encodeFlattensNestedParams() -> None:
    assert provision.encode(
        {"a": {"b": [1, {"c": True}], "d": False, "e": None}, "f": "x", "g": ["y"]}
    ) == [("a[b][0]", "1"), ("a[b][1][c]", "true"), ("a[d]", "false"), ("f", "x"), ("g[0]", "y")]


class Handler(http.server.BaseHTTPRequestHandler):
    """Answers what the test queued, and records each request."""

    responses: list[tuple[int, bytes]] = []
    seen: list[dict[str, Any]] = []

    def handle_one(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        Handler.seen.append(
            {"method": self.command, "path": self.path, "body": self.rfile.read(length).decode(), "headers": dict(self.headers)}
        )
        status, body = Handler.responses.pop(0)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    do_GET = handle_one
    do_POST = handle_one

    def log_message(self, *args: Any) -> None:
        return


@pytest.fixture()
def server() -> Any:
    Handler.responses, Handler.seen = [], []
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def test_theClientSendsAuthVersionAndForm(server: str) -> None:
    Handler.responses = [(200, b'{"id":"x"}'), (200, b'{"data":[],"has_more":false}'), (200, b'{"ok":true}')]
    api = provision.StripeApi("sk_test_abc", "2026-09-30.endive", server + "/")
    assert api.call("POST", "/v1/products", {"id": "p", "metadata": {"k": "v"}}) == {"id": "x"}
    assert api.call("GET", "/v1/prices", {"lookup_keys": ["a", "b"]}) == {"data": [], "has_more": False}
    assert api.call("GET", "/v1/tax/settings") == {"ok": True}
    post, get, bare = Handler.seen
    assert post["headers"]["Authorization"] == "Basic c2tfdGVzdF9hYmM6"
    assert post["headers"]["Stripe-Version"] == "2026-09-30.endive"
    assert post["body"] == "id=p&metadata%5Bk%5D=v"
    assert get["path"] == "/v1/prices?lookup_keys%5B0%5D=a&lookup_keys%5B1%5D=b"
    assert bare["path"] == "/v1/tax/settings"


@pytest.mark.parametrize(
    "body,message",
    [
        (b'{"error":{"message":"No such price"}}', "Stripe answered 404: No such price"),
        (b"<html>", "Stripe answered 404: unreadable error body (6 bytes)"),
        (b'{"error":"flat"}', "Stripe answered 404: unreadable error body (16 bytes)"),
    ],
)
def test_aRefusalBecomesAStripeError(server: str, body: bytes, message: str) -> None:
    Handler.responses = [(404, body)]
    with pytest.raises(provision.StripeError) as raised:
        provision.StripeApi("sk_test_abc", "v", server).call("GET", "/v1/prices/x")
    assert str(raised.value) == message
    assert raised.value.status == 404


def test_readKeyRefusesAKeyOfTheOtherEnvironment(tmp_path: Path) -> None:
    key = tmp_path / "k"
    key.write_text("sk_live_123\n")
    assert provision.read_key("live", key) == "sk_live_123"
    with pytest.raises(SystemExit, match="does not hold a test key"):
        provision.read_key("test", key)


def test_theWebhookSecretIsWrittenPrivately(tmp_path: Path) -> None:
    target = tmp_path / "nested" / "secret"
    provision.secret_writer(target)("whsec_x")
    assert target.read_text() == "whsec_x"
    assert stat.S_IMODE(os.stat(target).st_mode) == 0o600


def test_mainProvisionsThenChecksCleanly(server: str, tmp_path: Path, capsys: Any, monkeypatch: Any) -> None:
    fake = FakeStripe()
    monkeypatch.setattr(provision, "StripeApi", lambda key, version, base: fake)
    key = tmp_path / "key"
    key.write_text("sk_test_x")
    secret = tmp_path / "secret"
    common = ["--env", "test", "--key-file", str(key), "--webhook-secret-file", str(secret)]
    assert provision.main(common) == 0
    out = capsys.readouterr()
    assert "changed: create the meter ostrom_active_person" in out.err
    assert json.loads(out.out)["dryRun"] is False
    assert secret.read_text() == "whsec_fake"
    assert "whsec_fake" not in out.out + out.err
    assert provision.main(common + ["--check"]) == 0
    assert "nothing to change" in capsys.readouterr().err


def test_checkFailsWhenSomethingWouldChange(tmp_path: Path, capsys: Any, monkeypatch: Any) -> None:
    fake = FakeStripe()
    monkeypatch.setattr(provision, "StripeApi", lambda key, version, base: fake)
    key = tmp_path / "key"
    key.write_text("sk_test_x")
    assert provision.main(["--env", "test", "--key-file", str(key), "--check"]) == 1
    assert "would change" in capsys.readouterr().err
    assert fake.writes == []


def test_aCreatedEndpointWithoutSecretSavesNothing() -> None:
    class NoSecret(FakeStripe):
        def call(self, method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
            answer = super().call(method, path, params)
            if path == "/v1/webhook_endpoints" and method == "POST":
                return {key: value for key, value in answer.items() if key != "secret"}
            return answer

    report, secrets = provision_with(NoSecret())
    assert secrets == []
    assert report.ids["webhookEndpoint"] == "we_8"


def test_listingIsBoundedToTwentyPages() -> None:
    class Endless(FakeStripe):
        def call(self, method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
            self.counter += 1
            return {"data": [{"id": f"x{self.counter}"}], "has_more": True}

    assert len(Endless().list_all("/v1/webhook_endpoints")) == 20
