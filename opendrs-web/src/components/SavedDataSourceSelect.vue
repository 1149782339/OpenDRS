<script setup lang="ts">
import type { ConnectionResponse, DbType } from '@/types/api'
import { QuestionFilled } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps<{
  modelValue: number | undefined
  /** 字段标题的 i18n key（如 connection.source），语言切换时自动重译。 */
  titleKey: string
  inputId: string
  /** 该下拉框允许选择的数据库类型（如目标端允许 PostgreSQL 与 MySQL，供双向同步使用）。 */
  databaseTypes: DbType[]
  /** 类型展示文案，如 "MySQL" 或 "PostgreSQL / MySQL"。 */
  databaseLabel: string
  connections: ConnectionResponse[]
  loading: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | undefined]
}>()

const options = computed(() => props.connections.filter((item) => props.databaseTypes.includes(item.type)))
const selected = computed(() => options.value.find((item) => item.id === props.modelValue))
const titleText = computed(() => t(props.titleKey))

function optionLabel(item: ConnectionResponse): string {
  return `${item.name} (${item.host}:${item.port}/${item.database})`
}
</script>

<template>
  <el-form-item :label="titleText" :for="inputId" required>
    <el-select
      :id="inputId"
      :model-value="modelValue"
      :aria-label="titleText"
      filterable
      clearable
      :loading="loading"
      :disabled="loading"
      :placeholder="t('savedDs.placeholder', { title: titleText })"
      :no-data-text="t('savedDs.noData', { label: databaseLabel })"
      :no-match-text="t('savedDs.noMatch')"
      class="data-source-select field"
      @update:model-value="emit('update:modelValue', typeof $event === 'number' ? $event : undefined)"
    >
      <el-option
        v-for="item in options"
        :key="item.id"
        :label="optionLabel(item)"
        :value="item.id"
        class="data-source-option"
      >
        <span class="option-name">{{ item.name }}</span>
        <span class="option-address">{{ item.host }}:{{ item.port }}/{{ item.database }}</span>
      </el-option>
    </el-select>
    <el-tooltip :content="t('savedDs.help', { label: databaseLabel })" placement="top" effect="dark">
      <el-icon class="ops-help" tabindex="0"><QuestionFilled /></el-icon>
    </el-tooltip>
    <!-- 选中后才出现的连接确认：压成一行，不在字段之间开一段灰块 -->
    <dl v-if="selected" class="connection-meta">
      <div><dt>{{ t('common.host') }}</dt><dd>{{ selected.host }}:{{ selected.port }}</dd></div>
      <div><dt>{{ t('common.database') }}</dt><dd>{{ selected.database }}</dd></div>
      <div><dt>{{ t('common.user') }}</dt><dd>{{ selected.username }}</dd></div>
    </dl>
    <p v-else-if="!loading && options.length === 0" class="empty-hint">
      {{ t('savedDs.emptyHint', { label: databaseLabel, page: t('nav.connections') }) }}
    </p>
  </el-form-item>
</template>

<style scoped>
/* 与任务向导的字段列同宽：单行下拉不再按容器拉伸 */
.data-source-select {
  width: var(--ops-field-w, 320px);
  max-width: 100%;
}
.empty-hint {
  width: 100%;
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
.data-source-option {
  height: auto;
  min-height: 56px;
  padding-top: 7px;
  padding-bottom: 7px;
  line-height: 21px;
}
.option-name,
.option-address {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
}
.option-address {
  color: #606266;
  font-size: 12px;
  font-weight: 400;
}
/* 连接确认：单行键值，宽度与控件对齐，超长才换行 */
.connection-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 18px;
  width: var(--ops-field-w, 320px);
  max-width: 100%;
  margin: 6px 0 0;
}
.connection-meta > div {
  display: flex;
  align-items: baseline;
  gap: 5px;
}
.connection-meta dt {
  color: #909399;
  font-size: 12px;
  line-height: 18px;
}
.connection-meta dd {
  margin: 0;
  color: #606266;
  font-size: 12px;
  line-height: 18px;
  word-break: break-all;
}
</style>
