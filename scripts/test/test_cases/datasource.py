"""Case datasource: register a data source and verify it is reachable.

This is the fast-fail gate of the suite: every later stage calls the same API
through nginx, so a backend-down or proxy-broken environment dies here in
seconds instead of inside a migration task.
"""

import pytest

from facade import OpenDrs


@pytest.fixture(scope="module")
def registered(env, case_ctx):
    opendrs = OpenDrs(env.endpoint["url"])
    created = []
    for key, db_type in (("mysql1", "MYSQL"), ("postgres1", "POSTGRESQL")):
        cfg = env.datasources[key]
        ds = opendrs.create_datasource(
            case_ctx.ns(f"ds_{key}"), db_type,
            host=cfg["host"], port=int(cfg["port"]), database=cfg["database"],
            username=cfg["user"], password=cfg["password"])
        created.append(ds)
    try:
        yield {"opendrs": opendrs, "datasources": created}
    finally:
        for ds in created:
            opendrs.delete_datasource(ds)


def test_datasource_reachable(registered):
    for ds in registered["datasources"]:
        assert registered["opendrs"].test_datasource(ds), f"{ds.name} unreachable"
