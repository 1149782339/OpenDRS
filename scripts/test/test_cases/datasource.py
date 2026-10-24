"""Case datasource: register a data source through the REST API.

Dummy body (framework skeleton phase): only sleeps and asserts the env wiring.
The real body will call api.py's create_connection against env.endpoint.
"""

import time

import pytest


@pytest.fixture(scope="module")
def prepared(case_ctx, env):
    print(f"\n[prepare] {case_ctx.case} worker={case_ctx.worker} "
          f"endpoint={env.endpoint['url']}", flush=True)
    time.sleep(1)
    yield {"registered": 2}
    print(f"\n[clean] {case_ctx.case}", flush=True)


def test_register_datasource(prepared):
    time.sleep(1)  # run: the REST call goes here
    assert prepared["registered"] == 2  # check
