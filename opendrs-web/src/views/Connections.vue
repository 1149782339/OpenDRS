<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import {
  createConnection,
  deleteConnection,
  listConnections,
  testAdHocConnection,
  testSavedConnection,
} from '@/api/connection'
import ConnectionFields from '@/components/ConnectionFields.vue'
import { emptyConnection, type ConnectionInfo, type ConnectionResponse } from '@/types/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

const loading = ref(false)
const rows = ref<ConnectionResponse[]>([])
const dialogVisible = ref(false)
const creating = ref(false)
const testingUnsaved = ref(false)
const testingId = ref<number | null>(null)

const form = reactive({
  name: '',
  connection: emptyConnection(),
  extraText: '',
})

async function load() {
  loading.value = true
  try {
    rows.value = await listConnections()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.name = ''
  form.connection = emptyConnection()
  form.extraText = ''
  dialogVisible.value = true
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

function buildConnection(): ConnectionInfo {
  return {
    ...form.connection,
    extra: parseExtra(form.extraText),
  }
}

async function onTestUnsaved() {
  testingUnsaved.value = true
  try {
    const result = await testAdHocConnection(buildConnection())
    ElMessage.success(result.ok ? `测试成功，延迟 ${result.latencyMs ?? 0} ms` : '测试失败')
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    testingUnsaved.value = false
  }
}

async function onCreate() {
  creating.value = true
  try {
    await createConnection({ name: form.name, connection: buildConnection() })
    ElMessage.success('创建成功')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    creating.value = false
  }
}

async function onTestSaved(row: ConnectionResponse) {
  testingId.value = row.id
  try {
    const result = await testSavedConnection(row.id)
    ElMessage.success(result.ok ? `测试成功，延迟 ${result.latencyMs ?? 0} ms` : '测试失败')
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    testingId.value = null
  }
}

async function onDelete(row: ConnectionResponse) {
  try {
    await ElMessageBox.confirm(`确认删除连接「${row.name}」？`, '删除连接', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteConnection(row.id)
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
      <h2>连接</h2>
      <div>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建</el-button>
      </div>
    </div>
    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="type" label="类型" width="120" />
      <el-table-column prop="host" label="主机" min-width="140" />
      <el-table-column prop="port" label="端口" width="90" />
      <el-table-column prop="database" label="数据库" min-width="120" />
      <el-table-column prop="username" label="用户名" min-width="120" />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            :loading="testingId === row.id"
            @click="onTestSaved(row)"
          >
            测试
          </el-button>
          <el-button type="danger" link @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="新建连接" width="560px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <ConnectionFields v-model="form.connection" v-model:extra-text="form.extraText" />
      </el-form>
      <template #footer>
        <el-button :loading="testingUnsaved" @click="onTestUnsaved">测试未保存</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>
