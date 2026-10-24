"""Thin REST client for the OpenDRS backend API.

Wraps every call used by the e2e scripts: unwraps the common
``{code, message, data}`` envelope (code 1000 == success) and raises
``ApiError`` on HTTP or application failures.

Run from scripts/test so imports and uv dependencies resolve.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

SUCCESS_CODE = 1000

BASE_URL = os.environ.get("OPENDRS_BASE_URL", "http://127.0.0.1").rstrip("/")
TIMEOUT = float(os.environ.get("OPENDRS_TIMEOUT", "10"))


class ApiError(RuntimeError):
    """HTTP error or an application-level failure (code != 1000)."""


class OpenDrsApi:
    def __init__(self, base_url: str = BASE_URL, timeout: float = TIMEOUT) -> None:
        self.base_url = base_url
        self.timeout = timeout

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict | list | None:
        body = json.dumps(payload).encode() if payload is not None else None
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                text = response.read()
        except urllib.error.HTTPError as error:  # answered, but not 2xx
            detail = error.read().decode("utf-8", "replace")[:500]
            raise ApiError(f"{method} {path} -> HTTP {error.code}: {detail}") from error
        except urllib.error.URLError as error:  # connection refused, DNS, timeout, ...
            raise ApiError(f"{method} {path} unreachable: {error.reason}") from error

        try:
            envelope = json.loads(text)
        except json.JSONDecodeError as error:
            raise ApiError(f"{method} {path} returned non-JSON body: {text[:200]!r}") from error

        if not isinstance(envelope, dict) or envelope.get("code") != SUCCESS_CODE:
            raise ApiError(
                f"{method} {path} -> code={envelope.get('code')} message={envelope.get('message')!r}"
            )
        return envelope.get("data")

    # ------------------------------------------------------------------ connections
    def create_connection(self, name: str, connection: dict) -> dict:
        return self._request("POST", "/api/v1/migration/connections", {"name": name, "connection": connection})

    def list_connections(self) -> list:
        return self._request("GET", "/api/v1/migration/connections") or []

    def delete_connection(self, connection_id: int) -> None:
        self._request("DELETE", f"/api/v1/migration/connections/{connection_id}")

    # ------------------------------------------------------------------ tasks
    def create_task(self, name: str, mode: str, source_connection_id: int,
                    target_connection_id: int, options: dict | None = None) -> dict:
        return self._request("POST", "/api/v1/migration/tasks", {
            "name": name,
            "mode": mode,
            "sourceConnectionId": source_connection_id,
            "targetConnectionId": target_connection_id,
            "options": options,
        })

    def get_task(self, task_id: int) -> dict:
        return self._request("GET", f"/api/v1/migration/tasks/{task_id}")

    def get_status(self, task_id: int) -> dict:
        return self._request("GET", f"/api/v1/migration/tasks/{task_id}/status")

    def start_precheck(self, task_id: int) -> dict:
        return self._request("POST", f"/api/v1/migration/tasks/{task_id}/precheck")

    def get_precheck(self, task_id: int) -> dict:
        return self._request("GET", f"/api/v1/migration/tasks/{task_id}/precheck")

    def start_task(self, task_id: int) -> dict:
        return self._request("POST", f"/api/v1/migration/tasks/{task_id}/start")
