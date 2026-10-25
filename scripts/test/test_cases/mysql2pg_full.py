"""Case mysql2pg_full: full-only migration MySQL -> PostgreSQL.

生命周期（module fixture = prepare/clean，用例失败 clean 也保证执行）：
  prepare  源库授权 CDC 权限 -> 目标库清表 -> 源库造 2 张表各 1 万行确定性数据
           -> 注册双端数据源 -> 建 FULL_ONLY 任务（指定表选择，2 张表）
  run      预检查 -> 启动 -> 轮询到 COMPLETED
  check    每张目标表行数 = 10000，各抽样 100 行两端 sha256 一致
  clean    删任务/数据源（fixture teardown），删两端隔离表

只选本用例的隔离表：并行用例共用源库时 selection=ALL 会让两个任务互相
抓取对方的表，目标端建表/建序列发生竞争。
"""

import pytest

import utils
from facade import OpenDrs
from utils import MySqlConnection, PostgresConnection

ROW_COUNT = 10_000


@pytest.fixture(scope="module")
def prepared(env, case_ctx, fw_config):
    mysql = MySqlConnection(env.datasources["mysql1"])
    pg_admin = PostgresConnection(env.datasources["postgres1"])
    # 每个用例独立的目标库：同 stage 并行用例共享目标实例时，目标 schema（= 源库名）
    # 的 CREATE SCHEMA IF NOT EXISTS 并发会撞 pg_namespace 唯一索引（PG 已知竞态）。
    pg_db = case_ctx.ns("db")
    pg_admin.create_database(pg_db)
    pg = pg_admin.with_database(pg_db)
    schema = mysql.cfg["database"]        # MySQL 库名迁移后是同名的 PG schema
    tables = [case_ctx.ns("user_wallet"), case_ctx.ns("user_wallet_b")]
    timeout = float(fw_config.get("case_timeout_sec", 900))

    # ---------------- prepare
    mysql.ensure_cdc_privileges()
    for table in tables:
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
        host=pg.cfg["host"], port=int(pg.cfg["port"]), database=pg_db,
        username=pg.cfg["user"], password=pg.cfg["password"])
    assert opendrs.test_datasource(source)
    assert opendrs.test_datasource(target)
    task = opendrs.create_migration_task(
        case_ctx.ns("task"), "FULL_ONLY", source, target,
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
        # ---------------- clean
        try:
            opendrs.delete_task(task)
        finally:
            opendrs.delete_datasource(source)
            opendrs.delete_datasource(target)
            for table in tables:
                pg.drop_table(schema, table)
                mysql.drop_table(table)
            pg_admin.drop_database(pg_db)


def test_full_migration(prepared):
    # ---------------- run
    task = prepared["task"]
    task.precheck()
    task.wait_precheck_ok()
    task.start()
    status = task.wait_completed(timeout=prepared["timeout"])
    assert status.phase == "FULL"

    # ---------------- check：每张表独立对账
    for table in prepared["tables"]:
        utils.assert_sampled_rows_match(
            prepared["mysql"], prepared["pg"],
            prepared["schema"], table, ROW_COUNT)
