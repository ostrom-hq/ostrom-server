# ee/

Code under this directory is **not** AGPL-3.0: it is licensed under the Ostrom Enterprise Edition license
([`ee/LICENSE`](LICENSE)) and only runs in production with a valid license key. Everything outside `ee/` is
AGPL-3.0 ([`../LICENSE`](../LICENSE)). Enterprise modules live in `ee/modules/<name>` and plug into the server
the same way as the modules of `modules/` (docs/MODULES.md); nothing outside `ee/` may depend on `ee/`.
