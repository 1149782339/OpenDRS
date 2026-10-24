"""E2E full migration: source MySQL -> target PostgreSQL, then row-level hash compare.

Flow:
  1. Wipe the target table, (re)create user_wallet and seed 10,000 deterministic rows
     on the source MySQL.
  2. Create the two connections and a FULL_ONLY task via the REST API (api.py).
  3. Run precheck, start the task, poll status until COMPLETED.
  4. Sample 100 ids, fetch both sides, normalize rows, sha256 compare.

Run (from scripts/test, app stack + data-source stack up):
    uv run e2e_test.py

Environment:
    OPENDRS_BASE_URL  default http://127.0.0.1
    SOURCE_HOST/PORT/DB/USER/PASSWORD   default 127.0.0.1:3406 e2e_source e2e/123456
    TARGET_HOST/PORT/DB/USER/PASSWORD   default 127.0.0.1:5432  e2e_target  opendrs/123456
"""

from __future__ import annotations

import hashlib
import os
import random
import sys
import time
from decimal import Decimal

import psycopg2
import pymysql

from api import ApiError, OpenDrsApi

ROW_COUNT = 10_000
SAMPLE_SIZE = 100
SAMPLE_SEED = 42

TABLE = "user_wallet"
BATCH = 1_000
PRECHECK_POLL_SECONDS = 2.0
STATUS_POLL_SECONDS = 2.0
TASK_TIMEOUT_SECONDS = float(os.environ.get("E2E_TASK_TIMEOUT", "600"))


def env(prefix: str, defaults: dict[str, str]) -> dict[str, str]:
    return {key: os.environ.get(prefix + key.upper(), value) for key, value in defaults.items()}


SOURCE = env("SOURCE_", {"host": "127.0.0.1", "port": "3406", "db": "e2e_db",
                         "user": "e2e_mysql", "password": "123456"})
TARGET = env("TARGET_", {"host": "127.0.0.1", "port": "5432", "db": "e2e_db",
                         "user": "e2e_postgres", "password": "123456"})

# MySQL database name maps to a same-named PostgreSQL schema during schema migration.
PG_SCHEMA = SOURCE["db"]


def ensure_source_privileges() -> None:
    """Precheck requires SELECT/RELOAD/REPLICATION SLAVE/REPLICATION CLIENT/SHOW DATABASES.
    The container's MYSQL_USER only gets schema-level rights, so grant the CDC set as root."""
    root_password = os.environ.get("SOURCE_ROOT_PASSWORD", "123456")
    conn = pymysql.connect(host=SOURCE["host"], port=int(SOURCE["port"]), user="root",
                           password=root_password, autocommit=True)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "GRANT SELECT, RELOAD, REPLICATION SLAVE, REPLICATION CLIENT, SHOW DATABASES "
                "ON *.* TO %s@'%%'", (SOURCE["user"],)
            )
        print("[seed] CDC privileges granted to source user")
    finally:
        conn.close()


def seed_source() -> None:
    print(f"[seed] preparing {TABLE} on MySQL {SOURCE['host']}:{SOURCE['port']}/{SOURCE['db']}")
    ensure_source_privileges()
    conn = pymysql.connect(host=SOURCE["host"], port=int(SOURCE["port"]), user=SOURCE["user"],
                           password=SOURCE["password"], database=SOURCE["db"], autocommit=False)
    try:
        with conn.cursor() as cur:
            cur.execute(f"DROP TABLE IF EXISTS {TABLE}")
            cur.execute(
                "CREATE TABLE {} ("
                "  id INT PRIMARY KEY AUTO_INCREMENT,"
                "  name VARCHAR(64) NOT NULL,"
                "  money DECIMAL(20,2) NOT NULL"
                ")".format(TABLE)
            )
            rows = (
                (f"user-{i:05d}", Decimal(i % 997) * Decimal("1.25"))
                for i in range(1, ROW_COUNT + 1)
            )
            batch = []
            for i, row in enumerate(rows, 1):
                batch.append(row)
                if i % BATCH == 0:
                    cur.executemany(f"INSERT INTO {TABLE} (name, money) VALUES (%s, %s)", batch)
                    batch.clear()
            if batch:
                cur.executemany(f"INSERT INTO {TABLE} (name, money) VALUES (%s, %s)", batch)
            cur.execute(f"SELECT COUNT(*) FROM {TABLE}")
            count = cur.fetchone()[0]
        conn.commit()
        print(f"[seed] {count} rows inserted")
        if count != ROW_COUNT:
            raise RuntimeError(f"source seed count {count} != {ROW_COUNT}")
    finally:
        conn.close()


def wipe_target() -> None:
    print(f"[seed] wiping {TABLE} on PostgreSQL {TARGET['host']}:{TARGET['port']}/{TARGET['db']}")
    conn = psycopg2.connect(host=TARGET["host"], port=int(TARGET["port"]), user=TARGET["user"],
                            password=TARGET["password"], dbname=TARGET["db"])
    try:
        with conn.cursor() as cur:
            cur.execute(f'DROP TABLE IF EXISTS "{PG_SCHEMA}"."{TABLE}" CASCADE')
        conn.commit()
    finally:
        conn.close()


def run_migration(api: OpenDrsApi) -> int:
    suffix = time.strftime("%H%M%S")
    src = api.create_connection(f"e2e-source-{suffix}", {
        "type": "MYSQL",
        "host": SOURCE["host"], "port": int(SOURCE["port"]), "database": SOURCE["db"],
        "username": SOURCE["user"], "password": SOURCE["password"],
    })
    tgt = api.create_connection(f"e2e-target-{suffix}", {
        "type": "POSTGRESQL",
        "host": TARGET["host"], "port": int(TARGET["port"]), "database": TARGET["db"],
        "username": TARGET["user"], "password": TARGET["password"],
    })
    print(f"[api] connections: source={src['id']} target={tgt['id']}")

    task = api.create_task(f"e2e-full-{time.strftime('%H%M%S')}", "FULL_ONLY", src["id"], tgt["id"],
                           options={"topology": "ONE_WAY", "selection": {"mode": "ALL"}})
    task_id = task["id"]
    print(f"[api] task created: id={task_id}")

    print("[api] precheck ...")
    api.start_precheck(task_id)
    while True:
        precheck = api.get_precheck(task_id)
        state = precheck.get("state")
        phase = precheck.get("phase")
        running = precheck.get("runningId")
        print(f"[api]   precheck phase={phase} state={state} running={running}")
        if state == "FAILED":
            raise RuntimeError(f"precheck failed: {precheck.get('items')}")
        # ok is only true once phase reaches PRECHECKED and the plan is admitted; polling on
        # runningId alone races the gap between submit and the first check starting.
        if precheck.get("ok"):
            break
        time.sleep(PRECHECK_POLL_SECONDS)
    print("[api] precheck ok")

    print("[api] start task")
    api.start_task(task_id)
    deadline = time.monotonic() + TASK_TIMEOUT_SECONDS
    while True:
        status = api.get_status(task_id)
        state = status.get("state")
        phase = status.get("phase")
        print(f"[api]   state={state} phase={phase}")
        if state == "COMPLETED":
            break
        if state in ("FAILED", "TERMINATED"):
            raise RuntimeError(f"task ended in {state}: {status.get('error')}")
        if time.monotonic() > deadline:
            raise RuntimeError(f"task did not complete within {TASK_TIMEOUT_SECONDS:.0f}s")
        time.sleep(STATUS_POLL_SECONDS)
    print("[api] task COMPLETED")
    return task_id


def fetch_source(ids: list[int]) -> dict[int, tuple]:
    conn = pymysql.connect(host=SOURCE["host"], port=int(SOURCE["port"]), user=SOURCE["user"],
                           password=SOURCE["password"], database=SOURCE["db"])
    try:
        with conn.cursor() as cur:
            placeholders = ",".join(["%s"] * len(ids))
            cur.execute(f"SELECT id, name, money FROM {TABLE} WHERE id IN ({placeholders})", ids)
            return {row[0]: (row[1], Decimal(str(row[2]))) for row in cur.fetchall()}
    finally:
        conn.close()


def fetch_target(ids: list[int]) -> dict[int, tuple]:
    conn = psycopg2.connect(host=TARGET["host"], port=int(TARGET["port"]), user=TARGET["user"],
                            password=TARGET["password"], dbname=TARGET["db"])
    try:
        with conn.cursor() as cur:
            cur.execute(f'SELECT id, name, money FROM "{PG_SCHEMA}"."{TABLE}" WHERE id = ANY(%s::int[])', (ids,))
            return {row[0]: (row[1], Decimal(str(row[2]))) for row in cur.fetchall()}
    finally:
        conn.close()


def row_digest(rows: dict[int, tuple]) -> str:
    canonical = "\n".join(
        f"{i}|{name}|{format(money, 'f')}" for i, (name, money) in sorted(rows.items())
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


def verify() -> None:
    print(f"[verify] sampling {SAMPLE_SIZE} of {ROW_COUNT} rows")
    sampled = random.Random(SAMPLE_SEED).sample(range(1, ROW_COUNT + 1), SAMPLE_SIZE)

    src_rows = fetch_source(sampled)
    if len(src_rows) != SAMPLE_SIZE:
        raise RuntimeError(f"source returned {len(src_rows)} of {SAMPLE_SIZE} sampled rows")

    conn = psycopg2.connect(host=TARGET["host"], port=int(TARGET["port"]), user=TARGET["user"],
                            password=TARGET["password"], dbname=TARGET["db"])
    try:
        with conn.cursor() as cur:
            cur.execute(f'SELECT COUNT(*) FROM "{PG_SCHEMA}"."{TABLE}"')
            target_count = cur.fetchone()[0]
    finally:
        conn.close()
    if target_count != ROW_COUNT:
        raise RuntimeError(f"target row count {target_count} != {ROW_COUNT}")

    tgt_rows = fetch_target(sampled)
    if len(tgt_rows) != SAMPLE_SIZE:
        raise RuntimeError(f"target returned {len(tgt_rows)} of {SAMPLE_SIZE} sampled rows")

    src_hash, tgt_hash = row_digest(src_rows), row_digest(tgt_rows)
    print(f"[verify] target count ok ({target_count}), source sha={src_hash[:16]}...")
    if src_hash != tgt_hash:
        diff = [i for i in sampled if src_rows.get(i) != tgt_rows.get(i)]
        raise RuntimeError(f"hash mismatch; first differing ids: {diff[:5]}")
    print("[verify] sampled hashes match — migration data consistent")


def main() -> int:
    api = OpenDrsApi()
    try:
        wipe_target()
        seed_source()
        run_migration(api)
        verify()
    except (ApiError, RuntimeError) as error:
        print(f"[e2e] FAILED: {error}", file=sys.stderr)
        return 1
    print("[e2e] PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
