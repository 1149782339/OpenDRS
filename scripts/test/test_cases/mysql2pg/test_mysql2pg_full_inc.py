"""Case mysql2pg_full_inc: full + incremental migration MySQL -> PostgreSQL.

Dummy body (framework skeleton phase). The real case adds a post-full write
burst on the source and re-verifies after incremental replay catches up.
"""

import time

import pytest


@pytest.fixture(scope="module")
def prepared(case_ctx, env):
    table = case_ctx.ns("wallet")
    print(f"\n[prepare] {case_ctx.case} worker={case_ctx.worker} seed table={table}",
          flush=True)
    time.sleep(2)
    yield {"table": table, "rows": 5_000}
    print(f"\n[clean] {case_ctx.case} drop table={table}", flush=True)


def test_full_then_incremental(prepared):
    time.sleep(3)  # run: full phase
    time.sleep(1)  # run: incremental catch-up phase
    assert prepared["rows"] == 5_000  # check
