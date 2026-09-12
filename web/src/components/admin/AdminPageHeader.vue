<template>
  <!-- 统一页头：图标 + 标题 + 说明 + 右侧操作区（各后台页面共用，避免每页各写一套 inline style） -->
  <div class="ad-header">
    <div class="ad-header__main">
      <h4 class="ad-header__title">
        <i v-if="icon" :class="icon"></i>{{ title }}
      </h4>
      <p v-if="desc" class="ad-header__desc">{{ desc }}</p>
    </div>
    <div class="ad-header__actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  /** mdi 图标类名，如 'mdi mdi-image-multiple' */
  icon: { type: String, default: '' },
  title: { type: String, required: true },
  /** 一句话说明这个页面是干什么的（降低上手成本） */
  desc: { type: String, default: '' }
})
</script>
