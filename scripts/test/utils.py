"""数据库连接包装类与用例工具。

连接类按数据库类型封装（MySqlConnection / PostgresConnection），构造参数直接
吃 profile env.datasources 的条目；用例持有的是"连接"，source/target 只是
用例里的角色分工，不体现在工具函数签名上。表名一律由调用方传
case_ctx.ns(...) 生成的隔离名。
"""

from __future__ import annotations

import hashlib
import random
import time
from decimal import Decimal

import psycopg2
import pymysql

BATCH = 1_000


class MySqlConnection:
    """MySQL 连接：profile 条目需含 host/port/user/password/database，
    另有 root_password 时才能调用 ensure_cdc_privileges。"""

    def __init__(self, cfg: dict) -> None:
        self.cfg = cfg

    def _connect(self, **kwargs):
        return pymysql.connect(
            host=self.cfg["host"], port=int(self.cfg["port"]),
            user=self.cfg["user"], password=self.cfg["password"],
            database=self.cfg["database"], **kwargs)

    def execute(self, sql: str, params: tuple = ()) -> None:
        conn = self._connect(autocommit=True)
        try:
            with conn.cursor() as cur:
                cur.execute(sql, params)
        finally:
            conn.close()

    def count(self, table: str) -> int:
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(f"SELECT COUNT(*) FROM {table}")
                return cur.fetchone()[0]
        finally:
            conn.close()

    def drop_table(self, table: str) -> None:
        self.execute(f"DROP TABLE IF EXISTS {table}")

    def fetch_rows(self, table: str, ids: list[int]) -> dict[int, tuple]:
        """按主键 id 批量取行，money 统一规范化为 Decimal。"""
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                placeholders = ",".join(["%s"] * len(ids))
                cur.execute(
                    f"SELECT id, name, money FROM {table} WHERE id IN ({placeholders})", ids)
                return {row[0]: (row[1], Decimal(str(row[2]))) for row in cur.fetchall()}
        finally:
            conn.close()

    def ensure_cdc_privileges(self) -> None:
        """预检查要求 SELECT/RELOAD/REPLICATION SLAVE/REPLICATION CLIENT/
        SHOW DATABASES；容器创建的普通用户只有库级权限，用 root 补齐。"""
        conn = pymysql.connect(host=self.cfg["host"], port=int(self.cfg["port"]),
                               user="root", password=self.cfg["root_password"],
                               autocommit=True)
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "GRANT SELECT, RELOAD, REPLICATION SLAVE, REPLICATION CLIENT, "
                    "SHOW DATABASES ON *.* TO %s@'%%'", (self.cfg["user"],))
        finally:
            conn.close()


class PostgresConnection:
    """PostgreSQL 连接：schema 与 table 由调用方分别传入。"""

    def __init__(self, cfg: dict) -> None:
        self.cfg = cfg

    def _connect(self):
        return psycopg2.connect(host=self.cfg["host"], port=int(self.cfg["port"]),
                                user=self.cfg["user"], password=self.cfg["password"],
                                dbname=self.cfg["database"])

    def count(self, schema: str, table: str) -> int:
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(f'SELECT COUNT(*) FROM "{schema}"."{table}"')
                return cur.fetchone()[0]
        finally:
            conn.close()

    def drop_table(self, schema: str, table: str) -> None:
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(f'DROP TABLE IF EXISTS "{schema}"."{table}" CASCADE')
            conn.commit()
        finally:
            conn.close()

    def fetch_rows(self, schema: str, table: str,
                   ids: list[int]) -> dict[int, tuple]:
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f'SELECT id, name, money FROM "{schema}"."{table}" '
                    "WHERE id = ANY(%s::int[])", (ids,))
                return {row[0]: (row[1], Decimal(str(row[2]))) for row in cur.fetchall()}
        finally:
            conn.close()


# ---------------------------------------------------------------------- 造数（案例 schema 专属）
def seed_user_wallet(mysql: MySqlConnection, table: str, rows: int) -> None:
    """确定性造数：name=user-NNNNN，money=(i%997)*1.25，两端可对 hash。"""
    conn = mysql._connect(autocommit=False)
    try:
        with conn.cursor() as cur:
            cur.execute(f"DROP TABLE IF EXISTS {table}")
            cur.execute(
                f"CREATE TABLE {table} ("
                "  id INT PRIMARY KEY AUTO_INCREMENT,"
                "  name VARCHAR(64) NOT NULL,"
                "  money DECIMAL(20,2) NOT NULL"
                ")"
            )
            batch = []
            for i in range(1, rows + 1):
                batch.append((f"user-{i:05d}", Decimal(i % 997) * Decimal("1.25")))
                if i % BATCH == 0:
                    cur.executemany(f"INSERT INTO {table} (name, money) VALUES (%s, %s)", batch)
                    batch.clear()
            if batch:
                cur.executemany(f"INSERT INTO {table} (name, money) VALUES (%s, %s)", batch)
            cur.execute(f"SELECT COUNT(*) FROM {table}")
            count = cur.fetchone()[0]
        conn.commit()
        if count != rows:
            raise RuntimeError(f"seed count {count} != {rows}")
    finally:
        conn.close()


# ---------------------------------------------------------------------- 增量辅助
def append_user_wallet_rows(mysql: MySqlConnection, table: str,
                            start_id: int, count: int) -> None:
    """在已有表上按同样的确定性规则续写（id 从 start_id 起），供增量场景补写。"""
    conn = mysql._connect(autocommit=False)
    try:
        with conn.cursor() as cur:
            batch = []
            for i in range(start_id, start_id + count):
                batch.append((f"user-{i:05d}", Decimal(i % 997) * Decimal("1.25")))
                if len(batch) >= BATCH:
                    cur.executemany(f"INSERT INTO {table} (name, money) VALUES (%s, %s)", batch)
                    batch.clear()
            if batch:
                cur.executemany(f"INSERT INTO {table} (name, money) VALUES (%s, %s)", batch)
        conn.commit()
    finally:
        conn.close()


def wait_row_count(pg: PostgresConnection, schema: str, table: str,
                   expected: int, timeout: float = 180, poll: float = 2) -> None:
    """轮询目标端行数直到达到 expected（增量追平的判据）。"""
    deadline = time.monotonic() + timeout
    while True:
        try:
            count = pg.count(schema, table)
        except psycopg2.errors.UndefinedTable:
            count = 0  # 目标端建表晚于任务启动，属正常时序
        if count >= expected:
            return
        if time.monotonic() > deadline:
            raise RuntimeError(
                f"{schema}.{table} row count {count} did not reach {expected} "
                f"within {timeout:.0f}s")
        time.sleep(poll)


# ---------------------------------------------------------------------- 对账
def row_digest(rows: dict[int, tuple]) -> str:
    canonical = "\n".join(
        f"{i}|{name}|{format(money, 'f')}" for i, (name, money) in sorted(rows.items())
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


def assert_sampled_rows_match(mysql: MySqlConnection, pg: PostgresConnection,
                              schema: str, table: str, total_rows: int,
                              sample_size: int = 100, sample_seed: int = 42) -> None:
    """抽样两边各取同一批 id，行数、抽样数、规范化后的 sha256 全部对齐才过。"""
    sampled = random.Random(sample_seed).sample(range(1, total_rows + 1), sample_size)

    src_rows = mysql.fetch_rows(table, sampled)
    assert len(src_rows) == sample_size, \
        f"mysql returned {len(src_rows)} of {sample_size} sampled rows"

    tgt_count = pg.count(schema, table)
    assert tgt_count == total_rows, f"pg row count {tgt_count} != {total_rows}"

    tgt_rows = pg.fetch_rows(schema, table, sampled)
    assert len(tgt_rows) == sample_size, \
        f"pg returned {len(tgt_rows)} of {sample_size} sampled rows"

    src_hash, tgt_hash = row_digest(src_rows), row_digest(tgt_rows)
    assert src_hash == tgt_hash, (
        f"hash mismatch: mysql={src_hash[:16]} pg={tgt_hash[:16]}; "
        f"first differing ids: "
        f"{[i for i in sampled if src_rows.get(i) != tgt_rows.get(i)][:5]}"
    )
