<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { startAndWaitPrecheck, startTask, stopTask } from '@/api/task'
import { useTaskStatusStore } from '@/stores/taskStatus'
import { isActiveJobState, type MigrationPrecheckResponse } from '@/types/api'
import { ElMessage } from 'element-plus'
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const store = useTaskStatusStore()

const taskId = computed(() => Number(route.params.id))
const task = computed(() => store.task)
const status = computed(() => store.status)
const polling = computed(() => isActiveJobState(status.value?.jobState))

function notifyPrecheck(result: MigrationPrecheckResponse) {
  if (result.ok) {
    ElMessage.success(`预检查通过，任务已自动启动（jobState=${result.jobState}）`)
    return
  }
  const failed = result.results?.filter((item) => !item.ok).map((item) => item.message) ?? []
  ElMessage.warning(failed.length > 0 ? `预检查未通过：${failed.join('；')}` : '预检查未通过')
}

async function load() {
  try {
    await store.load(taskId.value)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  }
}

async function onPrecheck() {
  try {
    const result = await startAndWaitPrecheck(taskId.value)
    notifyPrecheck(result)
    await store.load(taskId.value)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  }
}

async function onStart() {
  try {
    const next = await startTask(taskId.value)
    ElMessage.success(`已启动（jobState=${next.jobState}）`)
    await store.load(taskId.value)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  }
}

async function onStop() {
  try {
    const next = await stopTask(taskId.value)
    ElMessage.success(`已请求停止（jobState=${next.jobState}）`)
    await store.load(taskId.value)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  }
}

watch(taskId, () => {
  if (!Number.isNaN(taskId.value)) {
    void load()
  }
})

onMounted(() => {
  void load()
})

onUnmounted(() => {
  store.reset()
})
</script>

<template>
  <div v-loading="store.loading">
    <div class="page-toolbar">
      <h2>任务详情 #{{ taskId }}</h2>
      <div>
        <el-button @click="router.push('/tasks')">返回列表</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="onPrecheck">预检查</el-button>
        <el-button type="success" @click="onStart">启动</el-button>
        <el-button type="warning" @click="onStop">停止</el-button>
      </div>
    </div>

    <el-alert
      v-if="polling"
      title="任务运行中，每 2 秒轮询一次状态"
      type="info"
      :closable="false"
      show-icon
      class="status-alert"
    />

    <el-descriptions v-if="task" title="配置" :column="2" border class="block">
      <el-descriptions-item label="名称">{{ task.name }}</el-descriptions-item>
      <el-descriptions-item label="模式">{{ task.mode }}</el-descriptions-item>
      <el-descriptions-item label="阶段">{{ task.jobPhase }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ task.jobState ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="源">
        {{ task.source.type }} {{ task.source.host }}:{{ task.source.port }}/{{ task.source.database }}
      </el-descriptions-item>
      <el-descriptions-item label="目标">
        {{ task.target.type }} {{ task.target.host }}:{{ task.target.port }}/{{ task.target.database }}
      </el-descriptions-item>
    </el-descriptions>

    <el-descriptions v-if="status" title="运行状态" :column="2" border class="block">
      <el-descriptions-item label="jobPhase">{{ status.jobPhase }}</el-descriptions-item>
      <el-descriptions-item label="jobState">{{ status.jobState ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="tablesTotal">{{ status.progress?.tablesTotal }}</el-descriptions-item>
      <el-descriptions-item label="tablesDone">{{ status.progress?.tablesDone }}</el-descriptions-item>
      <el-descriptions-item label="rowsDone">{{ status.progress?.rowsDone }}</el-descriptions-item>
      <el-descriptions-item label="lagMs">{{ status.progress?.lagMs ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="gtid">{{ status.offset?.gtid ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="scn">{{ status.offset?.scn ?? '—' }}</el-descriptions-item>
      <el-descriptions-item label="error" :span="2">
        <span :class="{ error: status.error }">{{ status.error ?? '—' }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <el-card v-if="task" header="表选择 / 选项" class="block">
      <pre class="json-block">{{ JSON.stringify({ tables: task.tables, options: task.options }, null, 2) }}</pre>
    </el-card>
  </div>
</template>

<style scoped>
.block {
  margin-bottom: 16px;
}
.status-alert {
  margin-bottom: 16px;
}
.error {
  color: #f56c6c;
}
</style>
