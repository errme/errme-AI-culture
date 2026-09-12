<template>
  <div v-if="items.length" class="article-toc" :class="{ 'is-collapsed': collapsed }">
    <!-- 阅读进度条 -->
    <div class="article-toc__progress" :style="{ width: Math.round(progress * 100) + '%' }"></div>

    <div class="article-toc__head" @click="collapsed = !collapsed">
      <span class="article-toc__title">目录</span>
      <span class="article-toc__toggle">{{ collapsed ? '展开' : '收起' }}</span>
    </div>

    <ul v-show="!collapsed" class="article-toc__list">
      <li v-for="item in items" :key="item.id"
          :class="['article-toc__item', 'lv' + item.level, { active: item.id === activeId }]">
        <a :href="'#' + item.id" @click.prevent="jump(item.id)">{{ item.text }}</a>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { scrollToHeading } from '@/utils/toc'

/**
 * 正文目录（吸顶卡片）+ 阅读进度。
 * 样式使用组件内 scoped CSS，不影响站点既有样式。
 */
const props = defineProps({
  items: { type: Array, default: () => [] },
  progress: { type: Number, default: 0 },
  activeId: { type: String, default: '' }
})

const collapsed = ref(false)

function jump(id) {
  scrollToHeading(id, 90)
}
</script>

<style scoped>
.article-toc {
  position: relative;
  margin: 18px 0 24px;
  padding: 12px 14px 6px;
  background: #fff;
  border: 1px solid #eee5da;
  border-radius: 6px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, .04);
}
.article-toc__progress {
  position: absolute;
  top: 0;
  left: 0;
  height: 3px;
  background: linear-gradient(90deg, #d9a06a, #b08968);
  border-radius: 6px 0 0 0;
  transition: width .15s linear;
}
.article-toc__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
}
.article-toc__title {
  font-size: 15px;
  font-weight: 600;
  color: #4a3f35;
  letter-spacing: 2px;
}
.article-toc__toggle {
  font-size: 12px;
  color: #a99a86;
}
.article-toc__list {
  max-height: 46vh;
  margin: 10px 0 6px;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}
.article-toc__item {
  margin: 0;
  padding: 4px 0;
  line-height: 1.6;
}
.article-toc__item a {
  color: #6b5b48;
  font-size: 13px;
  text-decoration: none;
}
.article-toc__item a:hover { color: #b08968; }
.article-toc__item.lv3 { padding-left: 14px; }
.article-toc__item.lv4 { padding-left: 28px; font-size: 12px; }
.article-toc__item.active a { color: #b08968; font-weight: 600; }
.article-toc.is-collapsed { padding-bottom: 10px; }
</style>
