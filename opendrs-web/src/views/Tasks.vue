<script setup lang="ts">
import { listConnections } from '@/api/connection'
import { apiErrorMessage } from '@/api/http'
import {
  createTask,
  deleteTask,
  listTasks,
  precheckTask,
  startTask,
  stopTask,
} from '@/api/task'
import ConnectionFields from '@/components/ConnectionFields.vue'
import {
  copySavedConnection,
  emptyConnection,
  type ConnectionInfo,
  type ConnectionResponse,
  type CreateMigrationTaskRequest,
  type MigrationPrecheckResponse,
  type MigrationTaskSummary,
  type SchemaObject,
} from '@/types/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const loading = ref(false)
const rows = ref<MigrationTaskSummary[]>([])
const savedConnections = ref<ConnectionResponse[]>([])
const dialogVisible = ref(false)
const creating = ref(false)
const actionId = ref<number | null>(null)

interface SchemaRow {
  schema: string
  allTables: boolean
  tables: string[]
  excludeTables: string[]
}

const form = reactive({
  name: '',
  sourceSavedId: null as number | null,
  targetSavedId: null as number | null,
  source: emptyConnection('MYSQL'),
  target: emptyConnection('POSTGRESQL'),
  sourceExtra: '',
  targetExtra: '',
  objects: [{ schema: '', allTables: false, tables: [] as string[], excludeTables: [] as string[] }] as SchemaRow[],
  batchSize: 1000,
  fullDumpParallelism: 8,
  databaseServerId: undefined as number | undefined,
})

async function load() {
  loading.value = true
  try {
    rows.value = await listTasks()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function loadSavedConnections() {
  try {
    savedConnections.value = await listConnections()
  } catch {
    savedConnections.value = []
  }
}

function extraToText(extra?: Record<string, unknown> | null): string {
  if (!extra || Object.keys(extra).length === 0) {
    return ''
  }
  return JSON.stringify(extra, null, 2)
}

function applySaved(which: 'source' | 'target', id: number | null) {
  const saved = savedConnections.value.find((item) => item.id === id)
  if (!saved) {
    return
  }
  const copied = copySavedConnection(saved)
  if (which === 'source') {
    form.source = copied
    form.sourceExtra = extraToText(saved.extra)
  } else {
    form.target = copied
    form.targetExtra = extraToText(saved.extra)
  }
}

function openCreate() {
  form.name = ''
  form.sourceSavedId = null
  form.targetSavedId = null
  form.source = emptyConnection('MYSQL')
  form.target = emptyConnection('POSTGRESQL')
  form.sourceExtra = ''
  form.targetExtra = ''
  form.objects = [{ schema: '', allTables: false, tables: [], excludeTables: [] }]
  form.batchSize = 1000
  form.fullDumpParallelism = 8
  form.databaseServerId = undefined
  dialogVisible.value = true
  void loadSavedConnections()
}

function addObject() {
  form.objects.push({ schema: '', allTables: false, tables: [], excludeTables: [] })
}

function removeObject(index: number) {
  form.objects.splice(index, 1)
}

function parseExtra(text: string): Record<string, unknown> | undefined {
  const trimmed = text.trim()
  if (!trimmed) {
    return undefined
  }
  const parsed: unknown = JSON.parse(trimmed)
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('额外参数必须是 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

function toConnection(info: ConnectionInfo, extraText: string): ConnectionInfo {
  return { ...info, extra: parseExtra(extraText) }
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

function buildRequest(): CreateMigrationTaskRequest {
  const request: CreateMigrationTaskRequest = {
    name: form.name,
    mode: 'FULL_AND_INCREMENTAL',
    source: toConnection(form.source, form.sourceExtra),
    target: toConnection(form.target, form.targetExtra),
    tables: { objects: toObjects() },
    options: {
      batchSize: form.batchSize,
      fullDumpParallelism: form.fullDumpParallelism,
      databaseServerId: form.databaseServerId ?? null,
    },
  }
  return request
}

async function onCreate() {
  creating.value = true
  try {
    await createTask(buildRequest())
    ElMessage.success('创建成功')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    creating.value = false
  }
}

function notifyPrecheck(result: MigrationPrecheckResponse) {
  if (result.ok) {
    ElMessage.success(`预检查通过，任务已自动启动（jobState=${result.jobState}）`)
    return
  }
  const failed = result.results?.filter((item) => !item.ok).map((item) => item.message) ?? []
  ElMessage.warning(failed.length > 0 ? `预检查未通过：${failed.join('；')}` : '预检查未通过')
}

async function onPrecheck(row: MigrationTaskSummary) {
  actionId.value = row.id
  try {
    const result = await precheckTask(row.id)
    notifyPrecheck(result)
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    actionId.value = null
  }
}

async function onStart(row: MigrationTaskSummary) {
  actionId.value = row.id
  try {
    const status = await startTask(row.id)
    ElMessage.success(`已启动（jobState=${status.jobState}）`)
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    actionId.value = null
  }
}

async function onStop(row: MigrationTaskSummary) {
  actionId.value = row.id
  try {
    const status = await stopTask(row.id)
    ElMessage.success(`已停止（jobState=${status.jobState}）`)
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    actionId.value = null
  }
}

async function onDelete(row: MigrationTaskSummary) {
  try {
    await ElMessageBox.confirm(`确认删除任务「${row.name}」？`, '删除任务', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteTask(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      return
    }
    ElMessage.error(apiErrorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-toolbar">
      <h2>任务</h2>
      <div>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建</el-button>
      </div>
    </div>
    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="mode" label="模式" width="180" />
      <el-table-column prop="jobPhase" label="阶段" width="150" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">{{ row.jobState ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="router.push(`/tasks/${row.id}`)">详情</el-button>
          <el-button type="primary" link :loading="actionId === row.id" @click="onPrecheck(row)">
            预检查
          </el-button>
          <el-button type="success" link :loading="actionId === row.id" @click="onStart(row)">
            启动
          </el-button>
          <el-button type="warning" link :loading="actionId === row.id" @click="onStop(row)">停止</el-button>
          <el-button type="danger" link @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建任务" width="820px" top="4vh" destroy-on-close>
      <el-form label-width="130px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="模式">
          <el-select model-value="FULL_AND_INCREMENTAL" disabled style="width: 280px">
            <el-option label="FULL_AND_INCREMENTAL" value="FULL_AND_INCREMENTAL" />
          </el-select>
          <span class="hint">v1 仅支持全量+增量</span>
        </el-form-item>

        <el-divider content-position="left">源连接</el-divider>
        <el-form-item label="已保存连接">
          <el-select
            v-model="form.sourceSavedId"
            clearable
            filterable
            placeholder="可选，复制字段后请重新输入密码"
            style="width: 100%"
            @change="(id: number | null) => applySaved('source', id)"
          >
            <el-option
              v-for="item in savedConnections"
              :key="item.id"
              :label="`${item.name} (${item.type})`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <ConnectionFields
          v-model="form.source"
          v-model:extra-text="form.sourceExtra"
          password-placeholder="已保存连接的密码为 ***，请重新输入"
        />

        <el-divider content-position="left">目标连接</el-divider>
        <el-form-item label="已保存连接">
          <el-select
            v-model="form.targetSavedId"
            clearable
            filterable
            placeholder="可选，复制字段后请重新输入密码"
            style="width: 100%"
            @change="(id: number | null) => applySaved('target', id)"
          >
            <el-option
              v-for="item in savedConnections"
              :key="item.id"
              :label="`${item.name} (${item.type})`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <ConnectionFields
          v-model="form.target"
          v-model:extra-text="form.targetExtra"
          password-placeholder="已保存连接的密码为 ***，请重新输入"
        />

        <el-divider content-position="left">表选择</el-divider>
        <div v-for="(object, index) in form.objects" :key="index" class="schema-row">
          <el-form-item :label="`Schema ${index + 1}`" required>
            <el-input v-model="object.schema" placeholder="schema" />
          </el-form-item>
          <el-form-item label="全部表">
            <el-switch v-model="object.allTables" />
          </el-form-item>
          <el-form-item v-if="!object.allTables" label="包含表">
            <el-select v-model="object.tables" multiple filterable allow-create default-first-option style="width: 100%" />
          </el-form-item>
          <el-form-item label="排除表">
            <el-select
              v-model="object.excludeTables"
              multiple
              filterable
              allow-create
              default-first-option
              style="width: 100%"
            />
          </el-form-item>
          <el-button v-if="form.objects.length > 1" type="danger" link @click="removeObject(index)">
            删除此 schema
          </el-button>
        </div>
        <el-button @click="addObject">添加 schema</el-button>
        <p class="hint">v1 不填写 mappings，目标名与源名相同。</p>

        <el-divider content-position="left">选项</el-divider>
        <el-form-item label="batchSize">
          <el-input-number v-model="form.batchSize" :min="1" />
        </el-form-item>
        <el-form-item label="fullDumpParallelism">
          <el-input-number v-model="form.fullDumpParallelism" :min="1" />
        </el-form-item>
        <el-form-item label="databaseServerId">
          <el-input-number v-model="form.databaseServerId" :min="1" :controls="false" />
          <span class="hint">源为 MySQL 时需要</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.schema-row {
  margin-bottom: 8px;
  padding: 8px 12px 0;
  background: #fafafa;
  border-radius: 6px;
}
</style>
