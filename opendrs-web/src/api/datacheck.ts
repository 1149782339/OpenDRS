import { http } from '@/api/http'
import type {
  CandidateSchema,
  CreateDataCheckTaskRequest,
  DataCheckResultPage,
  DataCheckTableResult,
  DataCheckTaskResponse,
} from '@/types/datacheck'

const BASE = '/api/v1/datacheck/tasks'

/**
 * Source tables a check may cover: the migration task's selection expanded by the engine
 * (ALL / SCHEMA / TABLE plus excludes), not the whole source instance.
 */
export function listDataCheckCandidates(migrationTaskId: number): Promise<CandidateSchema[]> {
  return http.get(`${BASE}/candidates`, { params: { migrationTaskId } })
}

export function listDataCheckTasks(migrationTaskId: number): Promise<DataCheckTaskResponse[]> {
  return http.get(BASE, { params: { migrationTaskId } })
}

export function createDataCheckTask(
  body: CreateDataCheckTaskRequest,
): Promise<DataCheckTaskResponse> {
  return http.post(BASE, body)
}

export function getDataCheckTask(id: number): Promise<DataCheckTaskResponse> {
  return http.get(`${BASE}/${id}`)
}

export function deleteDataCheckTask(id: number): Promise<void> {
  return http.delete(`${BASE}/${id}`)
}

export function startDataCheckTask(id: number): Promise<void> {
  return http.post(`${BASE}/${id}/start`)
}

export function stopDataCheckTask(id: number): Promise<void> {
  return http.post(`${BASE}/${id}/stop`)
}

export function terminateDataCheckTask(id: number): Promise<void> {
  return http.post(`${BASE}/${id}/terminate`)
}

export function listDataCheckTables(id: number): Promise<DataCheckTableResult[]> {
  return http.get(`${BASE}/${id}/tables`)
}

export function listDataCheckResults(
  id: number,
  params: { tableId?: string | null; flag?: number | null; page?: number; size?: number } = {},
): Promise<DataCheckResultPage> {
  return http.get(`${BASE}/${id}/results`, {
    params: {
      tableId: params.tableId ?? undefined,
      flag: params.flag ?? undefined,
      page: params.page ?? 1,
      size: params.size ?? 20,
    },
  })
}
