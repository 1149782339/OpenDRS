"""Case mysql2pg_full: full-only migration MySQL -> PostgreSQL.

Dummy body (framework skeleton phase). Shape of the real case:
  prepare  seed N deterministic rows into an isolated table (case_ctx.ns)
  run      create connections + FULL_ONLY task, precheck, start, poll COMPLETED
  check    target row count + sampled sha256 against source
  clean    drop the isolated schema — guaranteed by fixture teardown
"""

import time

import pytest


@pytest.fixture(scope="module")
def prepared(case_ctx, env):
    table = case_ctx.ns("wallet")
    print(f"\n[prepare] {case_ctx.case} worker={case_ctx.worker} "
          f"seed table={table} db={env.datasources['mysql1']['database']}", flush=True)
    time.sleep(2)  # the real seed lands here
    yield {"table": table, "rows": 10_000}
    print(f"\n[clean] {case_ctx.case} drop table={table}", flush=True)


def test_full_migration(prepared, env):
    assert env.datasources["postgres1"]["port"] == 5432
    time.sleep(2)  # run: the migration task goes here
    assert prepared["rows"] == 10_000  # check
