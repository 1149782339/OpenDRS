#!/usr/bin/env python3
"""资金守恒检查：单个 REPEATABLE READ 快照内逐表核对 sum(money) 与行数。

退出码 0 = 每张表都等于期望值（总额守恒），1 = 有不一致。

用法:
  uv run python check_money_conservation.py --type mysql --host 127.0.0.1 --port 3306 \
      --user test --password 123456 --database consistency \
      --schema consistency --tables 10 --rows 100000
"""

from __future__ import annotations

import argparse
import sys


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--type", choices=["mysql", "postgres"], required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--user", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--database", required=True)
    ap.add_argument("--schema", default=None, help="PG schema（默认与 database 同名）")
    ap.add_argument("--tables", type=int, default=10)
    ap.add_argument("--rows", type=int, default=100_000)
    args = ap.parse_args()

    if args.type == "mysql":
        import pymysql
        conn = pymysql.connect(host=args.host, port=args.port, user=args.user,
                               password=args.password, database=args.database,
                               autocommit=False)
        with conn.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        q = lambda cur, t: cur.execute(f"SELECT COUNT(*), COALESCE(SUM(money), -1) FROM {t}")
    else:
        import psycopg2
        conn = psycopg2.connect(host=args.host, port=args.port, user=args.user,
                                password=args.password, dbname=args.database)
        conn.set_session(isolation_level="REPEATABLE READ", autocommit=False)
        schema = args.schema or args.database
        q = lambda cur, t: cur.execute(
            f'SELECT COUNT(*), COALESCE(SUM(money), -1) FROM "{schema}"."{t}"')

    failures = []
    per_table = []
    with conn.cursor() as cur:
        for i in range(1, args.tables + 1):
            q(cur, f"user_wallet_{i}")
            count, total = cur.fetchone()
            ok = count == args.rows and total == args.rows  # money 初始全 1，总额 == 行数
            per_table.append(f"w{i}: count={count} sum={total}{'' if ok else ' <-- MISMATCH'}")
            if not ok:
                failures.append(f"user_wallet_{i}: count={count} (expect {args.rows}), "
                                f"sum(money)={total} (expect {args.rows})")
    conn.rollback()
    conn.close()

    print("; ".join(per_table))
    if failures:
        print(f"FAILED: {len(failures)} 张表不守恒")
        for f in failures[:10]:
            print("  -", f)
        return 1
    print("OK: 全部表资金守恒")
    return 0


if __name__ == "__main__":
    sys.exit(main())
