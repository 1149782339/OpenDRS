<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { listSchemas, listTables } from '@/api/objectCatalog'
import type { SelectionEntry } from '@/types/selection'
import {
  ArrowLeft,
  ArrowRight,
  Loading,
  Minus,
  Plus,
  QuestionFilled,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * Schema/table transfer shared by the migration wizard and the data-check creation page.
 *
 * <p>TABLE mode materializes a selected schema into its explicit table list, so the consumer only
 * ever sees concrete {@code schema.table} pairs; SCHEMA mode keeps the whole-schema entry.
 */
const props = defineProps<{
  connectionId: number | null | undefined
  mode: 'TABLE' | 'SCHEMA'
  modelValue: SelectionEntry[]
  /**
   * Optional replacement for the schema/table listing. Used by the data-check creation page,
   * where the candidate objects are the migration task's expanded scope, not the whole source.
   */
  loader?: (() => Promise<{ schema: string; tables: string[] }[]>) | null
}>()

const emit = defineEmits<{ 'update:modelValue': [SelectionEntry[]] }>()

const { t } = useI18n()

interface CatalogSchema {
  name: string
  tables: string[] | null
  loading: boolean
  error: string
}

interface TreeRow {
  key: string
  kind: 'schema' | 'table' | 'loading'
  schema: string
  label: string
  badge: '' | 'database' | 'table'
  expandable: boolean
  expanded: boolean
  checked: boolean
  indeterminate: boolean
}

const catalog = ref<CatalogSchema[]>([])
const catalogLoading = ref(false)
const catalogError = ref('')
const entries = ref<SelectionEntry[]>([])
const leftChecked = ref<string[]>([])
const rightChecked = ref<string[]>([])
const leftExpanded = ref<string[]>([])
const rightExpanded = ref<string[]>([])
const leftKeyword = ref('')
const rightKeyword = ref('')

const schemaKey = (schema: string) => `s:${schema}`
const tableKey = (schema: string, table: string) => `t:${schema}/${table}`

function cloneEntries(list: SelectionEntry[]): SelectionEntry[] {
  return list.map((entry) => ({
    schema: entry.schema,
    all: entry.all,
    tables: [...entry.tables],
  }))
}

function commit() {
  emit('update:modelValue', cloneEntries(entries.value))
}

watch(
  () => props.modelValue,
  (value) => {
    if (JSON.stringify(value) === JSON.stringify(entries.value)) {
      return
    }
    entries.value = cloneEntries(value)
  },
  { immediate: true, deep: true },
)

watch(
  () => props.connectionId,
  () => {
    catalog.value = []
    entries.value = []
    resetChecks()
    commit()
    if (props.connectionId != null) {
      void loadCatalog(true)
    }
  },
)

watch(
  () => props.mode,
  (mode) => {
    resetChecks()
    if (mode === 'TABLE') {
      for (const entry of entries.value) {
        if (entry.all) {
          if (!rightExpanded.value.includes(entry.schema)) {
            rightExpanded.value.push(entry.schema)
          }
          void materialize(entry.schema)
        }
      }
    }
    commit()
  },
)

onMounted(() => {
  if (props.loader || props.connectionId != null) {
    void loadCatalog(true)
  }
})

function resetChecks() {
  leftChecked.value = []
  rightChecked.value = []
  leftExpanded.value = []
  rightExpanded.value = []
  leftKeyword.value = ''
  rightKeyword.value = ''
}

function parseTableKey(key: string): { schema: string; table: string } {
  const rest = key.slice(2)
  const index = rest.indexOf('/')
  return { schema: rest.slice(0, index), table: rest.slice(index + 1) }
}

function entryOf(schema: string): SelectionEntry | undefined {
  return entries.value.find((entry) => entry.schema === schema)
}

function catalogOf(schema: string): CatalogSchema | undefined {
  return catalog.value.find((item) => item.name === schema)
}

/** Loads the schema list; refresh=true drops the cached table lists. */
async function loadCatalog(refresh = false) {
  if (props.loader) {
    catalogLoading.value = true
    catalogError.value = ''
    try {
      const groups = await props.loader()
      catalog.value = groups.map((group) => ({
        name: group.schema,
        tables: [...group.tables],
        loading: false,
        error: '',
      }))
      leftExpanded.value = []
      rightExpanded.value = []
    } catch (error) {
      catalogError.value = apiErrorMessage(error)
    } finally {
      catalogLoading.value = false
    }
    return
  }

  const connectionId = props.connectionId
  if (connectionId == null) {
    return
  }
  if (!refresh && catalog.value.length > 0) {
    return
  }
  catalogLoading.value = true
  catalogError.value = ''
  try {
    const names = await listSchemas(connectionId)
    catalog.value = names.map((name) => ({ name, tables: null, loading: false, error: '' }))
    leftExpanded.value = []
    rightExpanded.value = []
  } catch (error) {
    catalogError.value = apiErrorMessage(error)
  } finally {
    catalogLoading.value = false
  }
}

async function loadTables(schema: string) {
  const connectionId = props.connectionId
  if (connectionId == null) {
    return
  }
  const node = catalogOf(schema)
  if (!node || node.loading || node.tables != null) {
    return
  }
  node.loading = true
  node.error = ''
  try {
    node.tables = await listTables(connectionId, schema)
  } catch (error) {
    node.error = apiErrorMessage(error)
  } finally {
    node.loading = false
  }
}

function remainingTables(schema: string): string[] {
  const node = catalogOf(schema)
  if (!node || node.tables == null) {
    return []
  }
  const entry = entryOf(schema)
  if (!entry) {
    return node.tables
  }
  if (entry.all) {
    return []
  }
  return node.tables.filter((table) => !entry.tables.includes(table))
}

const leftRows = computed<TreeRow[]>(() => {
  const keyword = leftKeyword.value.trim().toLowerCase()
  const rows: TreeRow[] = []
  for (const node of catalog.value) {
    const remaining = remainingTables(node.name)
    const entry = entryOf(node.name)
    if (entry?.all || (entry != null && remaining.length === 0)) {
      continue
    }
    const schemaHit = keyword.length === 0 || node.name.toLowerCase().includes(keyword)
    const tableHits = remaining.filter((table) => table.toLowerCase().includes(keyword))
    if (!schemaHit && tableHits.length === 0) {
      continue
    }
    const expanded = props.mode === 'TABLE' && leftExpanded.value.includes(node.name)
    const parentKey = schemaKey(node.name)
    const childKeys = remaining.map((table) => tableKey(node.name, table))
    const parentOn = leftChecked.value.includes(parentKey)
    const checkedChildren = childKeys.filter((key) => leftChecked.value.includes(key))
    const checked = parentOn || (childKeys.length > 0 && checkedChildren.length === childKeys.length)
    rows.push({
      key: parentKey,
      kind: 'schema',
      schema: node.name,
      label: node.name,
      badge: 'database',
      expandable: props.mode === 'TABLE',
      expanded,
      checked,
      indeterminate: !checked && checkedChildren.length > 0,
    })
    if (!expanded) {
      continue
    }
    if (node.loading) {
      rows.push({
        key: `loading:${node.name}`,
        kind: 'loading',
        schema: node.name,
        label: t('wizard.sync.loadingTables'),
        badge: '',
        expandable: false,
        expanded: false,
        checked: false,
        indeterminate: false,
      })
      continue
    }
    for (const table of schemaHit ? remaining : tableHits) {
      rows.push({
        key: tableKey(node.name, table),
        kind: 'table',
        schema: node.name,
        label: table,
        badge: 'table',
        expandable: false,
        expanded: false,
        checked: parentOn || leftChecked.value.includes(tableKey(node.name, table)),
        indeterminate: false,
      })
    }
  }
  return rows
})

const rightRows = computed<TreeRow[]>(() => {
  const keyword = rightKeyword.value.trim().toLowerCase()
  const rows: TreeRow[] = []
  for (const entry of entries.value) {
    const node = catalogOf(entry.schema)
    const tables = entry.all ? (node?.tables ?? []) : entry.tables
    const schemaHit = keyword.length === 0 || entry.schema.toLowerCase().includes(keyword)
    const tableHits = tables.filter((table) => table.toLowerCase().includes(keyword))
    if (!schemaHit && tableHits.length === 0) {
      continue
    }
    const expanded = props.mode === 'TABLE' && rightExpanded.value.includes(entry.schema)
    const parentKey = schemaKey(entry.schema)
    const childKeys = tables.map((table) => tableKey(entry.schema, table))
    const parentOn = rightChecked.value.includes(parentKey)
    const checkedChildren = childKeys.filter((key) => rightChecked.value.includes(key))
    const checked = parentOn || (childKeys.length > 0 && checkedChildren.length === childKeys.length)
    rows.push({
      key: parentKey,
      kind: 'schema',
      schema: entry.schema,
      label: entry.schema,
      badge: 'database',
      expandable: props.mode === 'TABLE',
      expanded,
      checked,
      indeterminate: !checked && checkedChildren.length > 0,
    })
    if (!expanded) {
      continue
    }
    if (node?.loading) {
      rows.push({
        key: `loading:${entry.schema}`,
        kind: 'loading',
        schema: entry.schema,
        label: t('wizard.sync.loadingTables'),
        badge: '',
        expandable: false,
        expanded: false,
        checked: false,
        indeterminate: false,
      })
      continue
    }
    for (const table of schemaHit ? tables : tableHits) {
      rows.push({
        key: tableKey(entry.schema, table),
        kind: 'table',
        schema: entry.schema,
        label: table,
        badge: 'table',
        expandable: false,
        expanded: false,
        checked: checkedChildren.length > 0 && rightChecked.value.includes(tableKey(entry.schema, table)),
        indeterminate: false,
      })
    }
  }
  return rows
})

/**
 * Tree checkbox semantics: checking a schema checks all its loaded tables; checking every table
 * turns the schema into the selected state; a partial selection is indeterminate.
 */
function toggleKey(list: string[], key: string, rows: TreeRow[]): string[] {
  const childrenOf = (schema: string) =>
    rows.filter((row) => row.kind === 'table' && row.schema === schema).map((row) => row.key)

  if (key.startsWith('s:')) {
    const schema = key.slice(2)
    const children = childrenOf(schema)
    const on = list.includes(key) || (children.length > 0 && children.every((child) => list.includes(child)))
    const rest = list.filter((item) => item !== key && !children.includes(item))
    return on ? rest : [...rest, key]
  }

  const { schema } = parseTableKey(key)
  const parent = schemaKey(schema)
  const children = childrenOf(schema)
  const inherited = list.includes(parent)
  const on = inherited || list.includes(key)
  const rest = list.filter((item) => item !== parent && !children.includes(item))
  const next = new Set(inherited ? children : children.filter((child) => list.includes(child)))
  if (on) {
    next.delete(key)
  } else {
    next.add(key)
  }
  if (children.length > 0 && children.every((child) => next.has(child))) {
    return [...rest, parent]
  }
  return [...rest, ...next]
}

function toggleLeftExpand(schema: string) {
  if (props.mode !== 'TABLE') {
    return
  }
  const expanded = leftExpanded.value.includes(schema)
  leftExpanded.value = expanded
    ? leftExpanded.value.filter((item) => item !== schema)
    : [...leftExpanded.value, schema]
  if (!expanded) {
    void loadTables(schema)
  }
}

function toggleRightExpand(schema: string) {
  if (props.mode !== 'TABLE') {
    return
  }
  const expanded = rightExpanded.value.includes(schema)
  rightExpanded.value = expanded
    ? rightExpanded.value.filter((item) => item !== schema)
    : [...rightExpanded.value, schema]
  if (!expanded && entryOf(schema)?.all) {
    void materialize(schema)
  }
}

function checkAll(rows: TreeRow[], list: string[]): string[] {
  const keys = [...new Set(rows.filter((row) => row.kind === 'schema').map((row) => row.key))]
  const allOn =
    keys.length > 0 &&
    keys.every((key) => {
      const schema = key.slice(2)
      const children = rows
        .filter((row) => row.kind === 'table' && row.schema === schema)
        .map((row) => row.key)
      return list.includes(key) || (children.length > 0 && children.every((child) => list.includes(child)))
    })
  return allOn ? [] : keys
}

/** TABLE mode: replace a whole-schema entry with its explicit table list. */
async function materialize(schema: string) {
  const entry = entryOf(schema)
  if (!entry?.all) {
    return
  }
  await loadTables(schema)
  const tables = catalogOf(schema)?.tables
  if (entry.all && tables) {
    entry.all = false
    entry.tables = [...tables]
    commit()
  }
}

function moveRight() {
  for (const key of leftChecked.value) {
    if (key.startsWith('s:')) {
      const schema = key.slice(2)
      const entry = entryOf(schema)
      if (entry) {
        entry.all = true
        entry.tables = []
      } else {
        entries.value.push({ schema, all: true, tables: [] })
      }
      if (props.mode === 'TABLE') {
        if (!rightExpanded.value.includes(schema)) {
          rightExpanded.value = [...rightExpanded.value, schema]
        }
        void materialize(schema)
      }
    } else {
      const { schema, table } = parseTableKey(key)
      const entry = entryOf(schema)
      if (entry) {
        if (!entry.all && !entry.tables.includes(table)) {
          entry.tables.push(table)
        }
      } else {
        entries.value.push({ schema, all: false, tables: [table] })
      }
      if (!rightExpanded.value.includes(schema)) {
        rightExpanded.value = [...rightExpanded.value, schema]
      }
    }
  }
  leftChecked.value = []
  commit()
}

function moveLeft() {
  for (const key of rightChecked.value) {
    if (key.startsWith('s:')) {
      const schema = key.slice(2)
      entries.value = entries.value.filter((entry) => entry.schema !== schema)
    } else {
      const { schema, table } = parseTableKey(key)
      const entry = entryOf(schema)
      if (!entry) {
        continue
      }
      if (entry.all) {
        entry.all = false
        entry.tables = (catalogOf(schema)?.tables ?? []).filter((item) => item !== table)
      } else {
        entry.tables = entry.tables.filter((item) => item !== table)
      }
      if (!entry.all && entry.tables.length === 0) {
        entries.value = entries.value.filter((item) => item.schema !== schema)
      }
    }
  }
  rightChecked.value = []
  commit()
}
</script>

<template>
  <div class="transfer">
    <p class="transfer-tip">
      {{ t('wizard.sync.transferTip') }}<span class="danger">{{ t('wizard.sync.transferTipDanger') }}</span>
    </p>

    <div class="transfer-body">
      <section class="transfer-panel">
        <header class="transfer-head">
          <el-tooltip
            :content="mode === 'TABLE' ? t('wizard.sync.tableModeHelp') : t('wizard.sync.schemaModeHelp')"
            placement="top"
          >
            <el-icon class="head-help"><QuestionFilled /></el-icon>
          </el-tooltip>
          <el-button size="small" @click="leftChecked = checkAll(leftRows, leftChecked)">
            {{ t('common.all') }}
          </el-button>
          <el-icon
            class="head-refresh"
            :class="{ 'is-loading': catalogLoading }"
            :title="t('wizard.sync.refresh')"
            @click="loadCatalog(true)"
          >
            <Refresh />
          </el-icon>
        </header>
        <el-input
          v-model="leftKeyword"
          size="small"
          :placeholder="t('wizard.sync.searchExpanded')"
          :prefix-icon="Search"
          clearable
        />
        <div class="tree">
          <div v-if="catalogLoading" class="tree-state">
            <el-icon class="is-loading tree-spinner"><Loading /></el-icon>
            <span>{{ t('wizard.sync.loadingCatalog') }}</span>
          </div>
          <div v-else-if="catalogError" class="tree-state error">{{ catalogError }}</div>
          <div v-else-if="leftRows.length === 0" class="tree-state">{{ t('wizard.sync.noObjects') }}</div>
          <template v-else>
            <div
              v-for="row in leftRows"
              :key="row.key"
              class="tree-row"
              :class="{ child: row.kind === 'table', loading: row.kind === 'loading' }"
            >
              <el-icon v-if="row.expandable" class="expander" @click="toggleLeftExpand(row.schema)">
                <Minus v-if="row.expanded" /><Plus v-else />
              </el-icon>
              <span v-else class="expander" />
              <template v-if="row.kind === 'loading'">
                <el-icon class="is-loading node-spinner"><Loading /></el-icon>
                <span class="tree-label muted">{{ row.label }}</span>
              </template>
              <template v-else>
                <el-checkbox
                  :model-value="row.checked"
                  :indeterminate="row.indeterminate"
                  @change="leftChecked = toggleKey(leftChecked, row.key, leftRows)"
                />
                <span class="tree-label">{{ row.label }}</span>
                <span class="tree-badge">{{ row.badge }}</span>
              </template>
            </div>
          </template>
        </div>
      </section>

      <div class="transfer-actions">
        <el-button :disabled="leftChecked.length === 0" @click="moveRight">
          <el-icon><ArrowRight /></el-icon>
        </el-button>
        <el-button :disabled="rightChecked.length === 0" @click="moveLeft">
          <el-icon><ArrowLeft /></el-icon>
        </el-button>
      </div>

      <section class="transfer-panel">
        <header class="transfer-head">
          <el-button size="small" @click="rightChecked = checkAll(rightRows, rightChecked)">
            {{ t('common.all') }}
          </el-button>
        </header>
        <el-input
          v-model="rightKeyword"
          size="small"
          :placeholder="t('wizard.sync.searchExpanded')"
          :prefix-icon="Search"
          clearable
        />
        <div class="tree">
          <div v-if="rightRows.length === 0" class="tree-state">{{ t('wizard.sync.noSelection') }}</div>
          <template v-else>
            <div
              v-for="row in rightRows"
              :key="row.key"
              class="tree-row"
              :class="{ child: row.kind === 'table', loading: row.kind === 'loading' }"
            >
              <el-icon v-if="row.expandable" class="expander" @click="toggleRightExpand(row.schema)">
                <Minus v-if="row.expanded" /><Plus v-else />
              </el-icon>
              <span v-else class="expander" />
              <template v-if="row.kind === 'loading'">
                <el-icon class="is-loading node-spinner"><Loading /></el-icon>
                <span class="tree-label muted">{{ row.label }}</span>
              </template>
              <template v-else>
                <el-checkbox
                  :model-value="row.checked"
                  :indeterminate="row.indeterminate"
                  @change="rightChecked = toggleKey(rightChecked, row.key, rightRows)"
                />
                <span class="tree-label">{{ row.label }}</span>
                <span class="tree-badge">{{ row.badge }}</span>
              </template>
            </div>
          </template>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.transfer-tip {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}

.transfer-tip .danger {
  color: #f56c6c;
}

.transfer-body {
  display: flex;
  align-items: stretch;
  gap: 12px;
}

.transfer-panel {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #c8c9cc;
  border-radius: 4px;
  background: #fff;
}

.transfer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 8px;
  background: #f2f6fc;
  border-bottom: 1px solid #e4e7ed;
}

.head-help {
  color: #909399;
  cursor: help;
}

.transfer-head .el-button {
  margin: 0 auto;
}

.head-refresh {
  margin-left: 0;
  color: #606266;
  cursor: pointer;
  font-size: 15px;
}

.head-refresh:hover {
  color: #409eff;
}

.transfer-panel .el-input {
  padding: 6px 8px;
}

.tree {
  flex: 1 1 auto;
  height: 240px;
  min-height: 240px;
  overflow-y: auto;
  padding: 2px 4px 8px;
}

.tree-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100%;
  font-size: 12px;
  color: #a8abb2;
}

.tree-state.error {
  color: #f56c6c;
}

.tree-spinner {
  font-size: 22px;
  color: #409eff;
}

.tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 13px;
}

.tree-row:hover {
  background: #f5f7fa;
}

.tree-row.child {
  padding-left: 26px;
}

.tree-row.loading {
  color: #a8abb2;
}

.tree-row .expander {
  width: 14px;
  font-size: 12px;
  color: #909399;
  cursor: pointer;
  flex: 0 0 14px;
}

.tree-row .expander:empty {
  cursor: default;
}

.node-spinner {
  font-size: 13px;
  color: #409eff;
}

.tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-label.muted {
  color: #a8abb2;
}

.tree-badge {
  font-size: 12px;
  color: #a8abb2;
}

.transfer-actions {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 10px;
}

.transfer-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
