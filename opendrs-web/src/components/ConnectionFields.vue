<script setup lang="ts">
import { computed } from 'vue'
import { DEFAULT_PORTS, type ConnectionInfo, type DbType } from '@/types/api'

const props = withDefaults(
  defineProps<{
    modelValue: ConnectionInfo
    extraText?: string
    passwordPlaceholder?: string
    lockType?: boolean
    showExtra?: boolean
  }>(),
  { extraText: '', passwordPlaceholder: '请输入密码', lockType: false, showExtra: true },
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
  <el-form-item label="类型" required>
    <el-select
      :model-value="connection.type"
      :disabled="lockType"
      style="width: 100%"
      @change="onTypeChange"
    >
      <el-option v-for="item in types" :key="item" :label="item" :value="item" />
    </el-select>
  </el-form-item>
  <el-form-item label="主机" required>
    <el-input :model-value="connection.host" @update:model-value="patch({ host: $event })" />
  </el-form-item>
  <el-form-item label="端口" required>
    <el-input-number
      :model-value="connection.port"
      :min="1"
      :max="65535"
      controls-position="right"
      style="width: 100%"
      @update:model-value="patch({ port: Number($event || 0) })"
    />
  </el-form-item>
  <el-form-item label="数据库" required>
    <el-input :model-value="connection.database" @update:model-value="patch({ database: $event })" />
  </el-form-item>
  <el-form-item label="用户名" required>
    <el-input :model-value="connection.username" @update:model-value="patch({ username: $event })" />
  </el-form-item>
  <el-form-item label="密码" required>
    <el-input
      :model-value="connection.password"
      type="password"
      show-password
      autocomplete="new-password"
      :placeholder="passwordPlaceholder"
      @update:model-value="patch({ password: $event })"
    />
  </el-form-item>
  <el-form-item v-if="showExtra" label="额外参数 JSON">
    <el-input
      :model-value="extraText"
      type="textarea"
      :rows="4"
      placeholder='可选，例如 {"useSsl": false, "serverTimezone": "UTC"}'
      @update:model-value="emit('update:extraText', $event)"
    />
  </el-form-item>
</template>
