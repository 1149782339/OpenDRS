import { http } from '@/api/http'
import {
  isPrecheckFinished,
  type CreateMigrationTaskRequest,
  type MigrationPrecheckResponse,
  type MigrationStatusResponse,
  type MigrationTaskResponse,
  type MigrationTaskSummary,
} from '@/types/api'

const BASE = '/api/v1/migration/tasks'

export function listTasks(): Promise<MigrationTaskSummary[]> {
  return http.get(BASE)
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

export function deleteTask(id: number): Promise<void> {
  return http.delete(`${BASE}/${id}`)
}
