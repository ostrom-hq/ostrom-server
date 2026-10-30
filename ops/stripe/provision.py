#!/usr/bin/env python3
"""Creates or updates everything Ostrom's billing needs in a Stripe account, idempotently.

Run it once per environment, again after any change of `catalog.json`: it reads what exists, changes only what
differs, and never deletes. The same script is replayed in live mode at launch; only the key changes.

    python3 ops/stripe/provision.py --env test             # key from ~/.config/ostrom/stripe-test.key
    python3 ops/stripe/provision.py --env test --dry-run   # prints the plan, writes nothing
    python3 ops/stripe/provision.py --env live             # key from ~/.config/ostrom/stripe-live.key

What it manages (identity used to find each object again, so a rerun never duplicates it):
- Stripe Tax settings (head office, exclusive prices, SaaS tax code) and the tax registrations of the catalog;
- the billing meter counting active people (`event_name`);
- the products (fixed ids `ostrom_team`, `ostrom_business`, `ostrom_enterprise`);
- the prices (`lookup_key`; a price whose amount or shape changed is replaced: the new one takes the lookup key and
  the old one is archived, existing subscriptions keep it until they are moved);
- the customer portal configuration (`metadata.ostrom = billing_portal`), made the default one;
- the webhook endpoint (`metadata.ostrom = billing_webhook`). Its signing secret is shown by Stripe only at creation:
  it is written to `~/.config/ostrom/stripe-<env>-webhook.secret` (mode 0600) and never printed.

Only the standard library is used, so the script runs on any machine with Python 3.10+.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
CONFIG_DIR = Path.home() / ".config" / "ostrom"
PORTAL_TAG = "billing_portal"
WEBHOOK_TAG = "billing_webhook"
KEY_PREFIXES = {"test": ("sk_test_", "rk_test_"), "live": ("sk_live_", "rk_live_")}


class StripeError(Exception):
    """A refusal from Stripe, with its HTTP status and Stripe's own message (never a secret)."""

    def __init__(self, status: int, message: str):
        super().__init__(f"Stripe answered {status}: {message}")
        self.status = status


def encode(params: dict[str, Any], prefix: str = "") -> list[tuple[str, str]]:
    """Flattens nested params into Stripe's form encoding: `a[b][0]=x`. Booleans are `true`/`false`."""
    pairs: list[tuple[str, str]] = []
    for key, value in params.items():
        name = f"{prefix}[{key}]" if prefix else str(key)
        if isinstance(value, dict):
            pairs += encode(value, name)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                if isinstance(item, dict):
                    pairs += encode(item, f"{name}[{index}]")
                else:
                    pairs.append((f"{name}[{index}]", scalar(item)))
        elif value is not None:
            pairs.append((name, scalar(value)))
    return pairs


def scalar(value: Any) -> str:
    """One form value: Stripe expects lowercase booleans."""
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


class StripeApi:
    """The few calls the provisioning needs, over HTTPS with the standard library."""

    def __init__(self, key: str, api_version: str, base_url: str = "https://api.stripe.com"):
        self.base_url = base_url.rstrip("/")
        self.api_version = api_version
        self._auth = "Basic " + base64.b64encode(f"{key}:".encode()).decode()

    def call(self, method: str, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Sends one request and returns the decoded JSON body, or raises StripeError."""
        body = urllib.parse.urlencode(encode(params or {}))
        url = f"{self.base_url}{path}"
        data = None
        if method == "GET":
            url += f"?{body}" if body else ""
        else:
            data = body.encode()
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", self._auth)
        request.add_header("Stripe-Version", self.api_version)
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            payload = error.read().decode(errors="replace")
            try:
                message = json.loads(payload)["error"]["message"]
            except (ValueError, KeyError, TypeError):
                message = f"unreadable error body ({len(payload)} bytes)"
            raise StripeError(error.code, message) from None

    def list_all(self, path: str, params: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        """Reads every page of a list endpoint (bounded to 20 pages: our catalog is a handful of objects)."""
        items: list[dict[str, Any]] = []
        query = dict(params or {}, limit=100)
        for _ in range(20):
            page = self.call("GET", path, query)
            items += page["data"]
            if not page.get("has_more") or not page["data"]:
                break
            query["starting_after"] = page["data"][-1]["id"]
        return items


@dataclass
class Report:
    """What a run did: the ids of every managed object, and the list of changes (or planned changes)."""

    ids: dict[str, Any] = field(default_factory=dict)
    changes: list[str] = field(default_factory=list)


class Provisioner:
    """Brings a Stripe account to the state described by the catalog. Every step reads first, then writes."""

    def __init__(
        self,
        api: StripeApi,
        catalog: dict[str, Any],
        dry_run: bool,
        save_webhook_secret: Callable[[str], None],
        webhook_url: str | None = None,
    ):
        self.api = api
        self.catalog = catalog
        self.dry_run = dry_run
        self.save_webhook_secret = save_webhook_secret
        self.webhook_url = webhook_url or catalog["webhook"]["url"]
        self.report = Report()

    def write(self, description: str, method: str, path: str, params: dict[str, Any]) -> dict[str, Any] | None:
        """Records a change and performs it, unless this is a dry run."""
        self.report.changes.append(description)
        if self.dry_run:
            return None
        return self.api.call(method, path, params)

    def run(self) -> Report:
        """Every step, in dependency order (prices need the meter and the products)."""
        self.tax_settings()
        self.tax_registrations()
        meter_id = self.meter()
        self.products()
        self.prices(meter_id)
        self.portal()
        self.webhook()
        return self.report

    # Stripe Tax ------------------------------------------------------------------------------------------------

    def tax_settings(self) -> None:
        settings = self.api.call("GET", "/v1/tax/settings")
        wanted_address = self.catalog["headOffice"]
        current_address = ((settings.get("head_office") or {}).get("address")) or {}
        defaults = settings.get("defaults") or {}
        same = (
            all(current_address.get(key) == value for key, value in wanted_address.items())
            and defaults.get("tax_behavior") == "exclusive"
            and defaults.get("tax_code") == self.catalog["taxCode"]
        )
        self.report.ids["taxSettingsStatus"] = settings.get("status")
        if not same:
            self.write(
                "update the Stripe Tax settings (head office, exclusive prices, tax code)",
                "POST",
                "/v1/tax/settings",
                {
                    "head_office": {"address": wanted_address},
                    "defaults": {"tax_behavior": "exclusive", "tax_code": self.catalog["taxCode"]},
                },
            )

    def tax_registrations(self) -> None:
        active = {
            registration["country"]
            for registration in self.api.list_all("/v1/tax/registrations", {"status": "active"})
        }
        for wanted in self.catalog["taxRegistrations"]:
            country = wanted["country"]
            if country in active:
                continue
            self.write(
                f"add the {country} tax registration ({wanted['type']})",
                "POST",
                "/v1/tax/registrations",
                {
                    "country": country,
                    "active_from": "now",
                    "country_options": {
                        country.lower(): {"type": wanted["type"], wanted["type"]: wanted.get("options") or None},
                    },
                },
            )

    # Meter, products, prices -----------------------------------------------------------------------------------

    def meter(self) -> str | None:
        spec = self.catalog["meter"]
        for meter in self.api.list_all("/v1/billing/meters", {"status": "active"}):
            if meter["event_name"] == spec["eventName"]:
                self.report.ids["meter"] = meter["id"]
                return meter["id"]
        created = self.write(
            f"create the meter {spec['eventName']}",
            "POST",
            "/v1/billing/meters",
            {
                "display_name": spec["displayName"],
                "event_name": spec["eventName"],
                "default_aggregation": {"formula": "sum"},
                "customer_mapping": {"type": "by_id", "event_payload_key": "stripe_customer_id"},
                "value_settings": {"event_payload_key": spec["valueKey"]},
            },
        )
        meter_id = created["id"] if created else None
        self.report.ids["meter"] = meter_id
        return meter_id

    def products(self) -> None:
        for spec in self.catalog["products"]:
            wanted = {
                "name": spec["name"],
                "description": spec["description"],
                "tax_code": self.catalog["taxCode"],
                "metadata": {"ostrom_plan": spec["plan"]},
            }
            try:
                current = self.api.call("GET", f"/v1/products/{spec['id']}")
            except StripeError as error:
                if error.status != 404:
                    raise
                self.write(f"create the product {spec['id']}", "POST", "/v1/products", dict(wanted, id=spec["id"]))
                self.report.ids[spec["id"]] = spec["id"]
                continue
            self.report.ids[spec["id"]] = spec["id"]
            drift = (
                current.get("name") != wanted["name"]
                or current.get("description") != wanted["description"]
                or current.get("tax_code") != wanted["tax_code"]
                or (current.get("metadata") or {}).get("ostrom_plan") != spec["plan"]
                or not current.get("active")
            )
            if drift:
                self.write(
                    f"update the product {spec['id']}",
                    "POST",
                    f"/v1/products/{spec['id']}",
                    dict(wanted, active=True),
                )

    def price_params(self, spec: dict[str, Any], meter_id: str | None) -> dict[str, Any]:
        """The full creation request of a price; also the reference a live price is compared with."""
        recurring: dict[str, Any] = {"interval": spec["interval"]}
        if spec["metered"]:
            recurring.update(usage_type="metered", meter=meter_id)
        else:
            recurring["usage_type"] = "licensed"
        return {
            "product": spec["product"],
            "currency": self.catalog["currency"],
            "unit_amount": spec["unitAmount"],
            "tax_behavior": "exclusive",
            "lookup_key": spec["lookupKey"],
            "transfer_lookup_key": True,
            "recurring": recurring,
            "metadata": {"ostrom_price": spec["lookupKey"]},
        }

    def prices(self, meter_id: str | None) -> None:
        keys = [spec["lookupKey"] for spec in self.catalog["prices"]]
        existing = {
            price["lookup_key"]: price
            for price in self.api.list_all("/v1/prices", {"lookup_keys": keys, "active": "true"})
        }
        for spec in self.catalog["prices"]:
            params = self.price_params(spec, meter_id)
            current = existing.get(spec["lookupKey"])
            if current is not None and self.same_price(current, params):
                self.report.ids[spec["lookupKey"]] = current["id"]
                continue
            verb = "replace" if current is not None else "create"
            created = self.write(f"{verb} the price {spec['lookupKey']}", "POST", "/v1/prices", params)
            self.report.ids[spec["lookupKey"]] = created["id"] if created else None
            if current is not None:
                self.write(
                    f"archive the previous price {current['id']}",
                    "POST",
                    f"/v1/prices/{current['id']}",
                    {"active": False},
                )

    @staticmethod
    def same_price(current: dict[str, Any], params: dict[str, Any]) -> bool:
        recurring = current.get("recurring") or {}
        wanted = params["recurring"]
        return (
            current.get("product") == params["product"]
            and current.get("currency") == params["currency"]
            and current.get("unit_amount") == params["unit_amount"]
            and current.get("tax_behavior") == params["tax_behavior"]
            and recurring.get("interval") == wanted["interval"]
            and recurring.get("usage_type") == wanted["usage_type"]
            and recurring.get("meter") == wanted.get("meter")
        )

    # Customer portal and webhook --------------------------------------------------------------------------------

    def portal(self) -> None:
        spec = self.catalog["portal"]
        params = {
            "business_profile": {
                "headline": spec["headline"],
                "privacy_policy_url": spec["privacyPolicyUrl"],
                "terms_of_service_url": spec["termsOfServiceUrl"],
            },
            "default_return_url": spec["returnUrl"],
            "features": {
                "customer_update": {"enabled": True, "allowed_updates": ["email", "address", "tax_id", "name"]},
                "invoice_history": {"enabled": True},
                "payment_method_update": {"enabled": True},
                "subscription_cancel": {
                    "enabled": True,
                    "mode": "at_period_end",
                    "proration_behavior": "none",
                    "cancellation_reason": {
                        "enabled": True,
                        "options": ["too_expensive", "missing_features", "switched_service", "unused", "other"],
                    },
                },
                # Plan changes go through Ostrom's API: it knows the active people and the plan limits.
                "subscription_update": {"enabled": False},
            },
            "metadata": {"ostrom": PORTAL_TAG},
        }
        for configuration in self.api.list_all("/v1/billing_portal/configurations", {"active": "true"}):
            if (configuration.get("metadata") or {}).get("ostrom") == PORTAL_TAG:
                self.report.ids["portalConfiguration"] = configuration["id"]
                if not self.same_portal(configuration, params):
                    self.write(
                        f"update the portal configuration {configuration['id']}",
                        "POST",
                        f"/v1/billing_portal/configurations/{configuration['id']}",
                        params,
                    )
                return
        created = self.write("create the portal configuration", "POST", "/v1/billing_portal/configurations", params)
        self.report.ids["portalConfiguration"] = created["id"] if created else None

    @staticmethod
    def same_portal(current: dict[str, Any], params: dict[str, Any]) -> bool:
        profile = current.get("business_profile") or {}
        features = current.get("features") or {}
        cancel = features.get("subscription_cancel") or {}
        wanted_profile = params["business_profile"]
        return (
            all(profile.get(key) == value for key, value in wanted_profile.items())
            and current.get("default_return_url") == params["default_return_url"]
            and (features.get("invoice_history") or {}).get("enabled") is True
            and (features.get("payment_method_update") or {}).get("enabled") is True
            and cancel.get("enabled") is True
            and cancel.get("mode") == "at_period_end"
            and (features.get("subscription_update") or {}).get("enabled") is False
        )

    def webhook(self) -> None:
        events = sorted(self.catalog["webhook"]["events"])
        for endpoint in self.api.list_all("/v1/webhook_endpoints"):
            if (endpoint.get("metadata") or {}).get("ostrom") == WEBHOOK_TAG:
                self.report.ids["webhookEndpoint"] = endpoint["id"]
                same = (
                    endpoint.get("url") == self.webhook_url
                    and sorted(endpoint.get("enabled_events") or []) == events
                    and endpoint.get("status") == "enabled"
                )
                if not same:
                    self.write(
                        f"update the webhook endpoint {endpoint['id']}",
                        "POST",
                        f"/v1/webhook_endpoints/{endpoint['id']}",
                        {"url": self.webhook_url, "enabled_events": events, "disabled": False},
                    )
                return
        created = self.write(
            f"create the webhook endpoint {self.webhook_url}",
            "POST",
            "/v1/webhook_endpoints",
            {
                "url": self.webhook_url,
                "enabled_events": events,
                "api_version": self.catalog["apiVersion"],
                "description": "Ostrom billing",
                "metadata": {"ostrom": WEBHOOK_TAG},
            },
        )
        if created:
            self.report.ids["webhookEndpoint"] = created["id"]
            # Stripe returns the secret only in this answer; a mock may omit it.
            if created.get("secret"):
                self.save_webhook_secret(created["secret"])
        else:
            self.report.ids["webhookEndpoint"] = None


def read_key(env: str, key_file: Path) -> str:
    """Reads the secret key and refuses a key of the other environment (a live key in a test run, or the reverse)."""
    key = key_file.read_text().strip()
    if not key.startswith(KEY_PREFIXES[env]):
        raise SystemExit(f"{key_file} does not hold a {env} key: refusing to run.")
    return key


def secret_writer(path: Path) -> Callable[[str], None]:
    """Writes the webhook signing secret with mode 0600, never on stdout."""

    def save(secret: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            handle.write(secret)
        os.chmod(path, 0o600)

    return save


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Provision Ostrom's Stripe catalog (idempotent).")
    parser.add_argument("--env", choices=["test", "live"], required=True)
    parser.add_argument("--key-file", type=Path, help="Default: ~/.config/ostrom/stripe-<env>.key")
    parser.add_argument("--webhook-secret-file", type=Path, help="Default: ~/.config/ostrom/stripe-<env>-webhook.secret")
    parser.add_argument("--webhook-url", help="Default: the catalog's (https://api.ostrom.run/...)")
    parser.add_argument("--catalog", type=Path, default=HERE / "catalog.json")
    parser.add_argument("--base-url", default=os.environ.get("STRIPE_API_BASE", "https://api.stripe.com"))
    parser.add_argument("--dry-run", action="store_true", help="Print the changes without making them")
    parser.add_argument("--check", action="store_true", help="Exit 1 if anything would change (implies --dry-run)")
    args = parser.parse_args(argv)

    catalog = json.loads(args.catalog.read_text())
    key = read_key(args.env, args.key_file or CONFIG_DIR / f"stripe-{args.env}.key")
    secret_file = args.webhook_secret_file or CONFIG_DIR / f"stripe-{args.env}-webhook.secret"
    api = StripeApi(key, catalog["apiVersion"], args.base_url)
    dry_run = args.dry_run or args.check
    report = Provisioner(api, catalog, dry_run, secret_writer(secret_file), args.webhook_url).run()

    verb = "would change" if dry_run else "changed"
    for change in report.changes:
        print(f"{verb}: {change}", file=sys.stderr)
    if not report.changes:
        print("nothing to change", file=sys.stderr)
    print(json.dumps({"env": args.env, "dryRun": dry_run, "ids": report.ids}, indent=2, sort_keys=True))
    return 1 if args.check and report.changes else 0


if __name__ == "__main__":  # pragma: no cover - entry point, main() is tested directly
    sys.exit(main())
