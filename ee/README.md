# ee/

Code under this directory is **not** AGPL-3.0: it is licensed under the Ostrom Enterprise Edition license
([`ee/LICENSE`](LICENSE)) and only runs in production with a valid license key. Everything outside `ee/` is
AGPL-3.0 ([`../LICENSE`](../LICENSE)). Enterprise modules will live in `ee/modules/<name>` and implement the same
`ServerModule` contract as the modules of `modules/` (docs/MODULES.md); nothing outside `ee/` may depend on `ee/`.
The build does not wire `ee/` yet: the first enterprise module brings the Gradle discovery and its registration
(an AGPL build must still compile with `ee/` removed).
