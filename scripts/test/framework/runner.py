"""Profile runner: ordered stages, cases within a stage in parallel.

Usage (from scripts/test):

    uv run python -m framework.runner --profile profiles/ci.yml

Profile YAML contract:

    env:        endpoint/datasources handed to cases via the `env` fixture
    config:     max_parallel, case_timeout_sec (framework scheduling)
    testcases:  ordered list of stages; a stage runs to green before the next
                starts. A stage without `tests` runs the whole group directory;
                with `tests` only those cases (test_cases/<group>/test_<case>.py).

Each stage is one pytest subprocess (xdist for intra-stage parallelism), which
keeps the semantics honest: nothing in stage N+1 starts before stage N is fully
green, and a stage's cases share nothing except what they explicitly prepare.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

import yaml

TEST_ROOT = Path(__file__).resolve().parent.parent
TEST_CASES = TEST_ROOT / "test_cases"


def run_stage(profile_path: Path, stage: dict, config: dict, reports_dir: Path,
              verbose: bool) -> int:
    name = stage["name"]
    tests = stage.get("tests")
    group_dir = TEST_CASES / name
    if not group_dir.is_dir():
        print(f"[stage:{name}] ERROR: no such group directory {group_dir}", flush=True)
        return 2

    if tests:
        targets = []
        for case in tests:
            path = group_dir / f"test_{case}.py"
            if not path.is_file():
                print(f"[stage:{name}] ERROR: case '{case}' not found at {path}", flush=True)
                return 2
            targets.append(str(path))
    else:
        targets = [str(group_dir)]

    max_parallel = int(config.get("max_parallel", 4))
    timeout_sec = int(config.get("case_timeout_sec", 900))
    workers = min(max_parallel, os.cpu_count() or max_parallel)

    reports_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        sys.executable, "-m", "pytest",
        "-x" if verbose else "-q",
        "-p", "no:cacheprovider",
        "-n", str(workers),
        "--dist", "loadfile",
        f"--timeout={timeout_sec}",
        # SIGALRM does not exist on Windows; the thread method is portable and
        # plenty for API-polling cases (it cannot interrupt a hung C extension,
        # which our cases never call).
        "--timeout_method=thread",
        f"--junitxml={reports_dir / (name + '.xml')}",
        "--profile", str(profile_path),
        *targets,
    ]
    print(f"[stage:{name}] pytest -n {workers} ({len(tests) if tests else 'all'} case(s), "
          f"timeout {timeout_sec}s)", flush=True)
    started = time.monotonic()
    result = subprocess.run(cmd, cwd=TEST_ROOT)
    elapsed = time.monotonic() - started
    mark = "PASS" if result.returncode == 0 else "FAIL"
    print(f"[stage:{name}] {mark} in {elapsed:.1f}s (exit {result.returncode})", flush=True)
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--profile", required=True, help="profile YAML path")
    parser.add_argument("--reports-dir", default="reports",
                        help="junit XML output directory (default: reports/)")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="pytest verbose output per stage")
    args = parser.parse_args()

    profile_path = Path(args.profile).resolve()
    with open(profile_path, encoding="utf-8") as handle:
        profile = yaml.safe_load(handle)
    config = profile.get("config", {})
    stages = profile.get("testcases", [])
    if not stages:
        print("profile has no testcases section", file=sys.stderr)
        return 2

    print(f"profile: {profile_path.name}  stages: {[s['name'] for s in stages]}",
          flush=True)
    started = time.monotonic()
    for stage in stages:
        code = run_stage(profile_path, stage, config,
                         (TEST_ROOT / args.reports_dir).resolve(), args.verbose)
        if code != 0:
            print(f"\nprofile aborted: stage '{stage['name']}' failed "
                  f"({time.monotonic() - started:.1f}s elapsed)", flush=True)
            return code

    print(f"\nprofile OK — all {len(stages)} stage(s) green "
          f"in {time.monotonic() - started:.1f}s; reports in {args.reports_dir}/",
          flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
