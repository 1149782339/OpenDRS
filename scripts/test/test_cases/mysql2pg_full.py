"""Case mysql2pg_full: full-only migration MySQL -> PostgreSQL.

生命周期（module fixture = prepare/clean，用例失败 clean 也保证执行）：
  prepare  源库授权 CDC 权限 -> 目标库清表 -> 源库造 1 万行确定性数据
           -> 注册双端数据源 -> 建 FULL_ONLY 任务
  run      预检查 -> 启动 -> 轮询到 COMPLETED
  check    目标行数 = 10000，抽样 100 行两端 sha256 一致
  clean    删任务/数据源（fixture teardown），删两端隔离表
"""

import pytest

import utils
from facade import OpenDrs
from utils import MySqlConnection, PostgresConnection

ROW_COUNT = 10_000


@pytest.fixture(scope="module")
def prepared(env, case_ctx, fw_config):
    mysql = MySqlConnection(env.datasources["mysql1"])
    pg = PostgresConnection(env.datasources["postgres1"])
    schema = mysql.cfg["database"]        # MySQL 库名迁移后是同名的 PG schema
    table = case_ctx.ns("user_wallet")
    timeout = float(fw_config.get("case_timeout_sec", 900))

    # ---------------- prepare
    mysql.ensure_cdc_privileges()
    pg.drop_table(schema, table)
    utils.seed_user_wallet(mysql, table, ROW_COUNT)

    opendrs = OpenDrs(env.endpoint["url"])
    source = opendrs.create_datasource(
        case_ctx.ns("src_mysql"), "MYSQL",
        host=mysql.cfg["host"], port=int(mysql.cfg["port"]),
        database=mysql.cfg["database"],
        username=mysql.cfg["user"], password=mysql.cfg["password"])
    target = opendrs.create_datasource(
        case_ctx.ns("tgt_pg"), "POSTGRESQL",
        host=pg.cfg["host"], port=int(pg.cfg["port"]), database=pg.cfg["database"],
        username=pg.cfg["user"], password=pg.cfg["password"])
    assert opendrs.test_datasource(source)
    assert opendrs.test_datasource(target)
    task = opendrs.create_migration_task(
        case_ctx.ns("task"), "FULL_ONLY", source, target,
        options={"topology": "ONE_WAY", "selection": {
            "mode": "TABLE",
            # 只迁移本用例的隔离表：并行用例共用源库时，ALL 会让两个任务互相
            # 抓取对方的表，目标端建表/建序列发生竞争
            "tables": [{"schema": schema, "table": table}]}})

    ctx = {
        "opendrs": opendrs, "task": task, "source": source, "target": target,
        "mysql": mysql, "pg": pg, "schema": schema, "table": table,
        "timeout": timeout,
    }
    try:
        yield ctx
    finally:
        # ---------------- clean
        try:
            opendrs.delete_task(task)
        finally:
            opendrs.delete_datasource(source)
            opendrs.delete_datasource(target)
            pg.drop_table(schema, table)
            mysql.drop_table(table)


def test_full_migration(prepared):
    # ---------------- run
    task = prepared["task"]
    task.precheck()
    task.wait_precheck_ok()
    task.start()
    status = task.wait_completed(timeout=prepared["timeout"])
    assert status.phase == "FULL"

    # ---------------- check
    utils.assert_sampled_rows_match(
        prepared["mysql"], prepared["pg"],
        prepared["schema"], prepared["table"], ROW_COUNT)
