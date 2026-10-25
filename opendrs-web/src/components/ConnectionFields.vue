<script setup lang="ts">
import { computed } from 'vue'
import { DEFAULT_PORTS, type ConnectionInfo, type DbType } from '@/types/api'
import { dbLabel } from '@/utils/taskStatus'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    modelValue: ConnectionInfo
    extraText?: string
    passwordPlaceholder?: string
    lockType?: boolean
    showType?: boolean
    showExtra?: boolean
  }>(),
  { extraText: '', passwordPlaceholder: undefined, lockType: false, showType: true, showExtra: true },
)

const emit = defineEmits<{
  'update:modelValue': [value: ConnectionInfo]
  'update:extraText': [value: string]
}>()

const types: DbType[] = ['MYSQL', 'POSTGRESQL', 'ORACLE']

const connection = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
})

function patch(partial: Partial<ConnectionInfo>) {
  emit('update:modelValue', { ...props.modelValue, ...partial })
}

function onTypeChange(type: DbType) {
  const previousDefault = Object.values(DEFAULT_PORTS).includes(props.modelValue.port)
  patch({
    type,
    port: previousDefault ? DEFAULT_PORTS[type] : props.modelValue.port,
  })
}
</script>

<template>
  <el-form-item v-if="showType" :label="t('common.type')" required>
    <el-select
      :model-value="connection.type"
      :disabled="lockType"
      style="width: 100%"
      @change="onTypeChange"
    >
      <el-option v-for="item in types" :key="item" :label="dbLabel(item)" :value="item" />
    </el-select>
  </el-form-item>
  <el-form-item :label="t('common.host')" required>
    <el-input :model-value="connection.host" @update:model-value="patch({ host: $event })" />
  </el-form-item>
  <el-form-item :label="t('common.port')" required>
    <el-input-number
      :model-value="connection.port"
      :min="1"
      :max="65535"
      controls-position="right"
      style="width: 100%"
      @update:model-value="patch({ port: Number($event || 0) })"
    />
  </el-form-item>
  <el-form-item :label="t('common.database')" required>
    <el-input :model-value="connection.database" @update:model-value="patch({ database: $event })" />
  </el-form-item>
  <el-form-item :label="t('common.username')" required>
    <el-input :model-value="connection.username" @update:model-value="patch({ username: $event })" />
  </el-form-item>
  <el-form-item :label="t('common.password')" required>
    <el-input
      :model-value="connection.password"
      type="password"
      show-password
      autocomplete="new-password"
      :placeholder="passwordPlaceholder ?? t('connection.passwordPlaceholder')"
      @update:model-value="patch({ password: $event })"
    />
  </el-form-item>
  <el-form-item v-if="showExtra" :label="t('connection.extraJson')">
    <el-input
      :model-value="extraText"
      type="textarea"
      :rows="4"
      :placeholder="t('connection.extraPlaceholder')"
      @update:model-value="emit('update:extraText', $event)"
    />
  </el-form-item>
</template>
