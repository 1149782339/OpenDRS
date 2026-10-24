import type { MigrationOptions, SelectedTable, TableSelection } from '@/types/api'
import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'

/** 设置同步页的三选一：全部迁移 / 表级迁移 / 库级迁移。 */
export type SelectionMode = 'ALL' | 'SCHEMA' | 'TABLE'

export const useTaskCreateStore = defineStore('taskCreate', () => {
  const step = ref(0)
  const taskId = ref<number | null>(null)

  const form = reactive({
    name: '',
    sourceConnectionId: undefined as number | undefined,
    targetConnectionId: undefined as number | undefined,
    /**
     * 迁移对象按模式分开存，避免「整库 + 几张表」这种后端表达不了的组合：
     * ALL 两个列表都空，SCHEMA 只填 schemas，TABLE 只填 tables。
     */
    selectionMode: 'ALL' as SelectionMode,
    schemas: [] as string[],
    tables: [] as SelectedTable[],
    /** 同步拓扑：默认单向；双向同步仅支持 MySQL ↔ MySQL（源/目标类型不满足时 UI 置灰）。 */
    syncTopology: 'ONE_WAY' as 'ONE_WAY' | 'BIDIRECTIONAL',
    /* ---- 高级设置 ---- */
    /** 全量同步对象类型：同步表结构（含主键和唯一键）/ 同步数据 / 同步普通索引。 */
    fullSyncSchema: true,
    fullSyncData: true,
    fullSyncIndex: true,
    exportConcurrency: 8,
    importConcurrency: 8,
    importMode: 'COPY' as 'COPY' | 'INSERT',
    chunkSize: 520000,
    /** 结构迁移字符集适配：自动适配字符集长度 / 保持原库字符长度。 */
    charsetAdapt: 'AUTO' as 'AUTO' | 'KEEP',
    replayConcurrency: 64,
    conflictPolicy: 'OVERWRITE' as 'OVERWRITE' | 'ERROR' | 'IGNORE',
  })

  /** 迁移对象选择：四列表全空即「全部迁移」，与后端 options.selection 同形。 */
  function toSelection(): TableSelection & { mode: SelectionMode } {
    const base = { excludeSchemas: [], excludeTables: [] }
    if (form.selectionMode === 'SCHEMA') {
      return { mode: 'SCHEMA', schemas: [...form.schemas], tables: [], ...base }
    }
    if (form.selectionMode === 'TABLE') {
      return {
        mode: 'TABLE',
        schemas: [],
        tables: form.tables.map((item) => ({ schema: item.schema, table: item.table })),
        ...base,
      }
    }
    return { mode: 'ALL', schemas: [], tables: [], ...base }
  }

  /** 提交给后端的统一任务配置：topology + selection + 全量设置 + 增量回放设置。 */
  function toOptions(): MigrationOptions {
    const objectTypes = [
      form.fullSyncSchema ? ('SCHEMA' as const) : null,
      form.fullSyncData ? ('DATA' as const) : null,
      form.fullSyncIndex ? ('INDEX' as const) : null,
    ].filter((item) => item != null)
    return {
      topology: form.syncTopology,
      selection: toSelection(),
      full: {
        objectTypes,
        exportConcurrency: form.exportConcurrency,
        importConcurrency: form.importConcurrency,
        importMode: form.importMode,
        chunkSize: form.chunkSize,
        charsetAdapt: form.charsetAdapt,
      },
      incremental: {
        replayConcurrency: form.replayConcurrency,
        conflictPolicy: form.conflictPolicy,
      },
    }
  }

  function reset() {
    step.value = 0
    taskId.value = null
    form.name = ''
    form.sourceConnectionId = undefined
    form.targetConnectionId = undefined
    form.selectionMode = 'ALL'
    form.schemas = []
    form.tables = []
    form.syncTopology = 'ONE_WAY'
    form.fullSyncSchema = true
    form.fullSyncData = true
    form.fullSyncIndex = true
    form.exportConcurrency = 8
    form.importConcurrency = 8
    form.importMode = 'COPY'
    form.chunkSize = 520000
    form.charsetAdapt = 'AUTO'
    form.replayConcurrency = 64
    form.conflictPolicy = 'OVERWRITE'
  }

  return {
    step,
    taskId,
    form,
    toOptions,
    reset,
  }
})
