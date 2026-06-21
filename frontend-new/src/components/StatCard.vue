<template>
  <div class="stat-card">
    <div class="stat-value" :style="{ color }">{{ display }}</div>
    <div class="stat-label">{{ label }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: { type: String, default: '' },
  value: { type: [Number, String], default: 0 },
  color: { type: String, default: '#303133' }
})

const display = computed(() => (props.value === null || props.value === undefined ? 0 : props.value))
</script>

<style scoped>
.stat-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 18px 16px;
  text-align: center;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}
.stat-label {
  margin-top: 6px;
  color: #909399;
  font-size: 13px;
}
</style>
