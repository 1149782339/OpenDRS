import { emptyConnection, type ConnectionInfo, type SchemaObject } from '@/types/api'
import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'

export interface SchemaRow {
  schema: string
  allTables: boolean
  tables: string[]
  excludeTables: string[]
}

function newSchemaRow(): SchemaRow {
  return { schema: '', allTables: false, tables: [], excludeTables: [] }
}

export const useTaskCreateStore = defineStore('taskCreate', () => {
  const step = ref(0)
  const taskId = ref<number | null>(null)
  const sourceTestedKey = ref<string | null>(null)
  const targetTestedKey = ref<string | null>(null)

  const form = reactive({
    name: '',
    sourceSavedId: null as number | null,
    targetSavedId: null as number | null,
    source: emptyConnection('MYSQL'),
    target: emptyConnection('POSTGRESQL'),
    objects: [newSchemaRow()] as SchemaRow[],
    batchSize: 1000,
    fullDumpParallelism: 8,
    databaseServerId: undefined as number | undefined,
  })

  function connectionKey(info: ConnectionInfo): string {
    return [info.type, info.host, info.port, info.database, info.username, info.password].join('\0')
  }

  function markSourceTested() {
    sourceTestedKey.value = connectionKey(form.source)
  }

  function markTargetTested() {
    targetTestedKey.value = connectionKey(form.target)
  }

  function sourceTested(): boolean {
    return sourceTestedKey.value != null && sourceTestedKey.value === connectionKey(form.source)
  }

  function targetTested(): boolean {
    return targetTestedKey.value != null && targetTestedKey.value === connectionKey(form.target)
  }

  function toObjects(): SchemaObject[] {
    return form.objects.map((row) => {
      const object: SchemaObject = { schema: row.schema.trim() }
      if (row.allTables) {
        object.allTables = true
      } else {
        object.tables = row.tables
      }
      if (row.excludeTables.length > 0) {
        object.excludeTables = row.excludeTables
      }
      return object
    })
  }

  function addObject() {
    form.objects.push(newSchemaRow())
  }

  function removeObject(index: number) {
    form.objects.splice(index, 1)
  }

  function reset() {
    step.value = 0
    taskId.value = null
    sourceTestedKey.value = null
    targetTestedKey.value = null
    form.name = ''
    form.sourceSavedId = null
    form.targetSavedId = null
    form.source = emptyConnection('MYSQL')
    form.target = emptyConnection('POSTGRESQL')
    form.objects = [newSchemaRow()]
    form.batchSize = 1000
    form.fullDumpParallelism = 8
    form.databaseServerId = undefined
  }

  return {
    step,
    taskId,
    form,
    markSourceTested,
    markTargetTested,
    sourceTested,
    targetTested,
    toObjects,
    addObject,
    removeObject,
    reset,
  }
})
