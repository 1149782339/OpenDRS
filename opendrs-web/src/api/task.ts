import { http } from '@/api/http'
import {
  isPrecheckFinished,
  type CreateMigrationTaskRequest,
  type MigrationLogLevel,
  type MigrationLogPage,
  type MigrationPrecheckResponse,
  type MigrationStatusResponse,
  type MigrationTaskResponse,
  type MigrationTaskSummary,
  type TaskReplayMetrics,
} from '@/types/api'

const BASE = '/api/v1/migration/tasks'

export function listTasks(): Promise<MigrationTaskSummary[]> {
  return http.get(BASE)
}

/** 新建向导的默认任务名（DRS-xxxx，xxxx = 下一个自增值）；信息性，提交时可能已被占用。 */
export function getNextTaskName(): Promise<string> {
  return http.get(`${BASE}/next-name`)
}

/**
 * 一页任务日志。level 为空表示全部级别；筛选和分页都在服务端，
 * 前端只负责把页码递过去 —— 客户端筛会把「当前页没有 Error」显示成空页。
 */
export function listTaskLogs(
  id: number,
  params: { level?: MigrationLogLevel | null; page?: number; size?: number } = {},
): Promise<MigrationLogPage> {
  return http.get(`${BASE}/${id}/logs`, {
    params: {
      level: params.level ?? undefined,
      page: params.page ?? 1,
      size: params.size ?? 20,
    },
  })
}

export function createTask(body: CreateMigrationTaskRequest): Promise<MigrationTaskResponse> {
  return http.post(BASE, body)
}

export function getTask(id: number): Promise<MigrationTaskResponse> {
  return http.get(`${BASE}/${id}`)
}

export function getTaskStatus(id: number): Promise<MigrationStatusResponse> {
  return http.get(`${BASE}/${id}/status`)
}

/** 任务监控页的源端捕获 / 目标回放 TPS 曲线（后端代理 Prometheus query_range）。 */
export function getReplayMetrics(
  id: number,
  params: { minutes?: number; step?: number } = {},
): Promise<TaskReplayMetrics> {
  return http.get(`${BASE}/${id}/metrics/replay`, {
    params: {
      minutes: params.minutes ?? 15,
      step: params.step ?? 5,
    },
  })
}

export function precheckTask(id: number): Promise<MigrationPrecheckResponse> {
  return http.post(`${BASE}/${id}/precheck`)
}

export function getPrecheck(id: number): Promise<MigrationPrecheckResponse> {
  return http.get(`${BASE}/${id}/precheck`)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

export async function waitForPrecheck(
  id: number,
  intervalMs = 1000,
  timeoutMs = 120_000,
): Promise<MigrationPrecheckResponse> {
  const deadline = Date.now() + timeoutMs
  let last = await getPrecheck(id)
  while (!isPrecheckFinished(last)) {
    if (Date.now() > deadline) {
      throw new Error('预检查超时')
    }
    await sleep(intervalMs)
    last = await getPrecheck(id)
  }
  return last
}

export async function startAndWaitPrecheck(id: number): Promise<MigrationPrecheckResponse> {
  await precheckTask(id)
  return waitForPrecheck(id)
}

export function startTask(id: number): Promise<MigrationStatusResponse> {
  return http.post(`${BASE}/${id}/start`)
}

export function stopTask(id: number): Promise<MigrationStatusResponse> {
  return http.post(`${BASE}/${id}/stop`)
}

/** 结束：终态停机，落地 TERMINATED 后才可删除。 */
export function terminateTask(id: number): Promise<MigrationStatusResponse> {
  return http.post(`${BASE}/${id}/terminate`)
}

export function deleteTask(id: number): Promise<void> {
  return http.delete(`${BASE}/${id}`)
}
