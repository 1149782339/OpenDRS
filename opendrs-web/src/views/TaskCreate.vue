<script setup lang="ts">
import { listConnections } from '@/api/connection'
import { apiErrorCode, apiErrorMessage } from '@/api/http'
import { listSchemas, listTables } from '@/api/objectCatalog'
import {
  createTask,
  getNextTaskName,
  getPrecheck,
  getTask,
  precheckTask,
  startTask,
} from '@/api/task'
import SavedDataSourceSelect from '@/components/SavedDataSourceSelect.vue'
import { useTaskCreateStore } from '@/stores/taskCreate'
import { formatDateTime } from '@/utils/datetime'
import {
  isPrecheckFinished,
  isPrecheckRunning,
  type CheckGroup,
  type ConnectionResponse,
  type CreateMigrationTaskRequest,
  type MigrationPrecheckResponse,
  type MigrationTaskResponse,
  type PrecheckItem,
} from '@/types/api'
import {
  ArrowLeft,
  ArrowRight,
  CircleCheck,
  CircleClose,
  Loading,
  Minus,
  Plus,
  QuestionFilled,
  Refresh,
  Remove,
  Search,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const store = useTaskCreateStore()
const connections = ref<ConnectionResponse[]>([])
const loadingConnections = ref(false)
const connectionsError = ref('')
let disposed = false
const creating = ref(false)
const startingPrecheck = ref(false)
const starting = ref(false)
const createdTask = ref<MigrationTaskResponse | null>(null)
const precheck = ref<MigrationPrecheckResponse | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null
/** 从任务列表点进来的续跑模式：任务已经创建，只是回到预检查 / 任务确认这一步。 */
const resumeMode = ref(false)
const resuming = ref(false)
const pageTitle = computed(() => (resumeMode.value ? t('wizard.resumeTitle') : t('wizard.newTitle')))

const steps = computed(() => [
  { title: t('wizard.steps.basic') },
  { title: t('wizard.steps.sync') },
  { title: t('wizard.steps.advanced') },
  { title: t('wizard.steps.precheck') },
  { title: t('wizard.steps.confirm') },
])

/** 预检查分组名与后端 CheckGroup 枚举同名，key 对应语言包 wizard.precheck.group.*。 */
function groupLabel(code: CheckGroup): string {
  return t(`wizard.precheck.group.${code}`)
}

const running = computed(() => isPrecheckRunning(precheck.value))

/** 后端从第一次发布起就给完整清单，这里只按 order 排一次，之后每项原地翻状态。 */
const items = computed(() => [...(precheck.value?.items ?? [])].sort((a, b) => a.order - b.order))

/** 按分组渲染：order 已按分组排好，Map 的插入顺序就是分组顺序。 */
const groups = computed(() => {
  const byGroup = new Map<CheckGroup, PrecheckItem[]>()
  for (const item of items.value) {
    const bucket = byGroup.get(item.group)
    if (bucket) bucket.push(item)
    else byGroup.set(item.group, [item])
  }
  return [...byGroup.entries()].map(([code, list]) => ({ code, label: groupLabel(code), items: list }))
})

/** 清单还没下发（任务刚提交、引擎还没开始建计划）时的占位。 */
const preparing = computed(() => running.value && items.value.length === 0)

const finished = computed(() => precheck.value != null && isPrecheckFinished(precheck.value))
const passed = computed(() => precheck.value?.ok === true)
const canRecheck = computed(() => {
  const result = precheck.value
  if (!result || startingPrecheck.value) return false
  if (result.phase === 'CREATED') return result.state == null
  if (result.phase === 'PRECHECKING') return result.state === 'FAILED'
  return result.phase === 'PRECHECKED'
    && (result.state == null || result.state === 'STOPPED' || result.state === 'FAILED')
})

/**
 * 「跳过」的检查不参与通过率，否则版本里被移除的参数会一直把通过率压低。
 * 分母用整张清单（而不是已出结果的项），否则跑到一半会显示 100% 却还有项在转圈。
 */
const passCount = computed(() => items.value.filter((item) => item.verdict === 'PASS').length)
const skippedCount = computed(() => items.value.filter((item) => item.verdict === 'NOT_APPLICABLE').length)
/** 失败但只是警告：需要用户点头，不算不通过。 */
const warningCount = computed(
  () => items.value.filter((item) => item.verdict === 'FAIL' && item.severity === 'WARNING').length,
)
const passRate = computed(() => {
  const total = items.value.length - skippedCount.value
  if (total <= 0) return finished.value && passed.value ? 100 : 0
  return Math.round((passCount.value / total) * 100)
})

const selectedSource = computed(() => connections.value.find((item) => item.id === store.form.sourceConnectionId && item.type === 'MYSQL'))
/** 目标端默认 PostgreSQL；选 MySQL 是为了配置双向同步（MySQL ↔ MySQL）。 */
const selectedTarget = computed(() =>
  connections.value.find(
    (item) => item.id === store.form.targetConnectionId && (item.type === 'POSTGRESQL' || item.type === 'MYSQL'),
  ),
)
const canNextBasic = computed(() => store.form.name.trim().length > 0 && !loadingConnections.value && !connectionsError.value && selectedSource.value != null && selectedTarget.value != null)

/** 双向同步仅支持 MySQL ↔ MySQL；其余组合该选项置灰。 */
const bidirectionalAllowed = computed(
  () => selectedSource.value?.type === 'MYSQL' && selectedTarget.value?.type === 'MYSQL',
)
watch(bidirectionalAllowed, (allowed) => {
  if (!allowed && store.form.syncTopology === 'BIDIRECTIONAL') store.form.syncTopology = 'ONE_WAY'
})
// 双向要求全量+增量：已选双向时再改同步模式，拓扑自动退回单向（与后端校验一致）。
watch(
  () => store.form.syncMode,
  (mode) => {
    if (mode !== 'FULL_AND_INCREMENTAL' && store.form.syncTopology === 'BIDIRECTIONAL') {
      store.form.syncTopology = 'ONE_WAY'
    }
  },
)

/* ---------------- 迁移对象：全部迁移 / 表级迁移 / 库级迁移 ---------------- */

type ObjectMode = 'ALL' | 'TABLE' | 'SCHEMA'

interface CatalogSchema {
  name: string
  tables: string[] | null
  loading: boolean
  error: string
}

interface SelectionEntry {
  schema: string
  /** true = 整库全选，false = 只迁 tables 里列出的表。 */
  all: boolean
  tables: string[]
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
  /** 父节点下只勾了部分子节点时显示半选。 */
  indeterminate: boolean
}

const objectModeHint = computed(() => t('wizard.sync.objectsHelp'))

const objectMode = ref<ObjectMode>('ALL')
const catalog = ref<CatalogSchema[]>([])
const catalogLoading = ref(false)
const catalogError = ref('')
const selection = ref<SelectionEntry[]>([])
const leftChecked = ref<string[]>([])
const rightChecked = ref<string[]>([])
const leftExpanded = ref<string[]>([])
const rightExpanded = ref<string[]>([])
const leftKeyword = ref('')
const rightKeyword = ref('')

const schemaKey = (schema: string) => `s:${schema}`
const tableKey = (schema: string, table: string) => `t:${schema}/${table}`

function parseTableKey(key: string): { schema: string; table: string } {
  const rest = key.slice(2)
  const index = rest.indexOf('/')
  return { schema: rest.slice(0, index), table: rest.slice(index + 1) }
}

function entryOf(schema: string): SelectionEntry | undefined {
  return selection.value.find((entry) => entry.schema === schema)
}

function catalogOf(schema: string): CatalogSchema | undefined {
  return catalog.value.find((item) => item.name === schema)
}

/** 拉库列表；refresh=true 时丢掉已缓存的表清单重新拉。 */
async function loadCatalog(refresh = false) {
  const connectionId = store.form.sourceConnectionId
  if (connectionId == null) return
  if (!refresh && catalog.value.length > 0) return
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
  const connectionId = store.form.sourceConnectionId
  if (connectionId == null) return
  const node = catalogOf(schema)
  if (!node || node.loading || node.tables != null) return
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

/** 左栏某库里还没被选走的表。 */
function remainingTables(schema: string): string[] {
  const node = catalogOf(schema)
  if (!node || node.tables == null) return []
  const entry = entryOf(schema)
  if (!entry) return node.tables
  if (entry.all) return []
  return node.tables.filter((table) => !entry.tables.includes(table))
}

const leftRows = computed<TreeRow[]>(() => {
  const keyword = leftKeyword.value.trim().toLowerCase()
  const rows: TreeRow[] = []
  for (const node of catalog.value) {
    const remaining = remainingTables(node.name)
    const entry = entryOf(node.name)
    if (entry?.all || (entry != null && remaining.length === 0)) continue
    const schemaHit = keyword.length === 0 || node.name.toLowerCase().includes(keyword)
    const tableHits = remaining.filter((table) => table.toLowerCase().includes(keyword))
    if (!schemaHit && tableHits.length === 0) continue
    const expanded = objectMode.value === 'TABLE' && leftExpanded.value.includes(node.name)
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
      expandable: objectMode.value === 'TABLE',
      expanded,
      checked,
      indeterminate: !checked && checkedChildren.length > 0,
    })
    if (!expanded) continue
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
  for (const entry of selection.value) {
    const node = catalogOf(entry.schema)
    const tables = entry.all ? (node?.tables ?? []) : entry.tables
    const schemaHit = keyword.length === 0 || entry.schema.toLowerCase().includes(keyword)
    const tableHits = tables.filter((table) => table.toLowerCase().includes(keyword))
    if (!schemaHit && tableHits.length === 0) continue
    const expanded = objectMode.value === 'TABLE' && rightExpanded.value.includes(entry.schema)
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
      expandable: objectMode.value === 'TABLE',
      expanded,
      checked,
      indeterminate: !checked && checkedChildren.length > 0,
    })
    if (!expanded) continue
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
        checked: parentOn || rightChecked.value.includes(tableKey(entry.schema, table)),
        indeterminate: false,
      })
    }
  }
  return rows
})

const canNextSync = computed(() => objectMode.value === 'ALL' || selection.value.length > 0)

/**
 * 勾选按树走：勾父节点等于勾它下面所有子节点；子节点全勾上时父节点自动变全选，
 * 只勾了一部分则父节点显示半选（indeterminate）。取消父节点同时清掉子节点。
 */
function toggleKey(list: string[], key: string, rows: TreeRow[]): string[] {
  const childrenOf = (schema: string) => rows.filter((row) => row.kind === 'table' && row.schema === schema).map((row) => row.key)

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
  if (on) next.delete(key)
  else next.add(key)
  if (children.length > 0 && children.every((child) => next.has(child))) return [...rest, parent]
  return [...rest, ...next]
}

function toggleLeftExpand(schema: string) {
  if (objectMode.value !== 'TABLE') return
  const expanded = leftExpanded.value.includes(schema)
  leftExpanded.value = expanded ? leftExpanded.value.filter((item) => item !== schema) : [...leftExpanded.value, schema]
  if (!expanded) void loadTables(schema)
}

function toggleRightExpand(schema: string) {
  if (objectMode.value !== 'TABLE') return
  const expanded = rightExpanded.value.includes(schema)
  rightExpanded.value = expanded ? rightExpanded.value.filter((item) => item !== schema) : [...rightExpanded.value, schema]
  if (!expanded && entryOf(schema)?.all) void materialize(schema)
}

/** 全选/取消全选：按库节点算，库下面的表跟着一起变。 */
function checkAll(rows: TreeRow[], list: string[]): string[] {
  const keys = [...new Set(rows.filter((row) => row.kind === 'schema').map((row) => row.key))]
  const allOn =
    keys.length > 0 &&
    keys.every((key) => {
      const schema = key.slice(2)
      const children = rows.filter((row) => row.kind === 'table' && row.schema === schema).map((row) => row.key)
      return list.includes(key) || (children.length > 0 && children.every((child) => list.includes(child)))
    })
  return allOn ? [] : keys
}

/** 表级模式下把「整库选中」落成显式表清单，之后取消某张表才好算。 */
async function materialize(schema: string) {
  const entry = entryOf(schema)
  if (!entry?.all) return
  await loadTables(schema)
  const tables = catalogOf(schema)?.tables
  if (entry.all && tables) {
    entry.all = false
    entry.tables = [...tables]
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
        selection.value.push({ schema, all: true, tables: [] })
      }
      // 表级模式下整库选进来也要能展开看表，所以直接落成显式表清单
      if (objectMode.value === 'TABLE') {
        if (!rightExpanded.value.includes(schema)) rightExpanded.value = [...rightExpanded.value, schema]
        void materialize(schema)
      }
    } else {
      const { schema, table } = parseTableKey(key)
      const entry = entryOf(schema)
      if (entry) {
        if (!entry.all && !entry.tables.includes(table)) entry.tables.push(table)
      } else {
        selection.value.push({ schema, all: false, tables: [table] })
      }
      // 移过去的表要能立刻在右栏看到，所以顺手把目标库展开
      if (!rightExpanded.value.includes(schema)) rightExpanded.value = [...rightExpanded.value, schema]
    }
  }
  leftChecked.value = []
}

function moveLeft() {
  for (const key of rightChecked.value) {
    if (key.startsWith('s:')) {
      const schema = key.slice(2)
      selection.value = selection.value.filter((entry) => entry.schema !== schema)
    } else {
      const { schema, table } = parseTableKey(key)
      const entry = entryOf(schema)
      if (!entry) continue
      if (entry.all) {
        entry.all = false
        entry.tables = (catalogOf(schema)?.tables ?? []).filter((item) => item !== table)
      } else {
        entry.tables = entry.tables.filter((item) => item !== table)
      }
      if (!entry.all && entry.tables.length === 0) {
        selection.value = selection.value.filter((item) => item.schema !== schema)
      }
    }
  }
  rightChecked.value = []
}

/**
 * 把当前选择写进 store。三种模式互斥：
 * ALL 两个列表都空（后端据此做整实例迁移），SCHEMA 只填库名，TABLE 只填显式表。
 * 表级模式下「整库选中」会被 materialize 落成显式表清单，所以这里不需要再展开。
 */
function syncObjects() {
  store.form.selectionMode = objectMode.value
  if (objectMode.value === 'SCHEMA') {
    store.form.schemas = selection.value.map((entry) => entry.schema)
    store.form.tables = []
    return
  }
  if (objectMode.value === 'TABLE') {
    const tables: { schema: string; table: string }[] = []
    for (const entry of selection.value) {
      // entry.all 只在表清单还没落下来时短暂存在，此时按已加载的表展开。
      const names = entry.all ? (catalogOf(entry.schema)?.tables ?? []) : entry.tables
      for (const table of names) tables.push({ schema: entry.schema, table })
    }
    store.form.schemas = []
    store.form.tables = tables
    return
  }
  store.form.schemas = []
  store.form.tables = []
}

function onModeChange() {
  leftChecked.value = []
  rightChecked.value = []
  leftExpanded.value = []
  rightExpanded.value = []
  leftKeyword.value = ''
  rightKeyword.value = ''
  if (objectMode.value !== 'ALL') void loadCatalog()
  syncObjects()
}

watch(selection, syncObjects, { deep: true })
watch(
  () => store.form.sourceConnectionId,
  () => {
    catalog.value = []
    selection.value = []
    leftExpanded.value = []
    rightExpanded.value = []
    syncObjects()
    if (objectMode.value !== 'ALL') void loadCatalog(true)
  },
)


/**
 * 「下一步」在每一步的可用条件。预检查没通过就不能进入任务确认，
 * 任务确认里的启动同样要求预检查已通过。高级设置暂不接后端，恒可过。
 */
const nextEnabled = computed(() => {
  if (store.step === 0) return canNextBasic.value
  if (store.step === 1) return canNextSync.value
  if (store.step === 2) return !creating.value
  if (store.step === 3) return finished.value && passed.value && !startingPrecheck.value
  return passed.value && !starting.value
})

/**
 * 红色留给「启动迁移」——那是唯一不可回头的动作；前四步只是往前翻页，
 * 用主色。不可点击时统一回落到灰色默认按钮。
 */
const nextType = computed<'danger' | 'primary' | 'default'>(() => {
  if (!nextEnabled.value) return 'default'
  return store.step === 4 ? 'danger' : 'primary'
})

const nextLabel = computed(() => (store.step === 4 ? t('wizard.start') : t('wizard.next')))

/** 进入预检查后任务已经创建，不允许再回退改配置。 */
const canBack = computed(() => store.step === 1 || store.step === 2)

const topologyLabel = computed(() =>
  store.form.syncTopology === 'BIDIRECTIONAL'
    ? t('wizard.confirm.topologyBidirectional')
    : t('wizard.confirm.topologyOneWay'),
)

const modeLabel = computed(() => {
  const mode = createdTask.value?.mode ?? 'FULL_AND_INCREMENTAL'
  if (mode === 'FULL_ONLY') return t('wizard.confirm.modeFull')
  if (mode === 'INCREMENTAL_ONLY') return t('wizard.confirm.modeIncremental')
  return t('wizard.confirm.modeFullInc')
})

function addressOf(
  created: { host: string; port: number; database: string } | null | undefined,
  saved: { host: string; port: number; database: string } | undefined,
): string {
  const source = created ?? saved
  return source == null ? '—' : `${source.host}:${source.port}/${source.database}`
}

const sourceAddress = computed(() => addressOf(createdTask.value?.source, selectedSource.value))
const targetAddress = computed(() => addressOf(createdTask.value?.target, selectedTarget.value))

async function loadConnections() {
  if (loadingConnections.value) return
  loadingConnections.value = true
  connectionsError.value = ''
  try {
    const rows = await listConnections()
    if (disposed) return
    connections.value = rows
    if (!selectedSource.value) store.form.sourceConnectionId = undefined
    if (!selectedTarget.value) store.form.targetConnectionId = undefined
  } catch (error) {
    if (disposed) return
    connections.value = []
    store.form.sourceConnectionId = undefined
    store.form.targetConnectionId = undefined
    connectionsError.value = apiErrorMessage(error)
  } finally {
    if (!disposed) loadingConnections.value = false
  }
}

function stopPolling() {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function validateSync(): string | null {
  if (objectMode.value === 'SCHEMA' && store.form.schemas.length === 0) {
    return t('wizard.msg.needSchema')
  }
  if (objectMode.value === 'TABLE' && store.form.tables.length === 0) {
    return t('wizard.msg.needTable')
  }
  return null
}

function buildRequest(): CreateMigrationTaskRequest {
  if (!canNextBasic.value || store.form.sourceConnectionId == null || store.form.targetConnectionId == null) {
    throw new Error(t('wizard.msg.needBasic'))
  }
  return {
    name: store.form.name.trim(),
    mode: store.form.syncMode,
    sourceConnectionId: store.form.sourceConnectionId,
    targetConnectionId: store.form.targetConnectionId,
    options: store.toOptions(),
  }
}

async function refreshCreatedTask(id: number) {
  const task = await getTask(id)
  if (!disposed) createdTask.value = task
}

async function pollOnce(id: number) {
  const result = await getPrecheck(id)
  if (disposed) return
  precheck.value = result
  if (isPrecheckFinished(result)) {
    stopPolling()
    await refreshCreatedTask(id)
  }
}

function startPolling(id: number) {
  stopPolling()
  pollTimer = setInterval(() => {
    void pollOnce(id).catch(() => {
      /* keep last snapshot */
    })
  }, 200)
}

async function triggerPrecheck(id: number) {
  startingPrecheck.value = true
  try {
    const immediate = await precheckTask(id)
    if (disposed) return
    precheck.value = immediate
    if (isPrecheckFinished(immediate)) {
      await refreshCreatedTask(id)
      return
    }
    startPolling(id)
    await pollOnce(id)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    startingPrecheck.value = false
  }
}

/**
 * 默认名 DRS-xxxx 取自打开向导时的下一个自增值，提交前可能已被别人占用。
 * 撞名（TASK_CONFLICT）时按当前自增值重取名，静默重试一次；再失败交给上层报错。
 */
async function createWithDefaultNameRetry(): Promise<MigrationTaskResponse | null> {
  try {
    return await createTask(buildRequest())
  } catch (err) {
    if (apiErrorCode(err) !== 1003 || disposed) throw err
    store.form.name = await getNextTaskName()
    return await createTask(buildRequest())
  }
}

async function onNext() {
  if (creating.value || startingPrecheck.value || starting.value) return
  if (store.step === 0) {
    if (!canNextBasic.value) {
      ElMessage.warning(t('wizard.msg.needBasic'))
      return
    }
    store.step = 1
    return
  }
  if (store.step === 1) {
    const error = validateSync()
    if (error) {
      ElMessage.warning(error)
      return
    }
    store.step = 2
    return
  }
  if (store.step === 2) {
    creating.value = true
    try {
      const created = await createWithDefaultNameRetry()
      if (disposed || created == null) return
      store.taskId = created.id
      createdTask.value = created
      store.step = 3
      // 进入预检查即自动跑一次；失败项由「重新校验」重试，不再自动启动迁移。
      await triggerPrecheck(created.id)
    } catch (err) {
      ElMessage.error(apiErrorMessage(err))
    } finally {
      creating.value = false
    }
    return
  }
  if (store.step === 3) {
    if (!passed.value) {
      ElMessage.warning(t('wizard.msg.needPrecheck'))
      return
    }
    store.step = 4
    return
  }
  await onStartMigration()
}

/** 任务确认：迁移只在这里启动（结构迁移 → 全量 → 增量）。 */
async function onStartMigration() {
  const id = store.taskId
  if (id == null) {
    ElMessage.warning(t('wizard.msg.taskNotCreated'))
    return
  }
  starting.value = true
  try {
    const next = await startTask(id)
    ElMessage.success(t('wizard.msg.migrationStarted', { state: next.state ?? next.phase }))
    await router.push(`/tasks/${id}`)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    starting.value = false
  }
}

function onBack() {
  if (canBack.value && !creating.value) {
    store.step -= 1
  }
}

async function onRecheck() {
  if (store.taskId == null || !canRecheck.value) {
    return
  }
  await triggerPrecheck(store.taskId)
}

type RowTone = 'ok' | 'fail' | 'warn' | 'muted' | 'running'

interface RowStatus {
  tone: RowTone
  label: string
  /** 有原因/建议可看时才让整行可点。 */
  inspectable: boolean
}

/**
 * 状态文字按「执行状态 → 判定」两级判断：正在跑的一律转圈，
 * 跑完再看 verdict，最后按 severity 决定失败是「不通过」还是「请确认」。
 * 只有「没通过」的行才带详情（通过/跳过没什么可解释的）。
 */
function statusOf(item: PrecheckItem): RowStatus {
  if (item.state === 'PENDING' || item.state === 'RUNNING') {
    return { tone: 'running', label: t('wizard.precheck.itemStatus.checking'), inspectable: false }
  }
  if (item.state === 'BLOCKED') return { tone: 'muted', label: t('wizard.precheck.itemStatus.blocked'), inspectable: true }
  if (item.state === 'ERROR') return { tone: 'fail', label: t('wizard.precheck.itemStatus.error'), inspectable: true }
  if (item.verdict === 'PASS') return { tone: 'ok', label: t('wizard.precheck.itemStatus.pass'), inspectable: false }
  if (item.verdict === 'NOT_APPLICABLE') return { tone: 'muted', label: t('wizard.precheck.itemStatus.skip'), inspectable: false }
  if (item.verdict === 'UNKNOWN') return { tone: 'warn', label: t('wizard.precheck.itemStatus.unknown'), inspectable: true }
  return item.severity === 'BLOCKER'
    ? { tone: 'fail', label: t('wizard.precheck.itemStatus.fail'), inspectable: true }
    : { tone: 'warn', label: t('wizard.precheck.itemStatus.warn'), inspectable: true }
}

const detailItem = ref<PrecheckItem | null>(null)
const detailVisible = computed({
  get: () => detailItem.value != null,
  set: (visible: boolean) => {
    if (!visible) detailItem.value = null
  },
})

function openDetail(item: PrecheckItem) {
  detailItem.value = item
}

/**
 * 从任务列表点进来时带着 taskId + step 两个参数：任务已经建好了，只是回到预检查（step=3）
 * 或任务确认（step=4）。这里只回填快照并接着轮询，**不能**再调 POST /precheck ——
 * 预检查正在跑时 canPrecheck 返回 false，会直接 409。
 */
async function resumeFromQuery(): Promise<boolean> {
  const id = Number(route.query.taskId)
  const step = Number(route.query.step)
  if (!Number.isInteger(id) || id <= 0 || (step !== 3 && step !== 4)) return false
  resumeMode.value = true
  resuming.value = true
  store.step = step
  try {
    const task = await getTask(id)
    if (disposed) return true
    createdTask.value = task
    store.taskId = task.id
    store.form.name = task.name
    const result = await getPrecheck(id)
    if (disposed) return true
    precheck.value = result
    if (!isPrecheckFinished(result)) {
      startPolling(id)
      await pollOnce(id)
    }
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
    await router.replace('/tasks')
  } finally {
    resuming.value = false
  }
  return true
}

onMounted(async () => {
  if (await resumeFromQuery()) return
  await loadConnections()
  // 新任务的默认名：DRS-xxxx，xxxx = 下一个自增值（信息性，提交时撞名会自动重取一次）。
  if (!store.form.name) {
    try {
      store.form.name = await getNextTaskName()
    } catch {
      // 默认名拉不到不阻断建任务，用户手动输入即可。
    }
  }
})

onUnmounted(() => {
  disposed = true
  stopPolling()
  store.reset()
})
</script>

<template>
  <div class="wizard">
    <div class="page-toolbar">
      <h2>{{ pageTitle }}</h2>
      <el-button @click="router.push('/tasks')">{{ t('wizard.backToList') }}</el-button>
    </div>

    <el-steps :active="store.step" finish-status="success" align-center class="steps">
      <el-step v-for="item in steps" :key="item.title" :title="item.title" />
    </el-steps>

    <el-card v-loading="resuming" class="step-card" shadow="never">
      <div v-show="store.step === 0" class="step-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">{{ t('wizard.steps.basic') }}</h3>
            <p class="pane-note">{{ t('wizard.basic.note') }}</p>
          </div>
          <div class="pane-actions">
            <el-button :loading="loadingConnections" @click="loadConnections">{{ t('wizard.basic.refreshConnections') }}</el-button>
            <el-button tag="a" href="/connections" target="_blank" rel="noopener noreferrer">{{ t('wizard.basic.manageConnections') }}</el-button>
          </div>
        </div>
        <el-alert
          v-if="connectionsError"
          :title="t('wizard.basic.loadFailed', { msg: connectionsError })"
          type="error"
          :closable="false"
          show-icon
          class="data-source-error"
        />
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item :label="t('wizard.basic.taskName')" required>
            <el-input
              v-model="store.form.name"
              maxlength="128"
              show-word-limit
              :placeholder="t('wizard.basic.taskNamePlaceholder')"
              class="field"
            />
            <el-tooltip :content="t('wizard.basic.taskNameHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <SavedDataSourceSelect
            v-model="store.form.sourceConnectionId"
            title-key="connection.source"
            input-id="source-data-source"
            :database-types="['MYSQL']"
            database-label="MySQL"
            :connections="connections"
            :loading="loadingConnections"
          />
          <SavedDataSourceSelect
            v-model="store.form.targetConnectionId"
            title-key="connection.target"
            input-id="target-data-source"
            :database-types="['POSTGRESQL', 'MYSQL']"
            database-label="PostgreSQL / MySQL"
            :connections="connections"
            :loading="loadingConnections"
          />
        </el-form>
      </div>

      <div v-show="store.step === 1" class="step-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">{{ t('wizard.steps.sync') }}</h3>
            <p class="pane-note">{{ t('wizard.sync.note') }}</p>
          </div>
        </div>
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item :label="t('wizard.sync.mode')" required>
            <el-radio-group v-model="store.form.syncMode">
              <el-radio-button value="FULL_ONLY">{{ t('wizard.sync.modeFull') }}</el-radio-button>
              <el-radio-button value="INCREMENTAL_ONLY">{{ t('wizard.sync.modeIncremental') }}</el-radio-button>
              <el-radio-button value="FULL_AND_INCREMENTAL">{{ t('wizard.sync.modeFullIncremental') }}</el-radio-button>
            </el-radio-group>
            <el-tooltip
              :content="t('wizard.sync.modeHelp')"
              placement="top"
              effect="dark"
            >
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.sync.topology')" required>
            <el-radio-group v-model="store.form.syncTopology">
              <el-radio-button value="ONE_WAY">{{ t('wizard.sync.oneWay') }}</el-radio-button>
              <el-tooltip
                :disabled="bidirectionalAllowed"
                :content="
                  bidirectionalAllowed
                    ? t('wizard.sync.bidirectionalDisabledMysql')
                    : store.form.syncMode !== 'FULL_AND_INCREMENTAL'
                      ? t('wizard.sync.bidirectionalDisabledMode')
                      : t('wizard.sync.bidirectionalDisabledMysql')
                "
                placement="top"
              >
                <el-radio-button
                  value="BIDIRECTIONAL"
                  :disabled="!bidirectionalAllowed || store.form.syncMode !== 'FULL_AND_INCREMENTAL'"
                  >{{ t('wizard.sync.bidirectional') }}</el-radio-button
                >
              </el-tooltip>
            </el-radio-group>
            <el-tooltip
              :content="t('wizard.sync.bidirectionalHelp')"
              placement="top"
              effect="dark"
            >
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
            <p v-if="store.form.syncTopology === 'BIDIRECTIONAL'" class="topology-hint">
              {{ t('wizard.sync.topologyHint') }}
            </p>
          </el-form-item>
          <el-form-item :label="t('wizard.sync.objects')" required>
            <div class="object-mode">
              <el-radio-group v-model="objectMode" @change="onModeChange">
                <el-radio-button value="ALL">{{ t('wizard.sync.all') }}</el-radio-button>
                <el-radio-button value="TABLE">{{ t('wizard.sync.table') }}</el-radio-button>
                <el-radio-button value="SCHEMA">{{ t('wizard.sync.schema') }}</el-radio-button>
              </el-radio-group>
              <el-tooltip :content="objectModeHint" placement="top" effect="dark">
                <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </el-form-item>

          <div v-if="objectMode !== 'ALL'" class="transfer">
            <p class="transfer-tip">
              {{ t('wizard.sync.transferTip') }}<span class="danger">{{ t('wizard.sync.transferTipDanger') }}</span>
            </p>

            <div class="transfer-body">
              <section class="transfer-panel">
                <header class="transfer-head">
                  <el-tooltip :content="objectMode === 'TABLE' ? t('wizard.sync.tableModeHelp') : t('wizard.sync.schemaModeHelp')" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                  <el-button size="small" @click="leftChecked = checkAll(leftRows, leftChecked)">{{ t('common.all') }}</el-button>
                  <el-icon
                    class="head-refresh"
                    :class="{ 'is-loading': catalogLoading }"
                    :title="t('wizard.sync.refresh')"
                    @click="loadCatalog(true)"
                  >
                    <Refresh />
                  </el-icon>
                </header>
                <el-input v-model="leftKeyword" size="small" :placeholder="t('wizard.sync.searchExpanded')" :prefix-icon="Search" clearable />
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
                  <el-button size="small" @click="rightChecked = checkAll(rightRows, rightChecked)">{{ t('common.all') }}</el-button>
                </header>
                <el-input v-model="rightKeyword" size="small" :placeholder="t('wizard.sync.searchExpanded')" :prefix-icon="Search" clearable />
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

        </el-form>
      </div>

      <div v-show="store.step === 2" class="step-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">{{ t('wizard.steps.advanced') }}</h3>
            <p class="pane-note">{{ t('wizard.advanced.note') }}</p>
          </div>
        </div>

        <h4 class="sub-title">{{ t('wizard.advanced.fullTitle') }}</h4>
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item :label="t('wizard.advanced.syncObjects')" required>
            <el-checkbox v-model="store.form.fullSyncSchema">{{ t('wizard.advanced.syncSchema') }}</el-checkbox>
            <el-checkbox v-model="store.form.fullSyncData">{{ t('wizard.advanced.syncData') }}</el-checkbox>
            <el-checkbox v-model="store.form.fullSyncIndex">{{ t('wizard.advanced.syncIndex') }}</el-checkbox>
            <el-tooltip :content="t('wizard.advanced.objectsHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.advanced.exportConcurrency')" required>
            <el-input-number v-model="store.form.exportConcurrency" :min="1" :max="64" class="field field-narrow" />
            <el-tooltip :content="t('wizard.advanced.exportConcurrencyHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.advanced.importConcurrency')" required>
            <el-input-number v-model="store.form.importConcurrency" :min="1" :max="64" class="field field-narrow" />
            <el-tooltip :content="t('wizard.advanced.importConcurrencyHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.advanced.importMode')" required>
            <el-radio-group v-model="store.form.importMode">
              <el-radio-button value="COPY">COPY</el-radio-button>
              <el-radio-button value="INSERT">INSERT</el-radio-button>
            </el-radio-group>
            <el-tooltip :content="t('wizard.advanced.importModeHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.advanced.chunkMultiplier')" required>
            <el-input-number v-model="store.form.chunkMultiplier" :min="1" :max="10" class="field field-narrow" />
            <el-tooltip :content="t('wizard.advanced.chunkMultiplierHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.advanced.charsetAdapt')" required>
            <el-radio-group v-model="store.form.charsetAdapt">
              <el-radio-button value="AUTO">{{ t('wizard.advanced.charsetAdaptAuto') }}</el-radio-button>
              <el-radio-button value="KEEP">{{ t('wizard.advanced.charsetAdaptKeep') }}</el-radio-button>
            </el-radio-group>
            <el-tooltip :content="t('wizard.advanced.charsetAdaptHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-form>

        <h4 class="sub-title">{{ t('wizard.advanced.replayTitle') }}</h4>
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item :label="t('wizard.advanced.replayConcurrency')" required>
            <el-input-number v-model="store.form.replayConcurrency" :min="1" :max="128" class="field field-narrow" />
            <el-tooltip :content="t('wizard.advanced.replayConcurrencyHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item :label="t('wizard.advanced.conflictPolicy')" required>
            <el-radio-group v-model="store.form.conflictPolicy">
              <el-radio-button value="OVERWRITE">{{ t('wizard.advanced.conflictOverwrite') }}</el-radio-button>
              <el-radio-button value="ERROR">{{ t('wizard.advanced.conflictError') }}</el-radio-button>
              <el-radio-button value="IGNORE">{{ t('wizard.advanced.conflictIgnore') }}</el-radio-button>
            </el-radio-group>
            <el-tooltip :content="t('wizard.advanced.conflictHelp')" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-form>
      </div>

      <div v-show="store.step === 3" class="step-pane precheck-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">{{ t('wizard.steps.precheck') }}</h3>
            <p class="pane-note">{{ t('wizard.precheck.note') }}</p>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="summary">
          <el-descriptions-item :label="t('wizard.precheck.taskId')">{{ createdTask?.publicId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.basic.taskName')">{{ createdTask?.name ?? store.form.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.precheck.source')">
            {{ selectedSource ? `${selectedSource.name}（${sourceAddress}）` : sourceAddress }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('wizard.precheck.target')">
            {{ selectedTarget ? `${selectedTarget.name}（${targetAddress}）` : targetAddress }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('wizard.precheck.createdAt')" :span="2">{{ formatDateTime(createdTask?.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="rate-block">
          <div class="recheck-row">
            <el-button :disabled="!canRecheck" :loading="startingPrecheck" @click="onRecheck">
              {{ t('wizard.precheck.recheck') }}
            </el-button>
          </div>
          <div class="rate-label">{{ t('wizard.precheck.passRate') }}</div>
          <el-progress
            :percentage="passRate"
            :color="passed ? '#67c23a' : finished ? '#f56c6c' : '#409eff'"
          />
          <p class="tip">
            <template v-if="passed && warningCount > 0">
              {{ t('wizard.precheck.tipWarning', { n: warningCount }) }}
            </template>
            <template v-else-if="passed">{{ t('wizard.precheck.tipPassed') }}</template>
            <template v-else>{{ t('wizard.precheck.tipBlocked') }}</template>
          </p>
        </div>

        <div v-if="preparing" class="check-preparing">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ t('wizard.precheck.preparing') }}</span>
        </div>

        <section v-for="group in groups" :key="group.code" class="check-group">
          <h4>{{ group.label }}</h4>
          <div
            v-for="item in group.items"
            :key="item.id"
            class="check-row"
            :class="[`tone-${statusOf(item).tone}`, { clickable: statusOf(item).inspectable }]"
            @click="statusOf(item).inspectable && openDetail(item)"
          >
            <span class="check-title">{{ item.title }}</span>
            <span class="check-result">
              <el-icon v-if="statusOf(item).tone === 'running'" class="is-loading icon-running">
                <Loading />
              </el-icon>
              <el-icon v-else-if="statusOf(item).tone === 'ok'" class="icon-ok"><CircleCheck /></el-icon>
              <el-icon v-else-if="statusOf(item).tone === 'fail'" class="icon-fail"><CircleClose /></el-icon>
              <el-icon v-else-if="statusOf(item).tone === 'warn'" class="icon-warn"><WarningFilled /></el-icon>
              <el-icon v-else class="icon-muted"><Remove /></el-icon>
              <span class="check-status">{{ statusOf(item).label }}</span>
              <span v-if="statusOf(item).inspectable" class="check-detail">{{ t('wizard.precheck.detailLink') }}</span>
            </span>
          </div>
        </section>

        <el-alert
          v-if="passed"
          :title="warningCount > 0
            ? t('wizard.precheck.alertPassedWithWarning', { n: warningCount })
            : t('wizard.precheck.alertPassed')"
          type="success"
          :closable="false"
          show-icon
          class="done-alert"
        />
        <el-alert
          v-else-if="finished && !passed"
          :title="t('wizard.precheck.alertFailed')"
          type="error"
          :closable="false"
          show-icon
          class="done-alert"
        />

        <el-dialog v-model="detailVisible" :title="detailItem?.title ?? t('wizard.precheck.detailTitle')" width="560px">
          <div v-if="detailItem" class="detail-body">
            <div class="detail-row">
              <span class="detail-label">{{ t('wizard.precheck.result') }}</span>
              <span class="detail-value" :class="`text-${statusOf(detailItem).tone}`">
                {{ statusOf(detailItem).label }}
              </span>
            </div>
            <div v-if="detailItem.message" class="detail-row">
              <span class="detail-label">{{ t('wizard.precheck.reason') }}</span>
              <span class="detail-value">{{ detailItem.message }}</span>
            </div>
            <div v-if="detailItem.actual" class="detail-row">
              <span class="detail-label">{{ t('wizard.precheck.actual') }}</span>
              <span class="detail-value mono">{{ detailItem.actual }}</span>
            </div>
            <div v-if="detailItem.expected" class="detail-row">
              <span class="detail-label">{{ t('wizard.precheck.expected') }}</span>
              <span class="detail-value mono">{{ detailItem.expected }}</span>
            </div>
            <div v-if="detailItem.advice" class="detail-row">
              <span class="detail-label">{{ t('wizard.precheck.advice') }}</span>
              <span class="detail-value">{{ detailItem.advice }}</span>
            </div>
            <div v-if="detailItem.details.length > 0" class="detail-row">
              <span class="detail-label">{{ t('wizard.precheck.details', { n: detailItem.details.length }) }}</span>
              <ul class="detail-list">
                <li v-for="(line, index) in detailItem.details" :key="index" class="mono">{{ line }}</li>
              </ul>
            </div>
          </div>
          <template #footer>
            <el-button @click="detailVisible = false">{{ t('common.close') }}</el-button>
          </template>
        </el-dialog>
      </div>

      <div v-show="store.step === 4" class="step-pane confirm-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">{{ t('wizard.steps.confirm') }}</h3>
            <p class="pane-note">{{ t('wizard.confirm.note') }}</p>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="summary">
          <el-descriptions-item :label="t('wizard.precheck.taskId')">{{ createdTask?.publicId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.confirm.name')">{{ createdTask?.name ?? store.form.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.confirm.sourceAddr')">{{ sourceAddress }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.confirm.targetAddr')">{{ targetAddress }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.confirm.mode')">{{ modeLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.confirm.topology')">{{ topologyLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('wizard.precheck.createdAt')">{{ formatDateTime(createdTask?.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          :title="t('wizard.confirm.alert')"
          type="info"
          :closable="false"
          show-icon
          class="done-alert"
        />
      </div>

      <template #footer>
        <div class="wizard-footer">
          <el-button :disabled="!canBack || creating" @click="onBack">{{ t('wizard.prev') }}</el-button>
          <el-button
            :type="nextType"
            :loading="creating || starting"
            :disabled="!nextEnabled"
            @click="onNext"
          >
            {{ nextLabel }}
          </el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
/* 向导本身是一列，不铺满整个视口。
   之前卡片跟着视口一起拉宽，内容却只占左边一小块，右边一千多像素全是空的，
   整块面板因此显得空、散。 */
.wizard {
  max-width: 1120px;
}
.steps {
  margin: 8px 0 20px;
}
/* 当前步：圆圈整体填蓝、数字白、标题同蓝（默认是深色描边 + 深色数字） */
.steps :deep(.el-step__head.is-process) {
  color: #409eff;
  border-color: #409eff;
}
.steps :deep(.el-step__head.is-process .el-step__icon.is-text) {
  background: #409eff;
  border-color: #409eff;
  color: #ffffff;
}
.steps :deep(.el-step__title.is-process) {
  color: #409eff;
  font-weight: 500;
}
.step-card {
  border: 1px solid #ebeef5;
}
/* 面板内边距给足，标题和字段都不贴着卡片边 */
.step-card :deep(.el-card__body) {
  padding: 24px 32px;
}
/* 收进卡片底部，页面因此有一个明确的收口，不再把按钮悬在空白里 */
.step-card :deep(.el-card__footer) {
  padding: 12px 20px;
  border-top: 1px solid #ebeef5;
}
/* 只够挡住第一步/第二步之间的高度差；再高就会在卡片底部留出一块死白 */
.step-pane {
  min-height: 180px;
}
/* 段头：标题 + 一行灰注，动作按钮并排靠右。
   说明文字从字段区挪到这里，字段行因此只剩一行、节奏恒定。 */
.pane-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}
.pane-actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}
.pane-actions .el-button + .el-button {
  margin-left: 0;
}
.pane-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.pane-note {
  margin: 4px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
.sub-title {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.sub-title:not(:first-of-type) {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}
:deep(.el-radio-button__inner) {
  padding-left: 11px;
  padding-right: 11px;
}
/* 数字输入只需要装下 6~7 位数，用满 320px 会让它看起来像一个文本框 */
:deep(.field-narrow) {
  width: 140px;
}
.data-source-error {
  margin-bottom: 16px;
}
.object-mode {
  display: flex;
  align-items: center;
  gap: 8px;
}
.topology-hint {
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
/* 与 label 列右对齐：穿梭框的左边界跟其他字段起点对齐 */
.transfer {
  margin: 0 0 18px calc(var(--ops-label-w) + 14px);
}
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
.recheck-row {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}
.summary {
  margin-bottom: 16px;
}
.rate-block {
  margin-bottom: 20px;
}
.rate-label {
  margin-bottom: 6px;
  font-size: 14px;
}
.tip {
  margin: 8px 0 0;
  color: #909399;
  font-size: 12px;
}
.check-group {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fafafa;
  border-radius: 6px;
}
.check-group h4 {
  margin: 0 0 4px;
  padding: 6px 0;
  font-size: 14px;
  border-bottom: 1px solid #ebeef5;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px dashed #ebeef5;
  font-size: 13px;
}
.check-row:last-child {
  border-bottom: none;
}
.check-row.clickable {
  cursor: pointer;
}
.check-row.clickable:hover {
  background: #f2f6fc;
}
.check-title {
  flex: 1;
  min-width: 0;
  color: #303133;
}
/* 状态与「查看详情」共占一个定宽块：状态文字左对齐成一列，链接紧贴在它右边。 */
.check-result {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 140px;
}
.check-result .el-icon {
  flex: 0 0 auto;
  font-size: 15px;
}
.check-status {
  flex: 0 0 56px;
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
}
.check-detail {
  color: #409eff;
  font-size: 12px;
  white-space: nowrap;
}
.tone-ok .check-status {
  color: #67c23a;
}
.tone-fail .check-status {
  color: #f56c6c;
}
.tone-warn .check-status {
  color: #e6a23c;
}
.tone-muted .check-status {
  color: #909399;
}
.tone-running .check-status {
  color: #409eff;
}
.icon-ok {
  color: #67c23a;
}
.icon-fail {
  color: #f56c6c;
}
.icon-warn {
  color: #e6a23c;
}
.icon-muted,
.icon-running {
  color: #909399;
}
.check-preparing {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 24px 0;
  color: #909399;
  font-size: 13px;
}
.detail-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail-row {
  display: flex;
  gap: 12px;
  font-size: 13px;
  line-height: 1.6;
}
.detail-label {
  flex: 0 0 72px;
  color: #909399;
}
.detail-value {
  flex: 1;
  min-width: 0;
  color: #303133;
  word-break: break-all;
}
.detail-value.mono,
.detail-list .mono {
  font-family: Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
}
.detail-list {
  flex: 1;
  max-height: 220px;
  margin: 0;
  padding-left: 18px;
  overflow-y: auto;
  color: #303133;
}
.text-ok {
  color: #67c23a;
}
.text-fail {
  color: #f56c6c;
}
.text-warn {
  color: #e6a23c;
}
.text-muted,
.text-running {
  color: #909399;
}
.done-alert {
  margin-top: 8px;
}
.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.wizard-footer .el-button + .el-button {
  margin-left: 0;
}
</style>
