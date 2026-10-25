"""Case mysql2pg_schema_full: schema-level full-only migration MySQL -> PostgreSQL.

整 schema 选择（selection.mode=SCHEMA）：源库 2 个 database、每个 2 张表。
库与表名都带 case_ctx.ns 后缀，用例间互不干扰。

  prepare  root 建 2 个隔离库（含业务用户授权）-> 每库造 2 张表各 ROWS 行
           -> 注册双端数据源 -> 建 FULL_ONLY 任务（schemas = 2 个库）
  run      预检查 -> 启动 -> 轮询到 COMPLETED
  check    每个 MySQL 库映射为同名 PG schema，4 张表各自抽样对账
  clean    删任务/数据源，删 PG schema（CASCADE）与 MySQL 库
"""

import pytest

import utils
from facade import OpenDrs
from utils import MySqlConnection, PostgresConnection

SCHEMA_COUNT = 2
TABLE_COUNT = 2
ROW_COUNT = 2_500


@pytest.fixture(scope="module")
def prepared(env, case_ctx, fw_config):
    mysql = MySqlConnection(env.datasources["mysql1"])
    pg_admin = PostgresConnection(env.datasources["postgres1"])
    # 每个用例独立的目标库：同 stage 并行用例共享目标实例时，目标 schema 的
    # CREATE SCHEMA IF NOT EXISTS 并发会撞 pg_namespace 唯一索引（PG 已知竞态）。
    pg_db = case_ctx.ns("db")
    pg_admin.create_database(pg_db)
    pg = pg_admin.with_database(pg_db)
    dbs = [case_ctx.ns(f"shop_{i}") for i in range(SCHEMA_COUNT)]
    tables = [case_ctx.ns("user_wallet"), case_ctx.ns("user_wallet_b")]
    timeout = float(fw_config.get("case_timeout_sec", 900))

    # ---------------- prepare
    mysql.ensure_cdc_privileges()
    for db in dbs:
        mysql.create_database(db)
        pg.drop_schema(db)  # 上次中断的残留
        for table in tables:
            utils.seed_user_wallet(mysql, table, ROW_COUNT, database=db)

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
        case_ctx.ns("task"), "FULL_ONLY", source, target,
        options={"topology": "ONE_WAY", "selection": {
            "mode": "SCHEMA", "schemas": dbs}})

    ctx = {
        "opendrs": opendrs, "task": task, "source": source, "target": target,
        "mysql": mysql, "pg": pg, "dbs": dbs, "tables": tables,
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
            for db in dbs:
                pg.drop_schema(db)
                mysql.drop_database(db)
            pg_admin.drop_database(pg_db)


def test_schema_full_migration(prepared):
    # ---------------- run
    task = prepared["task"]
    task.precheck()
    task.wait_precheck_ok()
    task.start()
    status = task.wait_completed(timeout=prepared["timeout"])
    assert status.phase == "FULL"

    # ---------------- check：MySQL 库 -> 同名 PG schema，每表独立对账
    for db in prepared["dbs"]:
        for table in prepared["tables"]:
            utils.assert_sampled_rows_match(
                prepared["mysql"], prepared["pg"],
                db, table, ROW_COUNT, mysql_database=db)
