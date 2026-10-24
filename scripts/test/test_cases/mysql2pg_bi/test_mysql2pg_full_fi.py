"""Case mysql2pg_full_fi: bidirectional full + forward incremental (dummy body)."""

import time

import pytest


@pytest.fixture(scope="module")
def prepared(case_ctx):
    table = case_ctx.ns("ledger")
    print(f"\n[prepare] {case_ctx.case} worker={case_ctx.worker} seed table={table}",
          flush=True)
    time.sleep(1)
    yield {"table": table}
    print(f"\n[clean] {case_ctx.case}", flush=True)


def test_bidirectional_full(prepared):
    time.sleep(2)
    assert prepared["table"].startswith("ledger_")
