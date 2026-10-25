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
import { dbLabel } from '@/utils/taskStatus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

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
    throw new Error(t('connection.extraInvalid'))
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
    ElMessage.success(result.ok ? t('connection.testOk', { ms: result.latencyMs ?? 0 }) : t('connection.testFail'))
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
    ElMessage.success(t('connection.createOk'))
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
    ElMessage.success(result.ok ? t('connection.testOk', { ms: result.latencyMs ?? 0 }) : t('connection.testFail'))
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    testingId.value = null
  }
}

async function onDelete(row: ConnectionResponse) {
  try {
    await ElMessageBox.confirm(t('connection.deleteConfirm', { name: row.name }), t('connection.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
    await deleteConnection(row.id)
    ElMessage.success(t('connection.deleted'))
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
      <h2>{{ t('connection.title') }}</h2>
      <div>
        <el-button @click="load">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" @click="openCreate">{{ t('common.create') }}</el-button>
      </div>
    </div>
    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" :label="t('common.id')" width="80" />
      <el-table-column prop="name" :label="t('common.name')" min-width="140" />
      <el-table-column :label="t('common.type')" min-width="110">
        <template #default="{ row }: { row: ConnectionResponse }">{{ dbLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="host" :label="t('common.host')" min-width="140" />
      <el-table-column prop="port" :label="t('common.port')" width="90" />
      <el-table-column prop="database" :label="t('common.database')" min-width="120" />
      <el-table-column prop="username" :label="t('common.username')" min-width="120" />
      <el-table-column :label="t('common.actions')" width="180" fixed="right">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            :loading="testingId === row.id"
            @click="onTestSaved(row)"
          >
            {{ t('connection.test') }}
          </el-button>
          <el-button type="danger" link @click="onDelete(row)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="t('connection.createTitle')" width="560px" destroy-on-close>
      <el-form label-width="110px">
        <el-form-item :label="t('common.name')" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <ConnectionFields v-model="form.connection" v-model:extra-text="form.extraText" />
      </el-form>
      <template #footer>
        <el-button :loading="testingUnsaved" @click="onTestUnsaved">{{ t('connection.testUnsaved') }}</el-button>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="creating" @click="onCreate">{{ t('common.create') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>
