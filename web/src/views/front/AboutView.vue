<template>
  <FrontLayout>
    <!--
      原 frontend/templates/index/about.html 的 body 内容（纯静态）。
      loader / topbar / footer / cd_top 四个 th:replace 片段由 FrontLayout 承载
      （about.html 启用了 cd_top，FrontLayout 默认 cdTop = true）。
      标签、class、内联 style、层级与原模板保持完全一致。
    -->
    <div class="aboutcontent content box">
      <div class="about_word box">
        <!-- 原模板两个 span 之间是「换行 + 缩进」的空白文本节点（浏览器渲染为一个空格），
             Vue 编译器会移除含换行的纯空白节点，故此处写成同行空格，保证渲染结果一致 -->
        <h2 class="about_headline healdine headline_vertical" style="font-weight: 400;">
          <span class="headline_vertical_en">CARE MY HEART</span> <span class="headline_vertical_cn">照顾你的文字</span>
        </h2>
      </div>
      <div style="margin-top: 236px; position:relative;">
        <div style="font-size: 22px;font-weight: bold;text-align: center;margin-bottom: 148px;">
          有一些小情绪，写下来，会消失掉的
        </div>
        <p style="width: 400px;margin-left: 188px;position: relative;">
          <b style="font-size: 134px;font-style: italic;position: absolute;left: -73px;color: #e8e8e8;">
            /
          </b>
          <b>看见文字里那一具血肉之躯。</b>
          我不知道我无数的小情绪，抱怨，挑剔，失落，高兴，会不会正是我热爱生活的缘由。需要经由兜兜转转的故事，让生活的面目更加清明，不断学习，不断矫正，自己也变得愈加清晰，直到喜欢上自己。
          <b style="font-size: 134px;font-style: italic;position: absolute;right: -50px;color: #e8e8e8;">/</b>
        </p>

        <h2 style="margin-bottom: 27px;text-align:center;font-size: 18px;margin-top: 232px;">
          等风也等你
        </h2>

      </div>
    </div>
  </FrontLayout>
</template>

<script setup>
/**
 * 关于我（移植自 frontend/templates/index/about.html）
 *
 * 静态内容，无接口；原模板 head 的 <style> 原样放进 scoped 块（CSS 零改动，且不污染其它路由）。
 * 脚本：about.html 只引入了 jQuery（front.html 已全局加载），这里做一次缺失兜底。
 * 页面 title「关于我 · 遇你」与描述由 setup 中的 useSeo 设置（router meta 只作兜底）。
 */
import { onMounted } from 'vue'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { loadScript, loadStyle, JQUERY_MAIN } from '@/utils/loadScript'
import { useSeo } from '@/utils/seo'

/**
 * SEO：关于我是「站点品牌页」，标题带站点名，描述说明站点与作者定位，
 * 用于品牌词（站名）搜索时展示一段完整的自我介绍。
 */
useSeo({
  title: '关于我 · 遇你',
  description: '关于我 · 遇你 —— 看见文字里那一具血肉之躯。这里记录传统文化、小情绪与生活里值得留下的句子，等风也等你。',
  keywords: '关于我,遇你,传统文化,苍耳,个人站点',
  image: '/index/images/banner_1.png',
  type: 'website'
})

onMounted(async () => {
  // 原 topbar 片段带来的样式（FrontLayout 只负责 DOM，不加载这部分 CSS）
  loadStyle('/index/css/common.css')
  loadStyle('/index/css/font-awesome.css')

  // 原模板 <script th:src="@{/index/lib/jquery-3.4.1/jquery-3.4.1.min.js}">
  if (!window.jQuery) {
    try {
      await loadScript(JQUERY_MAIN)
    } catch (e) {
      console.warn('[AboutView] jQuery 加载失败：', e && e.message)
    }
  }
})
</script>

<style scoped>
/* 原 about.html <head> 内的 <style>，除 Vue 作用域外零改动 */
.headline_vertical_en {
    vertical-align: top;
    -webkit-writing-mode: vertical-rl;
    -ms-writing-mode: tb-rl;
    writing-mode: vertical-rl;
    text-align: left;
    font-size: 1.46667rem;
    font-family: poppin;
}

.headline_vertical_cn {
    font-size: 2.4rem;
    position: relative;
    top: -webkit-calc(((2.4rem * 1.38889) - 2.4rem)/ 2 * -1);
    top: calc(((2.4rem * 1.38889) - 2.4rem)/ 2 * -1);
    vertical-align: top;
    -webkit-writing-mode: vertical-rl;
    -ms-writing-mode: tb-rl;
    writing-mode: vertical-rl;
    text-align: left;
    margin: 5px 5px 0 0;
    line-height: 1;
}

.about_word {
    font-family: AdobeGaramondW01-Regula, GaramondPremrPro, "Ryumin Regular KL", RyuminPro-Regular, "游明朝", "Yu Mincho", "游明朝体", YuMincho, "Hiragino Mincho Pro", HiraMinProN-W3, "MS PMincho", serif;
    text-align: center;
    width: 100px;
    margin: 0px auto;
    background-color: #ffdc5e;
    padding: 55px 0px;
    margin-top: 100px;
}

.aboutcontent {
    margin: 0 auto;
    line-height: 1.8em;
    font-size: 16px;
    color: black;
    width: 800px;
}
</style>
