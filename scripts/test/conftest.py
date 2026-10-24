"""Shared fixtures for every test case in this directory.

The profile runner (framework/runner.py) starts one pytest subprocess per stage and
passes the profile YAML in via --profile. Everything a case needs comes from fixtures:

  env        session:  profile env section (endpoint, datasources), ${VAR} expanded
  case_ctx   module:   identity of the current case — the isolation namespace

Contract between runner and cases: test_cases/ is flat, case `x` is
test_cases/x.py, and the stage's `tests` list selects the files. Group-level
prepare/clean is a module-scoped fixture written inside the case file; under
xdist it runs once per worker, so cases must isolate every shared resource
through case_ctx.ns(...).
"""

from __future__ import annotations

import dataclasses
import os
import re
import uuid

import pytest
import yaml

_VAR_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption(
        "--profile",
        required=True,
        help="profile YAML path (passed by framework/runner.py)",
    )


def _expand(value):
    """Recursively expand ${VAR} references from the process environment."""
    if isinstance(value, dict):
        return {key: _expand(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_expand(item) for item in value]
    if isinstance(value, str):

        def replace(match: re.Match) -> str:
            name = match.group(1)
            if name not in os.environ:
                raise RuntimeError(f"profile references unset environment variable: {name}")
            return os.environ[name]

        return _VAR_RE.sub(replace, value)
    return value


@dataclasses.dataclass
class Env:
    """The env section of a profile, with variables expanded."""

    raw: dict

    @property
    def endpoint(self) -> dict:
        return self.raw["endpoint"]

    @property
    def datasources(self) -> dict:
        return self.raw.get("datasources", {})


@pytest.fixture(scope="session")
def env(request: pytest.FixtureRequest) -> Env:
    profile_path = request.config.getoption("--profile")
    with open(profile_path, encoding="utf-8") as handle:
        profile = yaml.safe_load(handle)
    if "env" not in profile:
        raise RuntimeError(f"profile {profile_path} has no env section")
    return Env(_expand(profile["env"]))


@dataclasses.dataclass
class CaseContext:
    """Identity + isolation namespace of one case.

    Every shared resource (task names, schemas, tables) must be created through
    ns() so parallel cases never collide.
    """

    group: str
    case: str
    worker: str
    case_id: str

    def ns(self, name: str) -> str:
        return f"{name}_{self.case_id}"


@pytest.fixture(scope="module")
def case_ctx(request: pytest.FixtureRequest) -> CaseContext:
    # Flat layout: the case is the file stem; the group is the stage the runner
    # put this file in (PYTEST_STAGE), which the file cannot know by itself.
    case = request.node.path.stem
    worker = getattr(request.config, "workerinput", {}).get("workerid", "master")
    return CaseContext(
        group=os.environ.get("PYTEST_STAGE", "default"),
        case=case,
        worker=worker,
        case_id=uuid.uuid4().hex[:8],
    )
