<script setup lang="ts">
import { createDataCheckTask, listDataCheckCandidates, startDataCheckTask } from '@/api/datacheck'
import { apiErrorMessage } from '@/api/http'
import { getTask } from '@/api/task'
import ObjectTransfer from '@/components/ObjectTransfer.vue'
import type { MigrationTaskResponse, SelectedTable } from '@/types/api'
import type { DataCheckMode } from '@/types/datacheck'
import type { SelectionEntry } from '@/types/selection'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const taskId = Number(route.params.id)
const task = ref<MigrationTaskResponse | null>(null)
const loading = ref(false)
const loadError = ref('')

const name = ref(`CHK-${Date.now()}`)
const compareType = ref('CONTENT')
const compareMode = ref<DataCheckMode>('FULL_ONLY')
/** 增量校验走 Kafka 变更流；迁移任务不是 KAFKA 通道时后端启动必失败，前端直接禁选。 */
const incrementalAllowed = computed(() => task.value?.transportChannel !== 'DIRECT')
const entries = ref<SelectionEntry[]>([])
const submitting = ref(false)

/** Candidates are the migration task's expanded source scope, not the whole source instance. */
function loadCandidates() {
  return listDataCheckCandidates(taskId)
}

async function loadTask() {
  loading.value = true
  loadError.value = ''
  try {
    task.value = await getTask(taskId)
  } catch (error) {
    loadError.value = apiErrorMessage(error)
  } finally {
    loading.value = false
  }
}

/** Explicit tables only; a whole-schema entry means its table list is not materialized yet. */
function selectedTables(): SelectedTable[] | null {
  if (entries.value.some((entry) => entry.all)) {
    return null
  }
  const tables: SelectedTable[] = []
  for (const entry of entries.value) {
    for (const table of entry.tables) {
      tables.push({ schema: entry.schema, table })
    }
  }
  return tables
}

async function submit() {
  if (!name.value.trim()) {
    ElMessage.warning(t('datacheck.createPage.needName'))
    return
  }
  const tables = selectedTables()
  if (tables == null) {
    ElMessage.warning(t('datacheck.createPage.needRefresh'))
    return
  }
  if (tables.length === 0) {
    ElMessage.warning(t('datacheck.createPage.needTables'))
    return
  }
  if (compareMode.value !== 'FULL_ONLY' && !incrementalAllowed.value) {
    ElMessage.warning(t('datacheck.createPage.incrementalUnavailable'))
    return
  }
  submitting.value = true
  try {
    const created = await createDataCheckTask({
      migrationTaskId: taskId,
      name: name.value.trim(),
      mode: compareMode.value,
      tables,
      options: { includeFloat: false },
    })
    await startDataCheckTask(created.id)
    ElMessage.success(t('datacheck.createPage.created'))
    await goBack()
  } catch (error) {
    ElMessage.error(apiErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

function goBack() {
  return router.push({
    name: 'task-detail',
    params: { id: taskId },
    query: { tab: 'datacheck' },
  })
}

onMounted(loadTask)
</script>

<template>
  <div class="page">
    <div class="breadcrumb" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>{{ t('datacheck.createPage.back') }}</span>
    </div>

    <div v-loading="loading" class="panel">
      <h2>{{ t('datacheck.createPage.title') }}</h2>

      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        show-icon
        class="load-error"
      />

      <template v-if="task">
        <el-form label-width="110px" label-position="left" class="form">
          <el-form-item :label="t('datacheck.createPage.taskName')" required>
            <el-input
              v-model="name"
              maxlength="128"
              show-word-limit
              class="name-input"
              :placeholder="t('datacheck.createPage.taskNamePlaceholder')"
            />
          </el-form-item>

          <el-form-item :label="t('datacheck.createPage.compareType')" required>
            <el-radio-group v-model="compareType">
              <el-radio-button value="CONTENT">
                {{ t('datacheck.createPage.contentCompare') }}
              </el-radio-button>
              <el-radio-button value="ROW_COUNT" disabled>
                {{ t('datacheck.createPage.rowCountCompare') }}
              </el-radio-button>
            </el-radio-group>
            <span class="hint">{{ t('datacheck.createPage.compareHint') }}</span>
          </el-form-item>

          <el-form-item :label="t('datacheck.createPage.compareMode')" required>
            <el-radio-group v-model="compareMode">
              <el-radio-button value="FULL_ONLY">
                {{ t('datacheck.createPage.modeFull') }}
              </el-radio-button>
              <el-radio-button value="INCREMENTAL_ONLY" :disabled="!incrementalAllowed">
                {{ t('datacheck.createPage.modeIncremental') }}
              </el-radio-button>
              <el-radio-button value="FULL_AND_INCREMENTAL" :disabled="!incrementalAllowed">
                {{ t('datacheck.createPage.modeFullIncremental') }}
              </el-radio-button>
            </el-radio-group>
            <span v-if="!incrementalAllowed" class="hint">
              {{ t('datacheck.createPage.incrementalUnavailable') }}
            </span>
            <span v-else-if="compareMode !== 'FULL_ONLY'" class="hint">
              {{ t('datacheck.createPage.incrementalHint') }}
            </span>
          </el-form-item>

          <el-form-item :label="t('datacheck.createPage.objects')" required>
            <ObjectTransfer
              v-model="entries"
              :connection-id="task.sourceConnectionId"
              :loader="loadCandidates"
              mode="TABLE"
            />
          </el-form-item>
        </el-form>

        <div class="footer">
          <el-button type="danger" :loading="submitting" @click="submit">
            {{ t('datacheck.createPage.submit') }}
          </el-button>
          <el-button @click="goBack">{{ t('datacheck.createPage.cancel') }}</el-button>
        </div>
      </template>

      <el-empty v-else-if="!loading && !loadError" :description="t('detail.notExist')" />
    </div>
  </div>
</template>

<style scoped>
.page {
  padding: 18px 22px;
}

.breadcrumb {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}

.breadcrumb:hover {
  color: var(--el-color-primary);
}

.panel {
  background: #fff;
  border-radius: 8px;
  padding: 20px 24px;
}

.panel h2 {
  margin: 0 0 18px;
  font-size: 18px;
  font-weight: 600;
}

.form {
  max-width: 1040px;
}

.name-input {
  max-width: 420px;
}

.hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.footer {
  margin-top: 18px;
  padding-left: 110px;
}

.load-error {
  margin-bottom: 16px;
}
</style>
