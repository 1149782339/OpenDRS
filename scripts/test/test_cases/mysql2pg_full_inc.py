"""Case mysql2pg_full_inc: full + incremental migration MySQL -> PostgreSQL.

与 FULL_ONLY 不同，这个任务不会 COMPLETED（增量常驻），流程是：
  prepare  授权 -> 清目标表 -> 造 BASE_ROWS 行 -> 注册数据源 -> 建
           FULL_AND_INCREMENTAL 任务
  run      预检查 -> 启动 -> 等全量落完（目标行数 = BASE_ROWS）
           -> 源库续写 APPEND_ROWS 行 -> 等增量追平（目标行数达标）
  check    全量+增量的总行数做抽样 sha256 对账
  clean    terminate（任务还在跑，先停才能删）-> 删任务/数据源/两端隔离表
"""

import pytest

import utils
from facade import OpenDrs
from utils import MySqlConnection, PostgresConnection

BASE_ROWS = 5_000
APPEND_ROWS = 2_000


@pytest.fixture(scope="module")
def prepared(env, case_ctx, fw_config):
    mysql = MySqlConnection(env.datasources["mysql1"])
    pg = PostgresConnection(env.datasources["postgres1"])
    schema = mysql.cfg["database"]
    table = case_ctx.ns("user_wallet")
    timeout = float(fw_config.get("case_timeout_sec", 900))

    # ---------------- prepare
    mysql.ensure_cdc_privileges()
    pg.drop_table(schema, table)
    utils.seed_user_wallet(mysql, table, BASE_ROWS)

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
        case_ctx.ns("task"), "FULL_AND_INCREMENTAL", source, target,
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
        # ---------------- clean：任务仍在增量 streaming，先 terminate 才能删
        try:
            task.terminate()
        finally:
            opendrs.delete_task(task)
            opendrs.delete_datasource(source)
            opendrs.delete_datasource(target)
            pg.drop_table(schema, table)
            mysql.drop_table(table)


def test_full_then_incremental(prepared):
    # ---------------- run：全量阶段落完
    task = prepared["task"]
    mysql, pg = prepared["mysql"], prepared["pg"]
    schema, table = prepared["schema"], prepared["table"]

    task.precheck()
    task.wait_precheck_ok()
    task.start()
    utils.wait_row_count(pg, schema, table, BASE_ROWS, timeout=prepared["timeout"])

    # ---------------- run：源库补写，等增量追平
    utils.append_user_wallet_rows(mysql, table, BASE_ROWS + 1, APPEND_ROWS)
    utils.wait_row_count(pg, schema, table, BASE_ROWS + APPEND_ROWS)

    # ---------------- check：含增量在内的全表对账
    utils.assert_sampled_rows_match(
        mysql, pg, schema, table, BASE_ROWS + APPEND_ROWS)
