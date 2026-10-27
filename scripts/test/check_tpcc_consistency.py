#!/usr/bin/env python3
"""TPC-C 数据一致性检查（HammerDB v4.10 checkschema 的蓝本移植，适配 BenchmarkSQL 的 bmsql_* 表）。

检查项（TPC 规范定义的 4 条一致性条件 + 行数/结构 sanity，全部为主键/数值聚合关系，
不碰 timestamp——正好绕开迁移两端时区偏移不一致的坑）。整个检查在单个 REPEATABLE READ
快照内完成，压测进行中也能给出某个真实时间点的结论，不会被并发提交晃出假不一致。

  1. 每个区: d_next_o_id - 1 = max(o_id) = max(no_o_id)
  2. 每个区: max(no_o_id) - min(no_o_id) + 1 = new_order 行数（delivery 从最老的删，区间保持连续）
  3. 每个区: sum(o_ol_cnt) = order_line 行数
  4. 每个仓: sum(d_ytd) = w_ytd

用法（在 scripts/test 目录下，复用框架的 uv 环境）:
  uv run python check_tpcc_consistency.py --type mysql --host 127.0.0.1 --port 3306 \
      --user test --password 123456 --database benchmark
  uv run python check_tpcc_consistency.py --type postgres ... --schema benchmark

退出码 0 = 全部通过，1 = 有失败项。
"""

from __future__ import annotations

import argparse
import sys
from decimal import Decimal

TABLES = [
    "bmsql_warehouse", "bmsql_district", "bmsql_customer", "bmsql_history",
    "bmsql_oorder", "bmsql_new_order", "bmsql_order_line", "bmsql_item",
    "bmsql_stock", "bmsql_config",
]


def open_conn(args):
    if args.type == "mysql":
        import pymysql
        conn = pymysql.connect(host=args.host, port=args.port, user=args.user,
                               password=args.password, database=args.database,
                               autocommit=False)
        # 整个检查跑在同一个 REPEATABLE READ 快照里：活跃负载下相邻查询之间
        # 并发事务的提交会造成"假不一致"，快照隔离让每次检查都对应一个真实的
        # 时间点状态——这正是"事务一致性"检查该有的语义。
        with conn.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        return conn
    import psycopg2
    conn = psycopg2.connect(host=args.host, port=args.port, user=args.user,
                            password=args.password, dbname=args.database)
    conn.set_session(isolation_level="REPEATABLE READ", autocommit=False)
    return conn


def q(conn, sql, params=()):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--type", choices=["mysql", "postgres"], required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--user", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--database", required=True)
    ap.add_argument("--schema", default=None,
                    help="PG 目标 schema（默认与 database 同名；MySQL 忽略）")
    args = ap.parse_args()

    schema = args.schema or args.database
    # MySQL 的 "schema" 就是库名，表已限定在连接库内；PG 需要显式 schema 限定。
    t = (lambda name: f'"{schema}"."{name}"' if args.type == "postgres" else name)

    conn = open_conn(args)
    failures: list[str] = []

    # ---------------- L0: 行数 sanity
    print("== 行数 ==")
    counts = {}
    for table in TABLES:
        (n,) = q(conn, f"SELECT COUNT(*) FROM {t(table)}")[0]
        counts[table] = n
        print(f"  {table:20s} {n:>12,}")
    w = counts["bmsql_warehouse"]
    if counts["bmsql_district"] != w * 10:
        failures.append(f"district 行数 {counts['bmsql_district']} != warehouse×10 ({w * 10})")
    if counts["bmsql_item"] != 100000:
        failures.append(f"item 行数 {counts['bmsql_item']} != 100000")
    for tbl, each in (("bmsql_customer", 30000), ("bmsql_history", 30000),
                      ("bmsql_oorder", 30000)):
        if counts[tbl] < w * each:
            failures.append(f"{tbl} 行数 {counts[tbl]} < warehouse×{each} ({w * each})")

    # ---------------- 条件 1: d_next_o_id - 1 = max(o_id) = max(no_o_id)
    print("== 条件1: d_next_o_id-1 = max(o_id) = max(no_o_id) ==")
    rows = q(conn, f"""
        SELECT d.d_w_id, d.d_id, d.d_next_o_id, mo.max_o, mno.max_no
        FROM {t('bmsql_district')} d
        LEFT JOIN (SELECT o_w_id, o_d_id, MAX(o_id) AS max_o
                   FROM {t('bmsql_oorder')} GROUP BY o_w_id, o_d_id) mo
               ON mo.o_w_id = d.d_w_id AND mo.o_d_id = d.d_id
        LEFT JOIN (SELECT no_w_id, no_d_id, MAX(no_o_id) AS max_no
                   FROM {t('bmsql_new_order')} GROUP BY no_w_id, no_d_id) mno
               ON mno.no_w_id = d.d_w_id AND mno.no_d_id = d.d_id
        ORDER BY d.d_w_id, d.d_id""")
    bad = 0
    for d_w_id, d_id, next_o, max_o, max_no in rows:
        if next_o - 1 != max_o or next_o - 1 != max_no:
            bad += 1
            if bad <= 5:
                failures.append(
                    f"w{d_w_id} d{d_id}: d_next_o_id-1={next_o - 1} max(o_id)={max_o} max(no_o_id)={max_no}")
    print(f"  检查 {len(rows)} 个区, 失败 {bad}")
    if bad > 5:
        failures.append(f"条件1 共 {bad} 个区失败（仅显示前 5）")

    # ---------------- 条件 2: max(no_o_id) - min(no_o_id) + 1 = new_order 行数
    print("== 条件2: new_order 区间连续 ==")
    rows = q(conn, f"""
        SELECT no_w_id, no_d_id, COUNT(*), MIN(no_o_id), MAX(no_o_id)
        FROM {t('bmsql_new_order')} GROUP BY no_w_id, no_d_id""")
    bad = 0
    for no_w_id, no_d_id, cnt, min_no, max_no in rows:
        if max_no - min_no + 1 != cnt:
            bad += 1
            if bad <= 5:
                failures.append(
                    f"w{no_w_id} d{no_d_id}: count={cnt} min={min_no} max={max_no} 不连续")
    print(f"  检查 {len(rows)} 个区, 失败 {bad}")
    if bad > 5:
        failures.append(f"条件2 共 {bad} 个区失败（仅显示前 5）")

    # ---------------- 条件 3: sum(o_ol_cnt) = order_line 行数
    print("== 条件3: sum(o_ol_cnt) = order_line 行数 ==")
    sums = {(r[0], r[1]): r[2] for r in q(conn, f"""
        SELECT o_w_id, o_d_id, SUM(o_ol_cnt) FROM {t('bmsql_oorder')} GROUP BY o_w_id, o_d_id""")}
    lines = {(r[0], r[1]): r[2] for r in q(conn, f"""
        SELECT ol_w_id, ol_d_id, COUNT(*) FROM {t('bmsql_order_line')} GROUP BY ol_w_id, ol_d_id""")}
    bad = 0
    for key in sorted(set(sums) | set(lines)):
        if sums.get(key) != lines.get(key):
            bad += 1
            if bad <= 5:
                failures.append(f"w{key[0]} d{key[1]}: sum(o_ol_cnt)={sums.get(key)} order_line={lines.get(key)}")
    print(f"  检查 {len(set(sums) | set(lines))} 个区, 失败 {bad}")
    if bad > 5:
        failures.append(f"条件3 共 {bad} 个区失败（仅显示前 5）")

    # ---------------- 条件 4: sum(d_ytd) = w_ytd
    print("== 条件4: sum(d_ytd) = w_ytd ==")
    d_sums = {(r[0]): Decimal(str(r[1])) for r in q(conn, f"""
        SELECT d_w_id, SUM(d_ytd) FROM {t('bmsql_district')} GROUP BY d_w_id""")}
    w_ytd = {r[0]: Decimal(str(r[1])) for r in q(conn, f"""
        SELECT w_id, w_ytd FROM {t('bmsql_warehouse')}""")}
    bad = 0
    for w_id in sorted(set(d_sums) | set(w_ytd)):
        a, b = d_sums.get(w_id), w_ytd.get(w_id)
        if a is None or b is None or a.quantize(Decimal("0.01")) != b.quantize(Decimal("0.01")):
            bad += 1
            if bad <= 5:
                failures.append(f"w{w_id}: sum(d_ytd)={a} w_ytd={b}")
    print(f"  检查 {len(set(d_sums) | set(w_ytd))} 个仓, 失败 {bad}")
    if bad > 5:
        failures.append(f"条件4 共 {bad} 个仓失败（仅显示前 5）")

    conn.rollback()  # 只读快照，释放事务
    conn.close()

    print()
    if failures:
        print(f"FAILED: {len(failures)} 项不一致")
        for f in failures[:20]:
            print("  -", f)
        return 1
    print("OK: 全部一致性检查通过")
    return 0

if __name__ == "__main__":
    sys.exit(main())
