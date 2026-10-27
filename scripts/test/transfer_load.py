#!/usr/bin/env python3
"""consistency 库的转账负载 / 造数工具。

不变式：任意时刻每张表 sum(money) = 初始总额（转账只在用户间搬运，不增不减）。

用法（scripts/test 目录下，复用 uv 环境）:
  # 造数：10 张表 × 10 万行，money=1
  uv run python transfer_load.py seed --host 127.0.0.1 --port 3306 --user test \
      --password 123456 --database consistency --tables 10 --rows 100000

  # 负载：32 线程跑 12 分钟，每事务随机挑 2 个用户 A->B 转 1 元
  uv run python transfer_load.py run --host 127.0.0.1 --port 3306 --user test \
      --password 123456 --database consistency --tables 10 --rows 100000 \
      --threads 32 --duration-sec 720

并发安全：每事务把 2 个 id 升序排序后按序 FOR UPDATE 加锁——所有事务遵守全局
一致的加锁顺序，理论无死锁；A 余额不足则整事务跳过。成功的事务恰好改变 2 行
（A -1、B +1），binlog 恰好 2 个行事件，任何提交点总额守恒。
"""

from __future__ import annotations

import argparse
import random
import threading
import time

import pymysql

BATCH = 10_000


def connect(args):
    return pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=args.password, database=args.database,
                           autocommit=False)


def seed(args) -> None:
    for t in range(1, args.tables + 1):
        conn = connect(args)
        try:
            with conn.cursor() as cur:
                cur.execute(f"TRUNCATE TABLE user_wallet_{t}")
                batch = []
                for i in range(1, args.rows + 1):
                    batch.append((i, f"user-{i:06d}", 1))
                    if len(batch) >= BATCH:
                        cur.executemany(
                            f"INSERT INTO user_wallet_{t} (id, name, money) VALUES (%s, %s, %s)",
                            batch)
                        batch.clear()
                if batch:
                    cur.executemany(
                        f"INSERT INTO user_wallet_{t} (id, name, money) VALUES (%s, %s, %s)",
                        batch)
            conn.commit()
            print(f"user_wallet_{t}: {args.rows} rows, sum(money) = {args.rows}")
        finally:
            conn.close()


def worker(args, stop_at: float, stats: dict, lock: threading.Lock) -> None:
    conn = connect(args)
    rnd = random.Random()
    committed = 0
    try:
        while time.time() < stop_at:
            t = rnd.randint(1, args.tables)
            a, b = rnd.sample(range(1, args.rows + 1), 2)  # A -> B，单事务单笔转账
            low, high = min(a, b), max(a, b)  # 升序加锁，全局一致顺序，理论无死锁
            try:
                with conn.cursor() as cur:
                    # 先按 id 升序加锁（FOR UPDATE），再转账
                    cur.execute(
                        f"SELECT id, money FROM user_wallet_{t} WHERE id = %s FOR UPDATE", (low,))
                    row_low = cur.fetchone()
                    cur.execute(
                        f"SELECT id, money FROM user_wallet_{t} WHERE id = %s FOR UPDATE", (high,))
                    row_high = cur.fetchone()
                    if row_low is None or row_high is None:
                        raise RuntimeError(f"missing id in user_wallet_{t}")
                    bal = {row_low[0]: row_low[1], row_high[0]: row_high[1]}
                    if bal[a] < args.amount:
                        conn.rollback()  # A 余额不足：整事务跳过
                        continue
                    bal[a] -= args.amount
                    bal[b] += args.amount
                    for uid in (low, high):  # 写回也按加锁顺序
                        cur.execute(
                            f"UPDATE user_wallet_{t} SET money = %s WHERE id = %s",
                            (bal[uid], uid))
                conn.commit()
                committed += 1
            except Exception:
                conn.rollback()
        with lock:
            stats["committed"] += committed
    finally:
        conn.close()


def run(args) -> None:
    stats = {"committed": 0}
    lock = threading.Lock()
    stop_at = time.time() + args.duration_sec
    threads = [threading.Thread(target=worker, args=(args, stop_at, stats, lock),
                                daemon=True) for _ in range(args.threads)]
    start = time.time()
    for th in threads:
        th.start()
    last = 0
    while any(th.is_alive() for th in threads):
        time.sleep(10)
        with lock:
            total = stats["committed"]
        elapsed = int(time.time() - start)
        print(f"[{elapsed:4d}s] committed={total} (+{total - last} in 10s)", flush=True)
        last = total
    print(f"done: {stats['committed']} transfers committed in {int(time.time() - start)}s")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("seed", "run"):
        p = sub.add_parser(name)
        p.add_argument("--host", default="127.0.0.1")
        p.add_argument("--port", type=int, required=True)
        p.add_argument("--user", required=True)
        p.add_argument("--password", required=True)
        p.add_argument("--database", required=True)
        p.add_argument("--tables", type=int, default=10)
        p.add_argument("--rows", type=int, default=100_000)
        if name == "run":
            p.add_argument("--threads", type=int, default=32)
            p.add_argument("--duration-sec", type=int, default=720)
            p.add_argument("--amount", type=int, default=1)
    args = ap.parse_args()
    seed(args) if args.cmd == "seed" else run(args)


if __name__ == "__main__":
    main()
