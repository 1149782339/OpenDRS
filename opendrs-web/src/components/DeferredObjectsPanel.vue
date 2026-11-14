<script setup lang="ts">
import { apiErrorMessage } from '@/api/http'
import { getDeferredObjects, retryDeferredObjects } from '@/api/task'
import type { DeferredObjects } from '@/types/api'
import { DASH } from '@/utils/taskStatus'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ taskId: number }>()
const { t } = useI18n()

const data = ref<DeferredObjects | null>(null)
const loading = ref(false)
const retrying = ref(false)

async function load() {
  loading.value = true
  try {
    data.value = await getDeferredObjects(props.taskId)
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    loading.value = false
  }
}

async function retry() {
  retrying.value = true
  try {
    data.value = await retryDeferredObjects(props.taskId)
    ElMessage.success(t('deferred.retried'))
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    retrying.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div v-loading="loading" class="deferred-panel">
    <p class="intro">{{ t('deferred.intro') }}</p>
    <template v-if="data && data.gate !== 'NONE'">
      <div class="toolbar">
        <el-tag :type="data.gate === 'RESTORED' ? 'success' : data.failed > 0 ? 'danger' : 'warning'">
          {{ t(`deferred.gate.${data.gate}`) }}
        </el-tag>
        <span>{{ t('deferred.summary', { total: data.total, rebuilt: data.rebuilt, failed: data.failed }) }}</span>
        <span class="position">{{ t('deferred.snapshotEnd') }}: {{ data.snapshotEndPosition ?? DASH }}</span>
        <span class="spacer" />
        <el-button size="small" :disabled="data.failed === 0" :loading="retrying" @click="retry">
          {{ t('deferred.retry') }}
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load" />
      </div>
      <el-table :data="data.objects" size="small" stripe>
        <el-table-column :label="t('deferred.columns.table')" min-width="180">
          <template #default="{ row }">{{ row.schema }}.{{ row.table }}</template>
        </el-table-column>
        <el-table-column :label="t('deferred.columns.type')" width="110">
          <template #default="{ row }">{{ t(`deferred.type.${row.objectType}`) }}</template>
        </el-table-column>
        <el-table-column :label="t('deferred.columns.name')" prop="objectName" min-width="180" />
        <el-table-column :label="t('deferred.columns.state')" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.state === 'REBUILT' ? 'success' : row.state === 'FAILED' ? 'danger' : 'info'">
              {{ t(`deferred.state.${row.state}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('deferred.columns.attempts')" prop="attempts" width="90" />
        <el-table-column :label="t('deferred.columns.error')" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastError ?? DASH }}</template>
        </el-table-column>
      </el-table>
    </template>
    <el-empty v-else-if="!loading" :description="t('deferred.none')" />
  </div>
</template>

<style scoped>
.deferred-panel {
  padding: 8px 0;
}
.intro {
  color: var(--el-text-color-secondary);
  margin: 0 0 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.position {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.spacer {
  flex: 1;
}
</style>
