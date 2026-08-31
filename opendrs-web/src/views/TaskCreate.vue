<script setup lang="ts">
import { listConnections, testAdHocConnection } from '@/api/connection'
import { apiErrorMessage } from '@/api/http'
import { createTask, getPrecheck, getTask, precheckTask } from '@/api/task'
import ConnectionFields from '@/components/ConnectionFields.vue'
import { useTaskCreateStore } from '@/stores/taskCreate'
import {
  copySavedConnection,
  isPrecheckFinished,
  isPrecheckRunning,
  type CheckResult,
  type ConnectionResponse,
  type CreateMigrationTaskRequest,
  type MigrationPrecheckResponse,
  type MigrationTaskResponse,
} from '@/types/api'
import { CircleCheck, CircleClose, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const store = useTaskCreateStore()
const savedConnections = ref<ConnectionResponse[]>([])
const testingSource = ref(false)
const testingTarget = ref(false)
const creating = ref(false)
const startingPrecheck = ref(false)
const createdTask = ref<MigrationTaskResponse | null>(null)
const precheck = ref<MigrationPrecheckResponse | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

const steps = [
  { title: '基本信息' },
  { title: '源库及目标库' },
  { title: '设置同步' },
  { title: '预检查' },
]

const sourceSaved = computed(() => savedConnections.value.filter((item) => item.type === 'MYSQL'))
const targetSaved = computed(() => savedConnections.value.filter((item) => item.type === 'POSTGRESQL'))

const sourceResults = computed(() => precheck.value?.sourceResults ?? [])
const targetResults = computed(() => precheck.value?.targetResults ?? [])
const running = computed(() => isPrecheckRunning(precheck.value))
const finished = computed(() => precheck.value != null && isPrecheckFinished(precheck.value))
const passed = computed(() => precheck.value?.ok === true)

const passTotal = computed(() => {
  const completed = sourceResults.value.length + targetResults.value.length
  const pending =
    (running.value && sourceResults.value.length === 0 ? 1 : 0) +
    (running.value && targetResults.value.length === 0 ? 1 : 0)
  return Math.max(completed + pending, 1)
})

const passCount = computed(() => {
  const all = [...sourceResults.value, ...targetResults.value]
  return all.filter((item) => item.ok).length
})

const passRate = computed(() => Math.round((passCount.value / passTotal.value) * 100))

const canNextBasic = computed(() => store.form.name.trim().length > 0)
const sourcePassed = computed(() => store.sourceTested())
const targetPassed = computed(() => store.targetTested())
const canNextConnections = computed(() => sourcePassed.value && targetPassed.value)

function stopPolling() {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function loadSaved() {
  try {
    savedConnections.value = await listConnections()
  } catch {
    savedConnections.value = []
  }
}

function applySaved(which: 'source' | 'target', id: number | null) {
  const saved = savedConnections.value.find((item) => item.id === id)
  if (!saved) {
    return
  }
  const copied = copySavedConnection(saved)
  if (which === 'source') {
    store.form.source = copied
  } else {
    store.form.target = copied
  }
}

function validateConnection(info: { host: string; database: string; username: string; password: string; port: number }): string | null {
  if (!info.host.trim()) {
    return '请填写主机'
  }
  if (!info.port) {
    return '请填写端口'
  }
  if (!info.database.trim()) {
    return '请填写数据库'
  }
  if (!info.username.trim()) {
    return '请填写用户名'
  }
  if (!info.password) {
    return '请填写密码'
  }
  return null
}

async function testSide(which: 'source' | 'target') {
  const info = which === 'source' ? store.form.source : store.form.target
  const error = validateConnection(info)
  if (error) {
    ElMessage.warning(error)
    return
  }
  if (which === 'source') {
    testingSource.value = true
  } else {
    testingTarget.value = true
  }
  try {
    const result = await testAdHocConnection(info)
    if (result.ok) {
      ElMessage.success(`测试成功，延迟 ${result.latencyMs ?? 0} ms`)
      if (which === 'source') {
        store.markSourceTested()
      } else {
        store.markTargetTested()
      }
    } else {
      ElMessage.warning('测试失败')
    }
  } catch (err) {
    ElMessage.error(apiErrorMessage(err))
  } finally {
    if (which === 'source') {
      testingSource.value = false
    } else {
      testingTarget.value = false
    }
  }
}

function validateSync(): string | null {
  if (store.form.databaseServerId == null || store.form.databaseServerId < 1) {
    return '源库为 MySQL 时必须填写 databaseServerId'
  }
  if (store.form.objects.length === 0) {
    return '请至少添加一个 schema'
  }
  for (const row of store.form.objects) {
    if (!row.schema.trim()) {
      return '请填写 schema'
    }
    if (!row.allTables && row.tables.length === 0) {
      return `请为 ${row.schema} 选择表，或开启全部表`
    }
  }
  return null
}

function buildRequest(): CreateMigrationTaskRequest {
  return {
    name: store.form.name.trim(),
    mode: 'FULL_AND_INCREMENTAL',
    source: { ...store.form.source },
    target: { ...store.form.target },
    tables: { objects: store.toObjects() },
    options: {
      batchSize: store.form.batchSize,
      fullDumpParallelism: store.form.fullDumpParallelism,
      databaseServerId: store.form.databaseServerId ?? null,
    },
  }
}

async function refreshCreatedTask(id: number) {
  createdTask.value = await getTask(id)
}

async function pollOnce(id: number) {
  const result = await getPrecheck(id)
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
  }, 1000)
}

async function triggerPrecheck(id: number) {
  startingPrecheck.value = true
  try {
    const immediate = await precheckTask(id)
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

async function onNext() {
  if (store.step === 0) {
    if (!canNextBasic.value) {
      ElMessage.warning('请填写任务名称')
      return
    }
    store.step = 1
    return
  }
  if (store.step === 1) {
    if (!canNextConnections.value) {
      ElMessage.warning('请先成功测试源库和目标库连接')
      return
    }
    store.step = 2
    return
  }
  if (store.step === 2) {
    const error = validateSync()
    if (error) {
      ElMessage.warning(error)
      return
    }
    creating.value = true
    try {
      const created = await createTask(buildRequest())
      store.taskId = created.id
      createdTask.value = created
      store.step = 3
      await triggerPrecheck(created.id)
    } catch (err) {
      ElMessage.error(apiErrorMessage(err))
    } finally {
      creating.value = false
    }
  }
}

function onBack() {
  if (store.step > 0 && store.step < 3) {
    store.step -= 1
  }
}

async function onRecheck() {
  if (store.taskId == null || running.value) {
    return
  }
  await triggerPrecheck(store.taskId)
}

function itemStatus(item: CheckResult): 'pass' | 'fail' {
  return item.ok ? 'pass' : 'fail'
}

function tableLabel(item: CheckResult): string {
  if (!item.table) {
    return ''
  }
  return `${item.table.schema}.${item.table.table}`
}

onMounted(() => {
  void loadSaved()
})

onUnmounted(() => {
  stopPolling()
  store.reset()
})
</script>

<template>
  <div class="wizard">
    <div class="page-toolbar">
      <h2>新建迁移任务</h2>
      <el-button @click="router.push('/tasks')">返回列表</el-button>
    </div>

    <el-steps :active="store.step" finish-status="success" align-center class="steps">
      <el-step v-for="item in steps" :key="item.title" :title="item.title" />
    </el-steps>

    <el-card class="step-card" shadow="never">
      <div v-show="store.step === 0" class="step-pane">
        <el-form label-width="140px" label-position="right">
          <el-form-item label="任务名称" required>
            <el-input v-model="store.form.name" maxlength="128" show-word-limit placeholder="请输入任务名称" />
          </el-form-item>
          <el-form-item label="源数据库引擎" required>
            <div class="engine-grid">
              <button type="button" class="engine-card selected" disabled>MySQL</button>
              <button type="button" class="engine-card disabled" disabled>PostgreSQL</button>
              <button type="button" class="engine-card disabled" disabled>Oracle</button>
            </div>
          </el-form-item>
          <el-form-item label="目标数据库引擎" required>
            <div class="engine-grid">
              <button type="button" class="engine-card disabled" disabled>MySQL</button>
              <button type="button" class="engine-card selected" disabled>PostgreSQL</button>
              <button type="button" class="engine-card disabled" disabled>Oracle</button>
            </div>
            <p class="hint block">v1 仅支持 MySQL → PostgreSQL</p>
          </el-form-item>
          <el-form-item label="同步模式" required>
            <el-radio-group model-value="FULL_AND_INCREMENTAL">
              <el-radio value="FULL_ONLY" disabled>全量</el-radio>
              <el-radio value="INCREMENTAL_ONLY" disabled>增量</el-radio>
              <el-radio value="FULL_AND_INCREMENTAL">全量+增量</el-radio>
            </el-radio-group>
            <p class="hint block">全量、增量模式暂不支持</p>
          </el-form-item>
        </el-form>
      </div>

      <div v-show="store.step === 1" class="step-pane">
        <el-row :gutter="20">
          <el-col :xs="24" :md="12">
            <h3 class="pane-title">源库信息</h3>
            <el-form label-width="110px">
              <el-form-item label="已保存连接">
                <el-select
                  v-model="store.form.sourceSavedId"
                  clearable
                  filterable
                  placeholder="可选，复制字段后请重新输入密码"
                  style="width: 100%"
                  @change="(id: number | null) => applySaved('source', id)"
                >
                  <el-option
                    v-for="item in sourceSaved"
                    :key="item.id"
                    :label="`${item.name} (${item.host}/${item.database})`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <ConnectionFields
                v-model="store.form.source"
                lock-type
                :show-extra="false"
                password-placeholder="已保存连接的密码为 ***，请重新输入"
              />
              <el-form-item>
                <el-button type="primary" :loading="testingSource" @click="testSide('source')">测试连接</el-button>
                <el-tag v-if="sourcePassed" type="success" class="test-tag">已通过测试</el-tag>
              </el-form-item>
            </el-form>
          </el-col>
          <el-col :xs="24" :md="12">
            <h3 class="pane-title">目标库信息</h3>
            <el-form label-width="110px">
              <el-form-item label="已保存连接">
                <el-select
                  v-model="store.form.targetSavedId"
                  clearable
                  filterable
                  placeholder="可选，复制字段后请重新输入密码"
                  style="width: 100%"
                  @change="(id: number | null) => applySaved('target', id)"
                >
                  <el-option
                    v-for="item in targetSaved"
                    :key="item.id"
                    :label="`${item.name} (${item.host}/${item.database})`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <ConnectionFields
                v-model="store.form.target"
                lock-type
                :show-extra="false"
                password-placeholder="已保存连接的密码为 ***，请重新输入"
              />
              <el-form-item>
                <el-button type="primary" :loading="testingTarget" @click="testSide('target')">测试连接</el-button>
                <el-tag v-if="targetPassed" type="success" class="test-tag">已通过测试</el-tag>
              </el-form-item>
            </el-form>
          </el-col>
        </el-row>
      </div>

      <div v-show="store.step === 2" class="step-pane">
        <el-form label-width="150px">
          <div v-for="(object, index) in store.form.objects" :key="index" class="schema-row">
            <el-form-item :label="`Schema ${index + 1}`" required>
              <el-input v-model="object.schema" placeholder="schema" />
            </el-form-item>
            <el-form-item label="全部表">
              <el-switch v-model="object.allTables" />
            </el-form-item>
            <el-form-item v-if="!object.allTables" label="包含表">
              <el-select
                v-model="object.tables"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="输入表名后回车"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="排除表">
              <el-select
                v-model="object.excludeTables"
                multiple
                filterable
                allow-create
                default-first-option
                placeholder="可选"
                style="width: 100%"
              />
            </el-form-item>
            <el-button v-if="store.form.objects.length > 1" type="danger" link @click="store.removeObject(index)">
              删除此 schema
            </el-button>
          </div>
          <el-form-item>
            <el-button @click="store.addObject">添加 schema</el-button>
            <span class="hint">v1 不填写 mappings，目标名与源名相同。</span>
          </el-form-item>
          <el-form-item label="databaseServerId" required>
            <el-input-number v-model="store.form.databaseServerId" :min="1" :controls="false" />
            <span class="hint">源为 MySQL 时必填</span>
          </el-form-item>
          <el-form-item label="batchSize">
            <el-input-number v-model="store.form.batchSize" :min="1" />
          </el-form-item>
          <el-collapse>
            <el-collapse-item title="高级选项" name="advanced">
              <el-form-item label="fullDumpParallelism">
                <el-input-number v-model="store.form.fullDumpParallelism" :min="1" />
              </el-form-item>
            </el-collapse-item>
          </el-collapse>
        </el-form>
      </div>

      <div v-show="store.step === 3" class="step-pane precheck-pane">
        <div class="precheck-header">
          <h3>基本信息</h3>
          <el-button :disabled="running || startingPrecheck" :loading="startingPrecheck" @click="onRecheck">
            重新校验
          </el-button>
        </div>
        <el-descriptions :column="2" border size="small" class="summary">
          <el-descriptions-item label="任务 ID">{{ createdTask?.id ?? store.taskId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="任务名称">{{ createdTask?.name ?? store.form.name }}</el-descriptions-item>
          <el-descriptions-item label="源库">
            {{ createdTask?.source.host }}/{{ createdTask?.source.database }}
          </el-descriptions-item>
          <el-descriptions-item label="目标库">
            {{ createdTask?.target.host }}/{{ createdTask?.target.database }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ createdTask?.createdAt ?? '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="rate-block">
          <div class="rate-label">
            <span>预检查通过率</span>
            <strong>{{ passRate }}%</strong>
          </div>
          <el-progress :percentage="passRate" :status="passed ? 'success' : running ? undefined : finished ? 'exception' : undefined" />
          <p class="tip">项目须全部通过后才会自动启动任务。</p>
        </div>

        <section class="check-group">
          <h4>源库</h4>
          <div v-if="running && sourceResults.length === 0" class="check-row pending">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span class="check-name">源库检查</span>
            <el-tag type="info">正在检查</el-tag>
          </div>
          <div v-for="(item, index) in sourceResults" :key="`s-${index}`" class="check-row">
            <el-icon v-if="itemStatus(item) === 'pass'" class="ok"><CircleCheck /></el-icon>
            <el-icon v-else class="fail"><CircleClose /></el-icon>
            <span class="check-name">{{ item.name }}</span>
            <span v-if="tableLabel(item)" class="check-table">{{ tableLabel(item) }}</span>
            <el-tag :type="item.ok ? 'success' : 'danger'">{{ item.ok ? '通过' : '失败' }}</el-tag>
            <span class="check-msg">{{ item.message }}</span>
          </div>
        </section>

        <section class="check-group">
          <h4>目标库</h4>
          <div v-if="running && targetResults.length === 0" class="check-row pending">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span class="check-name">目标库检查</span>
            <el-tag type="info">正在检查</el-tag>
          </div>
          <div v-for="(item, index) in targetResults" :key="`t-${index}`" class="check-row">
            <el-icon v-if="itemStatus(item) === 'pass'" class="ok"><CircleCheck /></el-icon>
            <el-icon v-else class="fail"><CircleClose /></el-icon>
            <span class="check-name">{{ item.name }}</span>
            <span v-if="tableLabel(item)" class="check-table">{{ tableLabel(item) }}</span>
            <el-tag :type="item.ok ? 'success' : 'danger'">{{ item.ok ? '通过' : '失败' }}</el-tag>
            <span class="check-msg">{{ item.message }}</span>
          </div>
        </section>

        <el-alert
          v-if="passed"
          title="预检查已通过，任务已自动启动"
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
      </div>
    </el-card>

    <div class="wizard-footer">
      <el-button v-if="store.step > 0 && store.step < 3" @click="onBack">上一步</el-button>
      <el-button v-if="store.step < 3" type="primary" :loading="creating" :disabled="store.step === 1 && !canNextConnections" @click="onNext">
        {{ store.step === 2 ? '创建并预检查' : '下一步' }}
      </el-button>
      <el-button v-if="store.step === 3 && store.taskId != null" type="primary" @click="router.push(`/tasks/${store.taskId}`)">
        查看任务
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.steps {
  margin: 8px 0 20px;
}
.step-card {
  border: 1px solid #ebeef5;
}
.step-pane {
  min-height: 280px;
}
.engine-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.engine-card {
  min-width: 120px;
  padding: 16px 20px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #fff;
  font-size: 14px;
  cursor: default;
}
.engine-card.selected {
  border-color: #409eff;
  color: #409eff;
  background: #ecf5ff;
  font-weight: 600;
}
.engine-card.disabled {
  color: #c0c4cc;
  background: #f5f7fa;
  border-color: #e4e7ed;
}
.hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
.hint.block {
  display: block;
  margin: 8px 0 0;
}
.pane-title {
  margin: 0 0 12px;
  font-size: 15px;
}
.test-tag {
  margin-left: 12px;
}
.schema-row {
  margin-bottom: 8px;
  padding: 8px 12px 0;
  background: #fafafa;
  border-radius: 6px;
}
.precheck-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.precheck-header h3 {
  margin: 0;
  font-size: 16px;
}
.summary {
  margin-bottom: 16px;
}
.rate-block {
  margin-bottom: 20px;
}
.rate-label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 14px;
}
.tip {
  margin: 8px 0 0;
  color: #909399;
  font-size: 12px;
}
.check-group {
  margin-bottom: 16px;
  padding: 12px;
  background: #fafafa;
  border-radius: 6px;
}
.check-group h4 {
  margin: 0 0 10px;
  font-size: 14px;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  flex-wrap: wrap;
}
.check-name {
  font-family: Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
}
.check-table {
  color: #909399;
  font-size: 12px;
}
.check-msg {
  color: #606266;
  font-size: 12px;
}
.ok {
  color: #67c23a;
}
.fail {
  color: #f56c6c;
}
.done-alert {
  margin-top: 8px;
}
.wizard-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
