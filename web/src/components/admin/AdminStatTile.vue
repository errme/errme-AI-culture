<template>
  <!-- 概览数字块：label / value / 后缀 / 提示 / 语义色 -->
  <div class="ad-tile">
    <div v-if="icon" class="ad-tile__icon" :class="tone ? 'ad-tile__icon--' + tone : ''">
      <i :class="icon"></i>
    </div>
    <div>
      <span class="ad-tile__label">{{ label }}</span>
      <span class="ad-tile__value">{{ display }}<small v-if="suffix">{{ suffix }}</small></span>
      <div v-if="hint" class="ad-tile__hint">{{ hint }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], default: 0 },
  suffix: { type: String, default: '' },
  hint: { type: String, default: '' },
  icon: { type: String, default: '' },
  /** primary | success | warning | danger */
  tone: { type: String, default: '' }
})

/** 大数字加千分位，可读性更好（与老后台一致：数值型才格式化） */
const display = computed(() => {
  const v = props.value
  if (typeof v === 'number' && isFinite(v)) return v.toLocaleString('zh-CN')
  return v == null ? '0' : String(v)
})
</script>
