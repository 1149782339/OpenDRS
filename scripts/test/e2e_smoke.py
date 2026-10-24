"""E2E smoke: prove the application stack is up and the API answers.

Phase 1 scope is deliberately read-only: probe a few GET endpoints through nginx
and check they return well-formed JSON. Creating sources, running a migration
task, and comparing rows comes in a later phase.

Run (from scripts/test, with the app stack up):
    uv run e2e_smoke.py

Environment:
    SMOKE_BASE_URL   default http://127.0.0.1 (nginx proxy; backend is :8080)
    SMOKE_TIMEOUT    per-request seconds, default 10
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

BASE_URL = os.environ.get("SMOKE_BASE_URL", "http://127.0.0.1").rstrip("/")
TIMEOUT = float(os.environ.get("SMOKE_TIMEOUT", "10"))

# (method, path, what a good body looks like)
PROBES: list[tuple[str, str]] = [
    ("GET", "/api/v1/migration/connections"),
    ("GET", "/api/v1/migration/tasks"),
]


def probe(method: str, path: str) -> tuple[int | None, bytes, str]:
    request = urllib.request.Request(BASE_URL + path, method=method)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            return response.status, response.read(), response.headers.get("Content-Type", "")
    except urllib.error.HTTPError as error:  # answered, but not 2xx
        return error.code, error.read(), ""
    except urllib.error.URLError as error:  # connection refused, DNS, timeout, ...
        return None, str(error.reason).encode(), ""


def main() -> int:
    failures = 0
    for method, path in PROBES:
        status, body, content_type = probe(method, path)
        ok = status == 200 and "json" in content_type.lower()
        detail = ""
        if ok:
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                ok = False
                detail = "body is not valid JSON"
            else:
                detail = f"{len(payload)} item(s)" if isinstance(payload, list) else "json object"
        else:
            snippet = body.decode("utf-8", "replace")[:120]
            detail = f"content-type={content_type!r} body={snippet!r}" if status is not None else "unreachable"

        mark = "PASS" if ok else "FAIL"
        print(f"{mark}  {method} {path} -> {status if status is not None else 'no response'}  ({detail})")
        failures += 0 if ok else 1

    if failures:
        print(f"\n{failures}/{len(PROBES)} probe(s) failed against {BASE_URL}")
        return 1
    print(f"\nAll {len(PROBES)} probes passed against {BASE_URL}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
