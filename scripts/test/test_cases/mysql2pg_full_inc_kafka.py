"""Case mysql2pg_full_inc_kafka: FULL_AND_INCREMENTAL via CdcTransportSpec=KAFKA.

流程：
  prepare  授权 -> 清目标 -> 造 3 张表各 BASE_ROWS 行 -> 注册数据源
           -> 建 FULL_AND_INCREMENTAL 任务，options.cdcTransportSpec=KAFKA
  run      预检查 -> 启动 -> 等全量落完（每张 = BASE_ROWS）
           -> 源库 3 张表各 ALTER ADD COLUMN note
           -> 等目标表结构出现 note
           -> 源库各续写 APPEND_ROWS 行 -> 等增量追平（每张 = BASE+APPEND）
           -> 源库 3 张表各 ALTER DROP COLUMN note
           -> 等目标表结构去掉 note
           -> 源库各再续写 APPEND_ROWS 行 -> 等增量追平（每张 = BASE+2*APPEND）
  check    每张目标行数 = BASE+2*APPEND，且不含 note 列
  clean    terminate -> 删任务/数据源/两端隔离表

依赖应用栈里的 Kafka + Apicurio（deploy/docker-compose.yml）。
"""

import pytest

import utils
from facade import OpenDrs
from utils import MySqlConnection, PostgresConnection

TABLE_COUNT = 3
BASE_ROWS = 1_000
APPEND_ROWS = 1_000
NEW_COLUMN = "note"
NEW_COLUMN_DEF = "VARCHAR(64) NULL"


@pytest.fixture(scope="module")
def prepared(env, case_ctx, fw_config):
    mysql = MySqlConnection(env.datasources["mysql1"])
    pg_admin = PostgresConnection(env.datasources["postgres1"])
    pg_db = case_ctx.ns("db")
    pg_admin.create_database(pg_db)
    pg = pg_admin.with_database(pg_db)
    schema = mysql.cfg["database"]
    tables = [case_ctx.ns(f"wallet_{i}") for i in range(TABLE_COUNT)]
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
            "tables": [{"schema": schema, "table": t} for t in tables]}},
        cdc_transport_spec="KAFKA")

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


def test_full_inc_kafka_ddl_and_rows(prepared):
    task = prepared["task"]
    mysql, pg = prepared["mysql"], prepared["pg"]
    schema, tables = prepared["schema"], prepared["tables"]
    timeout = prepared["timeout"]
    after_add = BASE_ROWS + APPEND_ROWS
    final_rows = BASE_ROWS + 2 * APPEND_ROWS

    # ---------------- run：全量
    task.precheck()
    task.wait_precheck_ok()
    task.start()
    for table in tables:
        utils.wait_row_count(pg, schema, table, BASE_ROWS, timeout=timeout)

    # ---------------- run：增量 DDL（加列）+ DML
    for table in tables:
        utils.alter_add_column(mysql, table, NEW_COLUMN, NEW_COLUMN_DEF)
    for table in tables:
        utils.wait_column(pg, schema, table, NEW_COLUMN, timeout=timeout)

    for table in tables:
        utils.append_user_wallet_rows(mysql, table, BASE_ROWS + 1, APPEND_ROWS)
    for table in tables:
        utils.wait_row_count(pg, schema, table, after_add, timeout=timeout)

    # ---------------- run：增量 DDL（删列）+ DML
    for table in tables:
        utils.alter_drop_column(mysql, table, NEW_COLUMN)
    for table in tables:
        utils.wait_column_absent(pg, schema, table, NEW_COLUMN, timeout=timeout)

    for table in tables:
        utils.append_user_wallet_rows(mysql, table, after_add + 1, APPEND_ROWS)
    for table in tables:
        utils.wait_row_count(pg, schema, table, final_rows, timeout=timeout)

    # ---------------- check：最终行数 + 表结构（无 note 列）
    for table in tables:
        count = pg.count(schema, table)
        assert count == final_rows, \
            f"{schema}.{table} row count {count} != {final_rows}"
        assert not utils.pg_has_column(pg, schema, table, NEW_COLUMN), \
            f"{schema}.{table} still has column {NEW_COLUMN!r} after DROP"
