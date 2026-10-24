"""业务层门面：测试用例只依赖这里的领域语义，不直接触碰 REST 细节。

当 api.py 里的端点、请求体、包壳结构或响应字段变化时，只有本文件需要适配；
用例调用保持稳定（create_datasource / create_migration_task /
precheck / wait_completed ...）。

依赖方向：用例 -> facade -> api.py -> REST。绝不允许反向 import。
"""

from __future__ import annotations

import dataclasses
import time

from api import ApiError, OpenDrsApi


class FacadeError(RuntimeError):
    """业务层错误：预检查不通过、任务失败、超时等。"""


@dataclasses.dataclass
class Datasource:
    id: int
    name: str


@dataclasses.dataclass
class TaskStatus:
    """任务快照——用例拿到的只有语义字段，不是原始响应 dict。"""

    id: int
    state: str | None
    phase: str | None
    error: str | None


@dataclasses.dataclass
class PrecheckStatus:
    ok: bool
    phase: str | None
    state: str | None


class MigrationTask:
    """一个迁移任务的生命周期：precheck -> start -> COMPLETED/FAILED。"""

    def __init__(self, facade: "OpenDrs", task_id: int, name: str) -> None:
        self._facade = facade
        self.id = task_id
        self.name = name

    # ------------------------------------------------------------------ precheck
    def precheck(self) -> None:
        self._facade._api.start_precheck(self.id)

    def precheck_status(self) -> PrecheckStatus:
        data = self._facade._api.get_precheck(self.id)
        return PrecheckStatus(
            ok=bool(data.get("ok")),
            phase=data.get("phase"),
            state=data.get("state"),
        )

    def wait_precheck_ok(self, timeout: float = 300, poll: float = 2) -> None:
        deadline = time.monotonic() + timeout
        while True:
            status = self.precheck_status()
            if status.ok:
                return
            if status.state == "FAILED":
                raise FacadeError(f"precheck failed for task {self.id}")
            if time.monotonic() > deadline:
                raise FacadeError(f"precheck did not pass within {timeout:.0f}s")
            time.sleep(poll)

    # ------------------------------------------------------------------ run
    def start(self) -> None:
        self._facade._api.start_task(self.id)

    def terminate(self) -> None:
        """Stop a streaming (FULL_AND_INCREMENTAL / INCREMENTAL_ONLY) task.

        Required before delete: the backend refuses to delete a task that is
        still running.
        """
        self._facade._api._request("POST", f"/api/v1/migration/tasks/{self.id}/terminate")

    def status(self) -> TaskStatus:
        data = self._facade._api.get_status(self.id)
        return TaskStatus(
            id=self.id,
            state=data.get("state"),
            phase=data.get("phase"),
            error=data.get("error"),
        )

    def wait_completed(self, timeout: float = 900, poll: float = 2) -> TaskStatus:
        deadline = time.monotonic() + timeout
        while True:
            status = self.status()
            if status.state == "COMPLETED":
                return status
            if status.state in ("FAILED", "TERMINATED"):
                raise FacadeError(f"task {self.id} ended in {status.state}: {status.error}")
            if time.monotonic() > deadline:
                raise FacadeError(f"task {self.id} did not complete within {timeout:.0f}s")
            time.sleep(poll)


class OpenDrs:
    """OpenDRS 业务门面入口。"""

    def __init__(self, base_url: str = "http://127.0.0.1") -> None:
        self._api = OpenDrsApi(base_url)

    # ------------------------------------------------------------------ datasources
    def list_datasources(self) -> list[Datasource]:
        rows = self._api.list_connections()
        return [Datasource(id=row["id"], name=row["name"]) for row in rows]

    def create_datasource(self, name: str, db_type: str, host: str, port: int,
                          database: str, username: str, password: str) -> Datasource:
        data = self._api.create_connection(name, {
            "type": db_type,
            "host": host,
            "port": port,
            "database": database,
            "username": username,
            "password": password,
        })
        return Datasource(id=data["id"], name=name)

    def test_datasource(self, datasource: Datasource) -> bool:
        """Ping the datasource; True means reachable. Latency stays server-side."""
        try:
            result = self._api._request(
                "POST", f"/api/v1/migration/connections/{datasource.id}/test")
            return bool(result.get("ok"))
        except ApiError as error:
            raise FacadeError(f"test datasource {datasource.id} failed: {error}") from error

    def delete_datasource(self, datasource: Datasource) -> None:
        self._api.delete_connection(datasource.id)

    # ------------------------------------------------------------------ tasks
    def create_migration_task(self, name: str, mode: str, source: Datasource,
                              target: Datasource,
                              options: dict | None = None) -> MigrationTask:
        data = self._api.create_task(name, mode, source.id, target.id, options)
        return MigrationTask(self, task_id=data["id"], name=name)

    def delete_task(self, task: MigrationTask) -> None:
        self._api._request("DELETE", f"/api/v1/migration/tasks/{task.id}")
