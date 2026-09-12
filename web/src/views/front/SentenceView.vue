<template>
  <FrontLayout>
    <!--
      原 frontend/templates/index/sentence.html 的 body 内容。
      loader / topbar / footer / cd_top 四个 th:replace 片段由 FrontLayout 承载
      （sentence.html 启用了 cd_top，FrontLayout 默认 cdTop = true）。
      标签、class、内联 style、层级与原模板保持完全一致。
    -->
    <div id="Main" class="content maincontain box">
      <div class="showZhiPian" show="0">
        <div class="userInfo box">
        </div>
        <div class="zhipian box">
        </div>
        <i class="close" title="关闭">×</i>
        <div class="action_item box">
          <a href="javascript:void(0)" class="xihuan"><i class="fa fa-heart"></i>&nbsp;喜欢</a>&nbsp;&nbsp;
        </div>
      </div>
      <div class="PageTitle" style="position: relative;">
        <div class="zhilou_title box">
          <span class="en">纸篓</span> <span class="cn">&nbsp;&nbsp;/&nbsp;与过往和解</span>
        </div>

      </div>

      <!-- total='71'、zid='433'、ed="0" 在原模板中即为静态值（非 th:attr），保持一致 -->
      <div class="contentTwo box" total="71">
        <div class="item box" zid="433" ed="0" v-for="(sentence, index) in sentences" :key="sentence.id || index">
          <div class="userInfo box">
            <img :src="avatarUrl(sentence.createImg)" loading="lazy">
            <div class="user_R box">
              <div class="zhipian_name">{{ sentence.createName }}</div>
              <div class="zhipian_time">
                {{ formatYmd(sentence.createTime) }}
              </div>
            </div>
          </div>
          <div class="zhipian box" :content="sentence.content">
            {{ sentence.content }}
          </div>
          <i class="look fa fa-search" title="查看详情"></i>
        </div>

      </div>

      <div class="moreDiv">
        <a class="more" href="javascript:void(0)">NEXT</a>
      </div>

    </div>
  </FrontLayout>
</template>

<script setup>
/**
 * 纸篓 / 与过往和解（移植自 frontend/templates/index/sentence.html）
 *
 * 数据：GET /api/sentence/list（后端 ApiHomeController#sentenceList）
 * 脚本：原模板 body 末尾的 /index/js/zhilou.js（依赖 jQuery，front.html 已全局加载）
 * 样式：zhilou.css（原 head）+ common.css / font-awesome.css（原 topbar 片段）
 */
import { ref, onMounted } from 'vue'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { getSentences } from '@/api/front'
import { avatarUrl } from '@/utils/format'
import { loadScript, loadStyle, whenJQuery, JQUERY_MAIN } from '@/utils/loadScript'
import { useSeo } from '@/utils/seo'

/**
 * SEO：句子页标题沿用原页面意境「琴弦上」，后缀站点名；
 * 描述点明内容形态（短句 / 纸篓），让「句子、文案」类长尾词有命中机会。
 */
useSeo({
  title: '琴弦上 · 遇你',
  description: '琴弦上 · 遇你 —— 纸篓里的小情绪与短句：与过往和解的那些话，一句一句留在这里。',
  keywords: '句子,短句,文案,纸篓,遇你,琴弦上',
  image: '/index/images/banner_1.png',
  type: 'website'
})

const sentences = ref([])

/** 对应原模板 [[ ${#dates.format(sentence.createTime, 'yyyy-M-d')} ]] */
function formatYmd(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

onMounted(async () => {
  // 原页面 <title>纸篓 / 与过往和解</title>；
  // 现由 setup 中的 useSeo 统一设置「琴弦上 · 遇你」（标题只留一处，避免覆盖 SEO 结果）

  // ==== 样式：与原模板声明顺序一致（common.css / font-awesome.css 来自 topbar 片段） ====
  loadStyle('/index/css/common.css')
  loadStyle('/index/css/font-awesome.css')
  loadStyle('/index/css/zhilou.css')

  // ==== 脚本：按原顺序 jquery → zhilou.js（jQuery 已在 front.html 全局加载，缺失时兜底） ====
  try {
    if (!window.jQuery) await loadScript(JQUERY_MAIN)
    await whenJQuery()
    await loadScript('/index/js/zhilou.js')
  } catch (e) {
    console.warn('[SentenceView] 脚本加载失败：', e && e.message)
  }

  // ==== 数据：REST 接口 ====
  try {
    const list = await getSentences()
    sentences.value = Array.isArray(list) ? list : []
  } catch (e) {
    sentences.value = []
    console.warn('[SentenceView] 句子加载失败：', e && e.message)
  }
})
</script>
