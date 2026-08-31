<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { deleteTask, listTasks, startAndWaitPrecheck, startTask, stopTask } from '@/api/task'
import { type MigrationPrecheckResponse, type MigrationTaskSummary } from '@/types/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const loading = ref(false)
const rows = ref<MigrationTaskSummary[]>([])
const actionId = ref<number | null>(null)

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
    const result = await startAndWaitPrecheck(row.id)
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
        <el-button type="primary" @click="router.push('/tasks/create')">新建</el-button>
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
  </div>
</template>
