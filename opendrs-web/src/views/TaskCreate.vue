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
import { useRoute, useRouter } from 'vue-router'

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
const pageTitle = computed(() => (resumeMode.value ? '继续任务' : '新建迁移任务'))

const steps = [
  { title: '基本信息' },
  { title: '设置同步' },
  { title: '高级设置' },
  { title: '预检查' },
  { title: '任务确认' },
]

const GROUP_LABELS: Record<CheckGroup, string> = {
  PARAMETER: '数据库参数检查',
  INSTANCE: '目标实例状态检查',
  PERMISSION: '数据库用户权限检查',
  VERSION: '数据库版本检查',
  NETWORK: '网络状态',
  OBJECT: '数据库对象检查',
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
  return [...byGroup.entries()].map(([code, list]) => ({ code, label: GROUP_LABELS[code] ?? code, items: list }))
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

const OBJECT_MODE_HINT =
  '全部迁移：源实例整个照搬，不用选库表。表级迁移：挑具体的表，勾库等于勾该库全部表。库级迁移：按库整库迁移，不细分到表。'

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
        label: '正在读取表…',
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
        label: '正在读取表…',
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

const nextLabel = computed(() => (store.step === 4 ? '启动迁移' : '下一步'))

/** 进入预检查后任务已经创建，不允许再回退改配置。 */
const canBack = computed(() => store.step === 1 || store.step === 2)

const topologyLabel = computed(() => (store.form.syncTopology === 'BIDIRECTIONAL' ? '双向同步' : '单向同步'))

const modeLabel = computed(() => {
  const mode = createdTask.value?.mode ?? 'FULL_AND_INCREMENTAL'
  if (mode === 'FULL_ONLY') return '全量迁移'
  if (mode === 'INCREMENTAL_ONLY') return '增量迁移'
  return '全量迁移+增量迁移'
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
    return '请至少选择一个库'
  }
  if (objectMode.value === 'TABLE' && store.form.tables.length === 0) {
    return '请至少选择一张表'
  }
  return null
}

function buildRequest(): CreateMigrationTaskRequest {
  if (!canNextBasic.value || store.form.sourceConnectionId == null || store.form.targetConnectionId == null) {
    throw new Error('请填写任务名称并选择源数据源和目标数据源')
  }
  return {
    name: store.form.name.trim(),
    mode: 'FULL_AND_INCREMENTAL',
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
      ElMessage.warning('请填写任务名称并选择源数据源和目标数据源')
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
      ElMessage.warning('预检查未通过，请先处理失败项并重新校验')
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
    ElMessage.warning('任务尚未创建')
    return
  }
  starting.value = true
  try {
    const next = await startTask(id)
    ElMessage.success(`迁移已启动（${next.state ?? next.phase}）`)
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
    return { tone: 'running', label: '正在检查', inspectable: false }
  }
  if (item.state === 'BLOCKED') return { tone: 'muted', label: '阻塞', inspectable: true }
  if (item.state === 'ERROR') return { tone: 'fail', label: '检查异常', inspectable: true }
  if (item.verdict === 'PASS') return { tone: 'ok', label: '通过', inspectable: false }
  if (item.verdict === 'NOT_APPLICABLE') return { tone: 'muted', label: '跳过', inspectable: false }
  if (item.verdict === 'UNKNOWN') return { tone: 'warn', label: '未知', inspectable: true }
  return item.severity === 'BLOCKER'
    ? { tone: 'fail', label: '不通过', inspectable: true }
    : { tone: 'warn', label: '请确认', inspectable: true }
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
      <el-button @click="router.push('/tasks')">返回列表</el-button>
    </div>

    <el-steps :active="store.step" finish-status="success" align-center class="steps">
      <el-step v-for="item in steps" :key="item.title" :title="item.title" />
    </el-steps>

    <el-card v-loading="resuming" class="step-card" shadow="never">
      <div v-show="store.step === 0" class="step-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">基本信息</h3>
            <p class="pane-note">选择已录入的数据源；连接测试请到「数据源」页面完成。</p>
          </div>
          <div class="pane-actions">
            <el-button :loading="loadingConnections" @click="loadConnections">刷新数据源</el-button>
            <el-button tag="a" href="/connections" target="_blank" rel="noopener noreferrer">管理数据源</el-button>
          </div>
        </div>
        <el-alert
          v-if="connectionsError"
          :title="`数据源加载失败：${connectionsError}`"
          type="error"
          :closable="false"
          show-icon
          class="data-source-error"
        />
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item label="任务名称" required>
            <el-input
              v-model="store.form.name"
              maxlength="128"
              show-word-limit
              placeholder="请输入任务名称"
              class="field"
            />
            <el-tooltip content="任务的显示名称，创建后可在任务列表里按它查找。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <SavedDataSourceSelect
            v-model="store.form.sourceConnectionId"
            title="源数据源"
            input-id="source-data-source"
            :database-types="['MYSQL']"
            database-label="MySQL"
            :connections="connections"
            :loading="loadingConnections"
          />
          <SavedDataSourceSelect
            v-model="store.form.targetConnectionId"
            title="目标数据源"
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
            <h3 class="pane-title">同步设置</h3>
            <p class="pane-note">确定迁移方向与范围；迁移对象决定后续全量与增量的抓取口径。</p>
          </div>
        </div>
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item label="同步模式" required>
            <el-radio-group model-value="FULL_AND_INCREMENTAL">
              <el-radio-button value="FULL_ONLY" disabled>全量</el-radio-button>
              <el-radio-button value="INCREMENTAL_ONLY" disabled>增量</el-radio-button>
              <el-radio-button value="FULL_AND_INCREMENTAL">全量+增量</el-radio-button>
            </el-radio-group>
            <el-tooltip content="迁移方式由后端按任务类型判定，暂固定为「全量 + 增量」。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="同步拓扑" required>
            <el-radio-group v-model="store.form.syncTopology">
              <el-radio-button value="ONE_WAY">单向同步</el-radio-button>
              <el-tooltip
                :disabled="bidirectionalAllowed"
                content="双向同步仅支持 MySQL ↔ MySQL"
                placement="top"
              >
                <el-radio-button value="BIDIRECTIONAL" :disabled="!bidirectionalAllowed">双向同步</el-radio-button>
              </el-tooltip>
            </el-radio-group>
            <el-tooltip
              content="双向同步两端均为 MySQL，不做结构迁移；预检查会逐项校验前提条件。"
              placement="top"
              effect="dark"
            >
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
            <p v-if="store.form.syncTopology === 'BIDIRECTIONAL'" class="topology-hint">
              两端均为 MySQL，不迁移表结构。
            </p>
          </el-form-item>
          <el-form-item label="迁移对象" required>
            <div class="object-mode">
              <el-radio-group v-model="objectMode" @change="onModeChange">
                <el-radio-button value="ALL">全部迁移</el-radio-button>
                <el-radio-button value="TABLE">表级迁移</el-radio-button>
                <el-radio-button value="SCHEMA">库级迁移</el-radio-button>
              </el-radio-group>
              <el-tooltip :content="OBJECT_MODE_HINT" placement="top" effect="dark">
                <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
              </el-tooltip>
            </div>
          </el-form-item>

          <div v-if="objectMode !== 'ALL'" class="transfer">
            <p class="transfer-tip">
              左边未选、右边已选，勾选后点中间箭头移动。<span class="danger">源库有变更时点右上方刷新。</span>
            </p>

            <div class="transfer-body">
              <section class="transfer-panel">
                <header class="transfer-head">
                  <el-tooltip :content="objectMode === 'TABLE' ? '按表挑选，勾选库等于勾选该库全部表' : '按库整库迁移，不细分到表'" placement="top">
                    <el-icon class="head-help"><QuestionFilled /></el-icon>
                  </el-tooltip>
                  <el-button size="small" @click="leftChecked = checkAll(leftRows, leftChecked)">全选</el-button>
                  <el-icon
                    class="head-refresh"
                    :class="{ 'is-loading': catalogLoading }"
                    title="刷新"
                    @click="loadCatalog(true)"
                  >
                    <Refresh />
                  </el-icon>
                </header>
                <el-input v-model="leftKeyword" size="small" placeholder="对展开的库进行搜索" :prefix-icon="Search" clearable />
                <div class="tree">
                  <div v-if="catalogLoading" class="tree-state">
                    <el-icon class="is-loading tree-spinner"><Loading /></el-icon>
                    <span>正在读取库表…</span>
                  </div>
                  <div v-else-if="catalogError" class="tree-state error">{{ catalogError }}</div>
                  <div v-else-if="leftRows.length === 0" class="tree-state">没有可迁移的对象</div>
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
                  <el-button size="small" @click="rightChecked = checkAll(rightRows, rightChecked)">全选</el-button>
                </header>
                <el-input v-model="rightKeyword" size="small" placeholder="对展开的库进行搜索" :prefix-icon="Search" clearable />
                <div class="tree">
                  <div v-if="rightRows.length === 0" class="tree-state">还没有选择对象</div>
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
            <h3 class="pane-title">高级设置</h3>
            <p class="pane-note">全量与增量的抓取策略；不确定时保持默认即可。</p>
          </div>
        </div>

        <h4 class="sub-title">全量设置</h4>
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item label="同步对象" required>
            <el-checkbox v-model="store.form.fullSyncSchema">同步表结构（包含主键和唯一键）</el-checkbox>
            <el-checkbox v-model="store.form.fullSyncData">同步数据</el-checkbox>
            <el-checkbox v-model="store.form.fullSyncIndex">同步普通索引</el-checkbox>
            <el-tooltip content="全量迁移时从源端导出的对象范围。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="导出并发" required>
            <el-input-number v-model="store.form.exportConcurrency" :min="1" :max="64" class="field field-narrow" />
            <el-tooltip content="从源端读库的并发线程数，网络带宽充足时可调高。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="导入并发" required>
            <el-input-number v-model="store.form.importConcurrency" :min="1" :max="64" class="field field-narrow" />
            <el-tooltip content="向目标端写库的并发线程数，受目标库写入能力限制。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="导入模式" required>
            <el-radio-group v-model="store.form.importMode">
              <el-radio-button value="COPY">COPY</el-radio-button>
              <el-radio-button value="INSERT">INSERT</el-radio-button>
            </el-radio-group>
            <el-tooltip content="COPY 走 PostgreSQL 的二进制拷贝通道，最快；INSERT 兼容所有场景。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="分片记录数" required>
            <el-input-number v-model="store.form.chunkSize" :min="1000" :step="10000" class="field field-narrow" />
            <el-tooltip content="每一片的记录数，决定单次提交的批量大小。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="字符集适配" required>
            <el-radio-group v-model="store.form.charsetAdapt">
              <el-radio-button value="AUTO">自动适配字符集长度</el-radio-button>
              <el-radio-button value="KEEP">保持原库字符长度</el-radio-button>
            </el-radio-group>
            <el-tooltip content="结构迁移时如何处理目标端的字符类型长度：自动拉齐到源端长度，或保持目标端定义。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-form>

        <h4 class="sub-title">增量回放设置</h4>
        <el-form :label-width="'var(--ops-label-w)'" label-position="left" class="ops-form">
          <el-form-item label="回放并发" required>
            <el-input-number v-model="store.form.replayConcurrency" :min="1" :max="128" class="field field-narrow" />
            <el-tooltip content="增量阶段回放 binlog 的并发线程数。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
          <el-form-item label="冲突策略" required>
            <el-radio-group v-model="store.form.conflictPolicy">
              <el-radio-button value="OVERWRITE">覆盖</el-radio-button>
              <el-radio-button value="ERROR">报错</el-radio-button>
              <el-radio-button value="IGNORE">忽略</el-radio-button>
            </el-radio-group>
            <el-tooltip content="源端与目标端同一主键冲突时的处理方式。" placement="top" effect="dark">
              <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
            </el-tooltip>
          </el-form-item>
        </el-form>
      </div>

      <div v-show="store.step === 3" class="step-pane precheck-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">预检查</h3>
            <p class="pane-note">逐项核对源端与目标端的迁移前提条件。</p>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="summary">
          <el-descriptions-item label="任务 ID">{{ createdTask?.publicId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="任务名称">{{ createdTask?.name ?? store.form.name }}</el-descriptions-item>
          <el-descriptions-item label="源数据源">
            {{ selectedSource ? `${selectedSource.name}（${sourceAddress}）` : sourceAddress }}
          </el-descriptions-item>
          <el-descriptions-item label="目标数据源">
            {{ selectedTarget ? `${selectedTarget.name}（${targetAddress}）` : targetAddress }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ formatDateTime(createdTask?.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="rate-block">
          <div class="recheck-row">
            <el-button :disabled="!canRecheck" :loading="startingPrecheck" @click="onRecheck">
              重新校验
            </el-button>
          </div>
          <div class="rate-label">预检查通过率</div>
          <el-progress
            :percentage="passRate"
            :color="passed ? '#67c23a' : finished ? '#f56c6c' : '#409eff'"
          />
          <p class="tip">
            <template v-if="passed && warningCount > 0">
              有 {{ warningCount }} 项需要确认，确认无误后可进入任务确认。
            </template>
            <template v-else-if="passed">全部检查已通过，可以进入任务确认。</template>
            <template v-else>全部通过后才能进入任务确认并启动迁移。</template>
          </p>
        </div>

        <div v-if="preparing" class="check-preparing">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>正在准备检查项…</span>
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
              <span v-if="statusOf(item).inspectable" class="check-detail">查看详情</span>
            </span>
          </div>
        </section>

        <el-alert
          v-if="passed"
          :title="warningCount > 0
            ? `预检查已通过，有 ${warningCount} 项需要确认，请点击下一步进入任务确认`
            : '预检查已通过，请点击下一步进入任务确认'"
          type="success"
          :closable="false"
          show-icon
          class="done-alert"
        />
        <el-alert
          v-else-if="finished && !passed"
          title="预检查未通过，请根据失败项处理后重新校验"
          type="error"
          :closable="false"
          show-icon
          class="done-alert"
        />

        <el-dialog v-model="detailVisible" :title="detailItem?.title ?? '检查详情'" width="560px">
          <div v-if="detailItem" class="detail-body">
            <div class="detail-row">
              <span class="detail-label">检查结果</span>
              <span class="detail-value" :class="`text-${statusOf(detailItem).tone}`">
                {{ statusOf(detailItem).label }}
              </span>
            </div>
            <div v-if="detailItem.message" class="detail-row">
              <span class="detail-label">原因</span>
              <span class="detail-value">{{ detailItem.message }}</span>
            </div>
            <div v-if="detailItem.actual" class="detail-row">
              <span class="detail-label">实际值</span>
              <span class="detail-value mono">{{ detailItem.actual }}</span>
            </div>
            <div v-if="detailItem.expected" class="detail-row">
              <span class="detail-label">期望值</span>
              <span class="detail-value mono">{{ detailItem.expected }}</span>
            </div>
            <div v-if="detailItem.advice" class="detail-row">
              <span class="detail-label">处理建议</span>
              <span class="detail-value">{{ detailItem.advice }}</span>
            </div>
            <div v-if="detailItem.details.length > 0" class="detail-row">
              <span class="detail-label">明细（{{ detailItem.details.length }}）</span>
              <ul class="detail-list">
                <li v-for="(line, index) in detailItem.details" :key="index" class="mono">{{ line }}</li>
              </ul>
            </div>
          </div>
          <template #footer>
            <el-button @click="detailVisible = false">关闭</el-button>
          </template>
        </el-dialog>
      </div>

      <div v-show="store.step === 4" class="step-pane confirm-pane">
        <div class="pane-head">
          <div>
            <h3 class="pane-title">任务确认</h3>
            <p class="pane-note">确认下方信息无误后启动迁移。</p>
          </div>
        </div>
        <el-descriptions :column="2" border size="small" class="summary">
          <el-descriptions-item label="任务 ID">{{ createdTask?.publicId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ createdTask?.name ?? store.form.name }}</el-descriptions-item>
          <el-descriptions-item label="源库地址">{{ sourceAddress }}</el-descriptions-item>
          <el-descriptions-item label="目标库地址">{{ targetAddress }}</el-descriptions-item>
          <el-descriptions-item label="迁移类型">{{ modeLabel }}</el-descriptions-item>
          <el-descriptions-item label="同步拓扑">{{ topologyLabel }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(createdTask?.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          title="确认无误后点击「启动迁移」：先创建目标表结构，再做全量数据迁移，最后进入增量同步。"
          type="info"
          :closable="false"
          show-icon
          class="done-alert"
        />
      </div>

      <template #footer>
        <div class="wizard-footer">
          <el-button :disabled="!canBack || creating" @click="onBack">上一步</el-button>
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
