import type { SelectedTable, TaskState } from '@/types/api'

export type DataCheckMode = 'FULL_ONLY' | 'INCREMENTAL_ONLY' | 'FULL_AND_INCREMENTAL'

export type DataCheckPhase = 'PENDING' | 'FULL' | 'INCREMENTAL'

export type DataCheckTableStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'STOPPED'

/** One checked table pair; the target is resolved by the server at creation time. */
export interface CheckedTable {
  source: SelectedTable
  target: SelectedTable
}

/** Schema group of the migration task's expanded source scope. */
export interface CandidateSchema {
  schema: string
  tables: string[]
}

export interface DataCheckOptions {
  includeFloat: boolean
}

export interface DataCheckTaskResponse {
  id: number
  publicId: string
  migrationTaskId: number
  pipelineId: number | null
  name: string
  mode: DataCheckMode
  state: TaskState | null
  tables: CheckedTable[]
  options: DataCheckOptions | null
  lagMs: number | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  /** False while any table is in the full-check phase (stop is rejected). */
  stoppable?: boolean
}

export interface DataCheckTableResult {
  sourceTableId: string
  targetTableId: string
  phase: DataCheckPhase
  status: DataCheckTableStatus
  lagMs: number
  errorMessage: string | null
  differentCount: number
  sourceOnlyCount: number
  targetOnlyCount: number
  totalCount: number
  rowsChecked?: number | null
  lastCheckAt?: string | null
  waitingForReplay?: boolean
}

export interface DataCheckResultItem {
  tableId: string
  pk: string
  /** 0 = both sides differ, 1 = source only, 2 = target only. */
  flag: number
  createdAt: string
  updatedAt: string
}

export interface DataCheckResultPage {
  items: DataCheckResultItem[]
  total: number
  page: number
  size: number
}

export interface CreateDataCheckTaskRequest {
  migrationTaskId: number
  pipelineId?: number | null
  name: string
  mode: DataCheckMode
  tables: SelectedTable[]
  options?: DataCheckOptions | null
}
