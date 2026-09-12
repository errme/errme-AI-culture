<template>
  <!-- 统一空状态：图标 + 文案 + 可选操作按钮 -->
  <div class="ad-empty">
    <i :class="icon"></i>
    <p class="ad-empty__text">{{ text }}</p>
    <div v-if="$slots.action" class="ad-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  icon: { type: String, default: 'mdi mdi-inbox-outline' },
  text: { type: String, default: '暂无数据' }
})
</script>
