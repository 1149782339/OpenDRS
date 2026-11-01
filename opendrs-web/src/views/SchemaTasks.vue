<script setup lang="ts">
/**
 * 结构迁移任务列表 —— 版式与数据迁移任务列表（views/Tasks.vue）保持一致。
 *
 * 后端目前没有结构迁移任务接口：`migration_tasks` 表没有任务类型字段，结构迁移只是数据迁移
 * 任务里的一个 phase（MigrationPhase.SCHEMA_MIGRATION）。所以这里**不拉数据**，也不复用
 * listTasks()——那样会把数据迁移任务显示成结构迁移任务，页面看着对、内容是错的。
 *
 * 接口就绪后：新增 listSchemaTasks()，把结果填进 rows，并把下面三个操作按钮接上。
 */
import { type MigrationTaskSummary } from '@/types/api'
import { type StatusView } from '@/utils/taskStatus'
import { CircleCheck, CircleClose, Loading } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

interface SchemaTaskRow extends MigrationTaskSummary {
  status: StatusView
  sourceLabel: string
  targetLabel: string
  createdLabel: string
}

/** 接口就绪前恒为空。列定义和样式已按最终形态写好。 */
const rows: SchemaTaskRow[] = []
</script>

<template>
  <div>
    <div class="page-toolbar">
      <h2>{{ t('nav.schemaTasks') }}</h2>
      <div>
        <el-button disabled>{{ t('common.refresh') }}</el-button>
        <el-button type="primary" disabled>{{ t('task.new') }}</el-button>
      </div>
    </div>
    <el-table :data="rows" border stripe row-key="id" class="task-table">
      <template #empty>
        <el-empty :description="t('task.schemaNotReady')" />
      </template>
      <el-table-column :label="t('task.nameId')" min-width="146">
        <template #default="{ row }: { row: SchemaTaskRow }">
          <div class="task-name" :title="row.name">{{ row.name }}</div>
          <div class="task-uuid" :title="row.publicId">{{ row.publicId.slice(0, 19) }}…</div>
        </template>
      </el-table-column>
      <el-table-column :label="t('task.status')" width="108">
        <template #default="{ row }">
          <span class="status" :class="`status-${row.status.tone}`">
            <el-icon v-if="row.status.tone === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="row.status.tone === 'done'"><CircleCheck /></el-icon>
            <el-icon v-else-if="row.status.tone === 'failed'"><CircleClose /></el-icon>
            <span class="status-label" :title="row.status.label">{{ row.status.label }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('task.sourceEngine')" width="104">
        <template #default="{ row }">
          <span class="cell-text" :title="row.sourceLabel">{{ row.sourceLabel }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('task.targetEngine')" width="104">
        <template #default="{ row }">
          <span class="cell-text" :title="row.targetLabel">{{ row.targetLabel }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('task.createdAt')" width="148">
        <template #default="{ row }">
          <span class="cell-text" :title="row.createdLabel">{{ row.createdLabel }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="146" fixed="right">
        <template #default>
          <div class="row-actions" @click.stop>
            <el-button type="success" link disabled>{{ t('task.migrate') }}</el-button>
            <el-button type="warning" link disabled>{{ t('task.terminate') }}</el-button>
            <el-button type="danger" link disabled>{{ t('common.delete') }}</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
/* 与数据迁移列表保持同一内边距，两张表并排看时密度一致。 */
.task-table :deep(.cell) {
  padding: 0 8px;
}

/* 英文文案比中文长，窄列用省略号收住，完整内容看 title。 */
.cell-text {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.status-label {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.task-name {
  font-weight: 500;
  line-height: 20px;
}

.task-uuid {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 18px;
}

.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  white-space: nowrap;
}

.status .el-icon {
  font-size: 15px;
}

.status-running {
  color: #409eff;
}

.status-done {
  color: #67c23a;
}

.status-failed {
  color: #f56c6c;
}

.status-none {
  color: var(--el-text-color-placeholder);
}

.row-actions {
  display: flex;
  align-items: center;
  /* 英文按钮比中文宽，窄屏下换行而不是被裁掉 */
  flex-wrap: wrap;
  gap: 12px;
}

.row-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
