"""Case mysql2pg_full_inc: full + incremental migration MySQL -> PostgreSQL.

与 FULL_ONLY 不同，这个任务不会 COMPLETED（增量常驻），流程是：
  prepare  授权 -> 清目标表 -> 造 2 张表各 BASE_ROWS 行 -> 注册数据源
           -> 建 FULL_AND_INCREMENTAL 任务（指定表选择，2 张表）
  run      预检查 -> 启动 -> 等全量落完（每张目标行数 = BASE_ROWS）
           -> 源库两张表各续写 APPEND_ROWS 行 -> 等增量追平（每张达标）
  check    每张表全量+增量的总行数做抽样 sha256 对账
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
    pg_admin = PostgresConnection(env.datasources["postgres1"])
    # 每个用例独立的目标库：同 stage 并行用例共享目标实例时，目标 schema（= 源库名）
    # 的 CREATE SCHEMA IF NOT EXISTS 并发会撞 pg_namespace 唯一索引（PG 已知竞态）。
    pg_db = case_ctx.ns("db")
    pg_admin.create_database(pg_db)
    pg = pg_admin.with_database(pg_db)
    schema = mysql.cfg["database"]
    tables = [case_ctx.ns("user_wallet"), case_ctx.ns("user_wallet_b")]
    timeout = float(fw_config.get("case_timeout_sec", 900))

    # ---------------- prepare
    mysql.ensure_cdc_privileges()
    for table in tables:
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
        host=pg.cfg["host"], port=int(pg.cfg["port"]), database=pg_db,
        username=pg.cfg["user"], password=pg.cfg["password"])
    assert opendrs.test_datasource(source)
    assert opendrs.test_datasource(target)
    task = opendrs.create_migration_task(
        case_ctx.ns("task"), "FULL_AND_INCREMENTAL", source, target,
        options={"topology": "ONE_WAY", "selection": {
            "mode": "TABLE",
            "tables": [{"schema": schema, "table": t} for t in tables]}})

    ctx = {
        "opendrs": opendrs, "task": task, "source": source, "target": target,
        "mysql": mysql, "pg": pg, "schema": schema, "tables": tables,
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
            for table in tables:
                pg.drop_table(schema, table)
                mysql.drop_table(table)
            pg_admin.drop_database(pg_db)


def test_full_then_incremental(prepared):
    # ---------------- run：全量阶段落完
    task = prepared["task"]
    mysql, pg = prepared["mysql"], prepared["pg"]
    schema, tables = prepared["schema"], prepared["tables"]

    task.precheck()
    task.wait_precheck_ok()
    task.start()
    for table in tables:
        utils.wait_row_count(pg, schema, table, BASE_ROWS,
                             timeout=prepared["timeout"])

    # ---------------- run：源库补写，等增量追平
    for table in tables:
        utils.append_user_wallet_rows(mysql, table, BASE_ROWS + 1, APPEND_ROWS)
    for table in tables:
        utils.wait_row_count(pg, schema, table, BASE_ROWS + APPEND_ROWS)

    # ---------------- check：每张表独立对账
    for table in tables:
        utils.assert_sampled_rows_match(
            mysql, pg, schema, table, BASE_ROWS + APPEND_ROWS)
