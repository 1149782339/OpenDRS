import { http } from '@/api/http'
import type {
  CreateMigrationTaskRequest,
  MigrationPrecheckResponse,
  MigrationStatusResponse,
  MigrationTaskResponse,
  MigrationTaskSummary,
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

export function startTask(id: number): Promise<MigrationStatusResponse> {
  return http.post(`${BASE}/${id}/start`)
}

export function stopTask(id: number): Promise<MigrationStatusResponse> {
  return http.post(`${BASE}/${id}/stop`)
}

export function deleteTask(id: number): Promise<void> {
  return http.delete(`${BASE}/${id}`)
}
