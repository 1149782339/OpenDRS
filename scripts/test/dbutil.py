"""数据库侧的用例工具：造数、清理、对账。

数据源配置直接复用 profile 的 env.datasources 条目（host/port/user/password/
database，MySQL 条目另有 root_password 用于授权）。表名一律由调用方传
case_ctx.ns(...) 生成的隔离名。
"""

from __future__ import annotations

import hashlib
import random
from decimal import Decimal

import psycopg2
import pymysql

BATCH = 1_000


# ---------------------------------------------------------------------- MySQL
def ensure_source_privileges(mysql: dict) -> None:
    """预检查要求 SELECT/RELOAD/REPLICATION SLAVE/REPLICATION CLIENT/SHOW DATABASES，
    容器创建的普通用户只有库级权限——用 root 补齐 CDC 权限集。"""
    conn = pymysql.connect(host=mysql["host"], port=int(mysql["port"]), user="root",
                           password=mysql["root_password"], autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "GRANT SELECT, RELOAD, REPLICATION SLAVE, REPLICATION CLIENT, "
                "SHOW DATABASES ON *.* TO %s@'%%'", (mysql["user"],)
            )
    finally:
        conn.close()


def seed_user_wallet(mysql: dict, table: str, rows: int) -> None:
    """确定性造数：name=user-NNNNN，money=(i%997)*1.25，两端可对 hash。"""
    conn = pymysql.connect(host=mysql["host"], port=int(mysql["port"]),
                           user=mysql["user"], password=mysql["password"],
                           database=mysql["database"], autocommit=False)
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
            raise RuntimeError(f"source seed count {count} != {rows}")
    finally:
        conn.close()


def drop_source_table(mysql: dict, table: str) -> None:
    conn = pymysql.connect(host=mysql["host"], port=int(mysql["port"]),
                           user=mysql["user"], password=mysql["password"],
                           database=mysql["database"], autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute(f"DROP TABLE IF EXISTS {table}")
    finally:
        conn.close()


def fetch_source_rows(mysql: dict, table: str, ids: list[int]) -> dict[int, tuple]:
    conn = pymysql.connect(host=mysql["host"], port=int(mysql["port"]),
                           user=mysql["user"], password=mysql["password"],
                           database=mysql["database"])
    try:
        with conn.cursor() as cur:
            placeholders = ",".join(["%s"] * len(ids))
            cur.execute(f"SELECT id, name, money FROM {table} WHERE id IN ({placeholders})", ids)
            return {row[0]: (row[1], Decimal(str(row[2]))) for row in cur.fetchall()}
    finally:
        conn.close()


# ---------------------------------------------------------------------- PostgreSQL
def drop_target_table(pg: dict, schema: str, table: str) -> None:
    conn = _pg_connect(pg)
    try:
        with conn.cursor() as cur:
            cur.execute(f'DROP TABLE IF EXISTS "{schema}"."{table}" CASCADE')
        conn.commit()
    finally:
        conn.close()


def target_row_count(pg: dict, schema: str, table: str) -> int:
    conn = _pg_connect(pg)
    try:
        with conn.cursor() as cur:
            cur.execute(f'SELECT COUNT(*) FROM "{schema}"."{table}"')
            return cur.fetchone()[0]
    finally:
        conn.close()


def fetch_target_rows(pg: dict, schema: str, table: str,
                      ids: list[int]) -> dict[int, tuple]:
    conn = _pg_connect(pg)
    try:
        with conn.cursor() as cur:
            cur.execute(
                f'SELECT id, name, money FROM "{schema}"."{table}" WHERE id = ANY(%s::int[])',
                (ids,))
            return {row[0]: (row[1], Decimal(str(row[2]))) for row in cur.fetchall()}
    finally:
        conn.close()


def _pg_connect(pg: dict):
    return psycopg2.connect(host=pg["host"], port=int(pg["port"]), user=pg["user"],
                          password=pg["password"], dbname=pg["database"])


# ---------------------------------------------------------------------- 对账
def row_digest(rows: dict[int, tuple]) -> str:
    canonical = "\n".join(
        f"{i}|{name}|{format(money, 'f')}" for i, (name, money) in sorted(rows.items())
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


def assert_sampled_rows_match(mysql: dict, pg: dict, schema: str, table: str,
                              total_rows: int, sample_size: int = 100,
                              sample_seed: int = 42) -> None:
    """抽样两边各取同一批 id，行数、抽样数、规范化后的 sha256 全部对齐才过。"""
    sampled = random.Random(sample_seed).sample(range(1, total_rows + 1), sample_size)

    src_rows = fetch_source_rows(mysql, table, sampled)
    assert len(src_rows) == sample_size, \
        f"source returned {len(src_rows)} of {sample_size} sampled rows"

    tgt_count = target_row_count(pg, schema, table)
    assert tgt_count == total_rows, f"target row count {tgt_count} != {total_rows}"

    tgt_rows = fetch_target_rows(pg, schema, table, sampled)
    assert len(tgt_rows) == sample_size, \
        f"target returned {len(tgt_rows)} of {sample_size} sampled rows"

    src_hash, tgt_hash = row_digest(src_rows), row_digest(tgt_rows)
    assert src_hash == tgt_hash, (
        f"hash mismatch: src={src_hash[:16]} tgt={tgt_hash[:16]}; "
        f"first differing ids: "
        f"{[i for i in sampled if src_rows.get(i) != tgt_rows.get(i)][:5]}"
    )
