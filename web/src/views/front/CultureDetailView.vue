<template>
  <!-- detail.html：cd_top 片段在原页面被注释掉，故 cd-top=false -->
  <FrontLayout :cd-top="false">
    <!--
      阅读进度条（阅读体验增强新增，独立容器）：
      固定定位 3px 细条 + 紧随其后的百分比数字；pointer-events:none，纯展示。
      与老主题已有的 #top（fixed 50px）与 .scrollbar（diaspora.js 控制宽度）互不干扰：
      这里既不读取也不修改它们的 DOM / 事件。
    -->
    <div class="read-progress" aria-hidden="true">
      <div class="read-progress__bar" :style="{ width: readPercent + '%' }"></div>
      <span class="read-progress__pct">{{ readPercent }}%</span>
    </div>

    <!--
      detail.html 的 topbar include 上带有 style="margin-top: 30px;margin-bottom: -30px"，
      topbar 本体由 FrontLayout 渲染，这里用外层 div 承载该内联样式（绑定写法与原内联样式等价）。
      · ref="rootRef"：图片灯箱的「事件委托」挂在这一层（正文是 v-html 动态渲染的）
      · --culture-read-font：正文字号 CSS 变量，只被 .content.markdown 消费
    -->
    <div ref="rootRef" :style="{ marginTop: '30px', marginBottom: '-30px', '--culture-read-font': readFontSize + 'px' }">
      <!-- 顶部 loader（loader 片段已由 FrontLayout 渲染一次，此处保留 detail.html 中 topbar 之后的那一个） -->
      <div id="loader"></div>

      <div id="single" v-if="culture">
        <div id="top" style="display: block;">
          <div class="bar" style="width: 0;"></div>
          <a class="icon-home image-icon" href="javascript:;" data-url="/"></a>
          <!-- <div title="播放/暂停" class="icon-play"></div> -->
          <h3 class="subtitle">{{ culture.cultureName }}</h3>
          <div class="social">
            <!--<div class="like-icon">-->
            <!--<a href="javascript:;" class="likeThis active"><span class="icon-like"></span><span class="count">76</span></a>-->
            <!--</div>-->
            <div>
              <div class="share">
                <img src="/index/images/9.gif" class="hsx" alt="">
                <!-- <a title="获取二维码" class="icon-scan" href="javascript:;"></a> -->
              </div>
              <div id="qr"></div>
            </div>
          </div>
          <div class="scrollbar"></div>
        </div>
        <div class="section">
          <div class="article">
            <div class='main'>
              <h1 class="title">{{ culture.cultureName }}</h1>
              <!--
                正文字号切换（阅读体验增强新增，独立容器，位于正文标题区右侧）：
                三档 15 / 17 / 19px，只改 .content.markdown 的 font-size（CSS 变量），
                不触碰全站样式；选择写入 localStorage(key=culture_read_font)，刷新后保持。
              -->
              <div class="read-tools" role="group" aria-label="正文字号">
                <span class="read-tools__label">字号</span>
                <button v-for="(size, i) in READ_FONT_SIZES" :key="size" type="button"
                        class="read-tools__btn" :class="{ 'is-active': fontLevel === i }"
                        :aria-pressed="fontLevel === i ? 'true' : 'false'"
                        :title="'正文字号 ' + size + 'px'"
                        @click="setFontLevel(i)">{{ i === 0 ? 'A-' : (i === 1 ? 'A' : 'A+') }}</button>
              </div>
              <!-- 原模板三个 span 之间是「换行 + 缩进」的空白文本节点（浏览器渲染为一个空格），
                   Vue 编译器会移除含换行的纯空白节点，故写成同行空格，保证渲染结果一致 -->
              <div class="stuff">
                <span>地址 {{ culture.address }}</span> <span>浏览 {{ culture.view }}</span> <span>时间 {{ formatYmd(culture.createTime) }}</span>
              </div>
              <div class="content markdown">

                <img :src="coverUrl(culture.fmUrl)" class="cover posttitle"/>

                <br><br>

                <p v-html="culture.desc"></p>

                <!-- 内容标签（Batch4）：点击进入标签聚合页 -->
                <div v-if="tags.length" class="culture-tags">
                  <span class="culture-tags__label">标签：</span>
                  <a v-for="t in tags" :key="t.id" class="culture-tags__item" :href="`/tag/${t.id}`">{{ t.name }}</a>
                </div>

                <!-- 正文目录 + 阅读进度（Batch3：由 collectHeadings 在渲染后扫描标题生成） -->
                <ArticleToc :items="tocItems" :progress="readingProgress" :active-id="activeHeading" />

                <p v-html="culture.info"></p>

                <!--                    <button class="navbar-btn nav-button wow bounceInRight login"-->
                <!--                            th:onclick="sc([[${culture.id}]])">收藏文创-->
                <!--                    </button>-->

                <!--
                <audio id="audio" loop="1" preload="auto" controls="controls" data-autoplay="true">

                    <source type="audio/mpeg" src="https://link.hhtjim.com/163/33911781.mp3">
                </audio> -->

              </div>

              <div class="comment link" @click="scCulture">收藏</div>

              <!--
                分享卡片（阅读体验增强新增，独立容器，位于正文底部）：
                复制链接 / 微博 / QQ；微博与 QQ 用 location.href 拼接官方分享地址，新窗口打开。
              -->
              <div class="read-share">
                <span class="read-share__label">分享：</span>
                <button type="button" class="read-share__btn" @click="copyLink">复制链接</button>
                <button type="button" class="read-share__btn" @click="shareTo('weibo')">微博</button>
                <button type="button" class="read-share__btn" @click="shareTo('qq')">QQ</button>
                <span v-if="copyTip" class="read-share__tip" role="status">{{ copyTip }}</span>
              </div>

            </div>

          </div>
        </div>
      </div>
      <!-- property area end -->

      <!-- 留言区（Batch4：列表只展示审核通过的评论，提交后待审） -->
      <section class="container" style="padding: 10px 15px 40px;">
        <div class="row">
          <div class="col-md-10 col-md-offset-1 col-sm-12">
            <CommentSection :culture-id="id" />
          </div>
        </div>
      </section>
      <div class="content-area home-area-1 recent-property" style="background-color: #FCFCFC; padding-bottom: 55px;">
        <div class="container">
          <div class="row">
            <div class="col-md-10 col-md-offset-1 col-sm-12 text-center page-title" style="text-align: center">
              <!-- /.feature title -->
              <h2>猜你喜欢</h2>
              <p>系统智能算法推荐,找到最匹配您的爱好 . </p>
            </div>
          </div>

          <div class="row">

            <!-- 数据start-->
            <div class="proerty-th" v-for="item in recommendCultures" :key="item.id"
                 style="text-align: center;margin: 0 auto">
              <div class="col-sm-6 col-md-3 p0">
                <div class="box-two proerty-item">
                  <div class="item-thumb">
                    <a :href="`/culture/${item.id}`"><img :src="coverUrl(item.fmUrl)"
                                                          style="height: 150px;width: 222px;margin: 0 auto"/></a>
                  </div>
                  <div class="item-entry overflow">
                    <!-- 原模板 <span> 与 </a> 之间有一个空格文本节点（Vue 会移除元素首尾的纯空白节点），用插值保留 -->
                    <h5><a :href="`/culture/${item.id}`"><span>{{ item.cultureName }}</span>{{ ' ' }}</a></h5>
                    <div class="dot-hr"></div>
                  </div>
                </div>
              </div>
            </div>
            <!-- 数据end-->

          </div>


        </div>
      </div>
    </div>

      <!--
        图片灯箱（阅读体验增强新增，独立容器）：
        点击遮罩空白处关闭；Esc 关闭；关闭时恢复 body 的 overflow，不会把页面锁死。
        只由 rootRef 上的事件委托触发，且只对正文容器 .content.markdown 内的 img 生效。
      -->
      <div v-if="lightboxSrc" class="read-lightbox" @click.self="closeLightbox">
        <button type="button" class="read-lightbox__close" aria-label="关闭图片预览" @click="closeLightbox">×</button>
        <img class="read-lightbox__img" :src="lightboxSrc" :alt="lightboxAlt" @click.stop>
        <p class="read-lightbox__hint" @click.stop>Esc 或点击遮罩关闭</p>
      </div>
    </FrontLayout>
</template>

<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import FrontLayout from '@/layouts/FrontLayout.vue'
import ArticleToc from '@/components/front/ArticleToc.vue'
import CommentSection from '@/components/front/CommentSection.vue'
import { getCategorys, getCultureDetail, getCultureList, likeCulture, cancelLike } from '@/api/front'
import { coverUrl, pickPage } from '@/utils/format'
import { loadScripts, loadStyle, whenJQuery } from '@/utils/loadScript'
import { useFrontUserStore } from '@/stores/frontUser'
import { absoluteUrl, truncate, useSeo } from '@/utils/seo'
import { collectHeadings, trackReading } from '@/utils/toc'
import { toast as dsToast } from '@/utils/notify'

/**
 * 文化详情页（frontend/templates/index/detail.html 的 1:1 移植）
 * 数据：GET /api/culture/detail?id=  -> { culture }
 * 交互：收藏/取消收藏走前台 Token 的 POST /api/culture/like|cancel/{id}
 */
const route = useRoute()

/** 当前文化 id（模板中的 CommentSection 与标题都依赖它） */
const id = computed(() => route.params.id)
const user = useFrontUserStore()

const culture = ref(null)
const recommendCultures = ref([])
/** 内容标签（Batch4） */
const tags = ref([])
/** 正文目录与阅读进度（Batch3） */
const tocItems = ref([])
const readingProgress = ref(0)
const activeHeading = ref('')
let readingTracker = null
const loadError = ref('')
/** 本地收藏态：用于 like / cancel 切换（后端在「已收藏」时会返回 400 提示） */
const liked = ref(false)
/** 组件是否已卸载：避免卸载后仍更新状态 / 弹窗 */
let disposed = false
/** 当前打开的 jquery-confirm 实例，卸载时关闭 */
let activeDialog = null

/* ===================== 阅读体验增强（新增状态，全部与老主题逻辑解耦） ===================== */

/** 正文字号三档（px）。默认取中间档，可用 localStorage 覆盖 */
const READ_FONT_SIZES = [15, 17, 19]
const READ_FONT_KEY = 'culture_read_font'
const DEFAULT_FONT_LEVEL = 1
/** 字号档位下标；readFontSize 作为 CSS 变量 --culture-read-font 传给 .content.markdown */
const fontLevel = ref(DEFAULT_FONT_LEVEL)
const readFontSize = computed(() => READ_FONT_SIZES[fontLevel.value] || READ_FONT_SIZES[DEFAULT_FONT_LEVEL])
/** 顶部固定进度条百分比（0~100 的整数） */
const readPercent = ref(0)
/** 分类名：详情接口的 culture.category 常为 null，用 getCategorys() 反查，供面包屑使用 */
const categoryName = ref('')
/** 图片灯箱 */
const lightboxSrc = ref('')
const lightboxAlt = ref('')
/** 复制链接的轻提示文案（2 秒后自动消失） */
const copyTip = ref('')
/** 外层容器 ref：图片灯箱的事件委托挂在这里（正文是 v-html 动态渲染的） */
const rootRef = ref(null)
/** 阅读进度用的 rAF 节流标记 + 卸载清理用的定时器 */
let progressTicking = false
let copyTipTimer = null
/** 全局滚动 / 键盘监听是否已绑定（同一实例内只绑一次） */
let readListenersBound = false
/** 打开灯箱前的 body overflow，关闭时原样恢复（避免把页面滚动锁死） */
let bodyOverflowBeforeLightbox = null

/** 去掉描述里的 HTML 标签：meta description 是纯文本，接口的 desc 可能带 <p> 等富文本标签 */
function stripHtml(value) {
  if (!value) return ''
  return String(value).replace(/<[^>]*>/g, ' ')
}

/** yyyy-MM-dd（JSON-LD 的 datePublished 用 ISO 8601 日期，取接口真实的 createTime 字段） */
function formatIsoDate(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/**
 * SEO（详情页是站内最需要收录的页面）：
 *   - 标题用接口真实字段 culture.cultureName，加站点后缀形成「内容名 · 站名」
 *   - 描述取 culture.desc（无 desc 时退回正文 info），去标签后截断 120 字，正好是搜索结果摘要长度
 *   - og:image 用接口封面 coverUrl(fmUrl)，分享到社交平台时有图
 *   - JSON-LD 注入 Article 结构化数据（headline/description/image/datePublished），争取富摘要
 * 传入 getter：接口数据回来后（含 SPA 内切换 id）会自动重新写入 head。
 */
useSeo(() => {
  const c = culture.value
  // 数据未返回时的兜底：先给一个可收录的通用标题，避免出现空 <title>
  if (!c) {
    return {
      title: '文化详情 · 遇你',
      description: '遇你 · 传统文化 —— 文化详情页：图文介绍、地址与浏览热度，带你认识一处传统文化。',
      keywords: '传统文化,文化详情,遇你',
      type: 'article'
    }
  }
  const description = truncate(stripHtml(c.desc || c.info), 120)
  const image = coverUrl(c.fmUrl)
  return {
    title: c.cultureName + ' · 遇你',
    description,
    // 关键词：内容名 + 分类名 + 站点通用词，覆盖长尾搜索
    keywords: [c.cultureName, c.category && c.category.categoryName, '传统文化', '遇你']
      .filter(Boolean)
      .join(','),
    image,
    type: 'article',
    // JSON-LD：用一个 @graph 装 Article + BreadcrumbList。
    // seo.js 的 setJsonLd 只做 JSON.stringify 后写入单个 <script id="page-jsonld">，
    // 因此顶层必须是「一个对象」；@graph 是它唯一能正确渲染的多节点形式（顶层数组也可被
    // JSON.stringify，但部分校验工具对裸数组支持不佳，这里选 @graph，最稳）。
    jsonLd: {
      '@context': 'https://schema.org',
      '@graph': [
        {
          '@type': 'Article',
          headline: c.cultureName,
          description,
          // JSON-LD 里的图片必须是绝对地址（浏览器里由 seo.js 按当前站点拼全）
          image: absoluteUrl(image),
          // 发布/创建时间：接口返回的 createTime（yyyy-MM-dd）
          datePublished: formatIsoDate(c.createTime)
        },
        // 面包屑：首页 → 文化列表 → 当前分类（接口有分类时） → 当前内容
        {
          '@type': 'BreadcrumbList',
          itemListElement: breadcrumbItems(c)
        }
      ]
    }
  }
})

/**
 * 面包屑层级（JSON-LD BreadcrumbList 的 itemListElement）。
 *   · 首页       /                        名称「首页」
 *   · 文化列表   /culture                 名称「文化列表」（前台实际路由，见 router/front.js）
 *   · 当前分类   /culture?categoryId=xx   仅当能拿到分类名时输出；
 *                详情接口 /api/culture/detail 的 culture.category 多为 null，
 *                此时用 getCategorys() 拿到的分类表按 culture.categoryId 反查名称（见 resolveCategoryName）
 *   · 当前内容   /culture/{id}
 * 所有 item 用绝对地址（schema.org 建议），position 从 1 递增。
 */
function breadcrumbItems(c) {
  const items = [
    { '@type': 'ListItem', position: 1, name: '首页', item: absoluteUrl('/') },
    { '@type': 'ListItem', position: 2, name: '文化列表', item: absoluteUrl('/culture') }
  ]
  // 注意：这里不要再用 categoryName 作局部变量名（会和上面的 ref 同名，触发 TDZ 报错）
  const catName = (c.category && c.category.categoryName) || categoryName.value
  const categoryId = (c.category && c.category.id) || c.categoryId
  if (catName && categoryId) {
    items.push({
      '@type': 'ListItem',
      position: items.length + 1,
      name: catName,
      // 前台没有独立的分类页路由，分类筛选在文化列表页用 query 承载（/culture?categoryId=xx）
      item: absoluteUrl('/culture?categoryId=' + categoryId)
    })
  }
  items.push({
    '@type': 'ListItem',
    position: items.length + 1,
    name: c.cultureName,
    item: absoluteUrl('/culture/' + c.id)
  })
  return items
}

/** yyyy-M-d（对应原页面 #dates.format(culture.createTime, 'yyyy-M-d')） */
function formatYmd(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/** 等待 jQuery 可用（老插件与 jquery-confirm 都依赖全局 jQuery） */
function getJQuery() {
  if (window.jQuery) return Promise.resolve(window.jQuery)
  return whenJQuery(3000).catch(() => null)
}

/** 统一提示（顶部轻提示；原 jquery-confirm 已由 utils/notify 取代） */
function openConfirm(options) {
  const content = options && options.content ? String(options.content) : ''
  const tone = options && (options.type === 'red' ? 'error' : options.type === 'orange' ? 'warning' : 'success')
  dsToast[tone](content)
  return null
}

/** 收藏成功提示（与原页面 scCulture 成功分支完全一致） */
function successDialog(content) {
  return openConfirm({
    title: '温馨提示',
    content: content,
    type: 'green',
    buttons: {
      omg: {
        text: '谢谢',
        btnClass: 'btn-green'
      }
    }
  })
}

/** 失败提示（与原页面 scCulture 失败分支完全一致） */
function errorDialog(content) {
  dsToast.error(content)
}

/** 详情 + 推荐位（REST） */
async function loadDetail() {
  const id = route.params.id
  try {
    const data = await getCultureDetail(id)
    if (disposed) return
    culture.value = (data && data.culture) || null
    // 标题/描述/canonical/JSON-LD 由 setup 中的 useSeo 统一下发（依赖 culture.value 自动更新）

    let list = data && data.recommendCultures
    if (!Array.isArray(list)) {
      // 当前后端 /api/culture/detail 仅返回 { culture }（原推荐位需要登录 Session），
      // 这里退回文化列表作为「猜你喜欢」数据源，展示位与 detail.html 完全一致。
      const page = await getCultureList({ page: 1, pageSize: 4 })
      list = pickPage(page).rows
    }
    recommendCultures.value = (list || []).filter(item => String(item.id) !== String(id))
    tags.value = (data && data.tags) || []
  } catch (e) {
    if (!disposed) loadError.value = (e && e.message) || '内容不存在'
  }
}

/** 收藏 / 取消收藏（未登录跳转前台登录页，并带上回跳地址） */
async function scCulture() {
  if (!culture.value) return
  if (!user.isLogin) {
    // 前台登录页属于独立入口（auth.html），必须整页跳转
    window.location.href = '/auth/login?redirect=' + encodeURIComponent(route.fullPath)
    return
  }
  const id = culture.value.id
  try {
    if (liked.value) {
      await cancelLike(id)
      if (disposed) return
      liked.value = false
      successDialog('已取消收藏')
    } else {
      await likeCulture(id)
      if (disposed) return
      liked.value = true
      successDialog('恭喜 收藏成功')
    }
  } catch (e) {
    if (disposed) return
    // 与原页面一致：已收藏时后端返回该提示；同时纠正本地状态，便于再次点击取消
    if (/已经收藏/.test((e && e.message) || '')) liked.value = true
    errorDialog((e && e.message) || '收藏失败')
  }
}

/** 与原 diaspora.js 一致：#single 高度跟随视口（其只在脚本加载时设置一次，这里跟随 resize） */
function syncSingleHeight() {
  const el = document.getElementById('single')
  if (el) el.style.minHeight = window.innerHeight + 'px'
}

/* =====================================================================================
 * 阅读体验增强（Batch5）
 * 全部以「独立容器 + scoped 样式 + 事件委托」实现，不修改 #single / #top / .content 的
 * 既有 id、class 与 DOM 结构，也不改动 diaspora.js 的初始化逻辑。
 * ===================================================================================== */

/** 正文容器（.content.markdown 是 v-html 正文所在元素；.stuff 只是地址/浏览/时间） */
function contentRoot() {
  const root = rootRef.value || document
  return root.querySelector('.content.markdown') || root.querySelector('.content')
}

/* ---------- 1) 阅读进度条 ---------- */

/** 计算滚动进度（0~100），scroll 事件里经 requestAnimationFrame 节流后调用 */
function computeReadProgress() {
  progressTicking = false
  if (disposed) return
  const doc = document.documentElement
  const total = doc.scrollHeight - window.innerHeight
  const top = window.pageYOffset || doc.scrollTop || 0
  const percent = total > 0 ? Math.round(Math.min(1, Math.max(0, top / total)) * 100) : (top > 0 ? 100 : 0)
  if (percent !== readPercent.value) readPercent.value = percent
}

/** scroll / resize 共用的 rAF 节流入口（passive 监听，不阻塞老主题自己的滚动逻辑） */
function onReadScroll() {
  if (progressTicking) return
  progressTicking = true
  window.requestAnimationFrame(computeReadProgress)
}

/* ---------- 2) 正文字号切换 ---------- */

/** 读取 localStorage 里的字号档位（隐私模式 / 存储被禁时静默退回默认档） */
function initReadFont() {
  let saved = null
  try {
    saved = window.localStorage.getItem(READ_FONT_KEY)
  } catch (e) {
    saved = null
  }
  const idx = READ_FONT_SIZES.indexOf(Number(saved))
  fontLevel.value = idx >= 0 ? idx : DEFAULT_FONT_LEVEL
}

/** 切换字号：只改 --culture-read-font（由 .content.markdown 消费），并持久化到 localStorage */
function setFontLevel(index) {
  if (index < 0 || index >= READ_FONT_SIZES.length) return
  fontLevel.value = index
  try {
    window.localStorage.setItem(READ_FONT_KEY, String(READ_FONT_SIZES[index]))
  } catch (e) {
    // 存储不可用（隐私模式 / 配额满）：只影响「刷新后保持」，不影响本次切换
  }
}

/* ---------- 3) 图片灯箱（事件委托） ---------- */

/**
 * 委托点击：只有落在正文容器 .content.markdown 内的 <img> 才打开灯箱。
 * 正文由 v-html 动态渲染，元素会随路由切换整体重建，所以在稳定的外层容器上委托一次即可。
 */
function onRootClick(event) {
  const target = event.target
  if (!target || target.tagName !== 'IMG') return
  const content = contentRoot()
  if (!content || !content.contains(target)) return
  const src = target.getAttribute('src')
  if (!src) return
  openLightbox(src, target.getAttribute('alt') || culture.value && culture.value.cultureName || '')
}

function openLightbox(src, alt) {
  lightboxSrc.value = src
  lightboxAlt.value = alt || ''
  // 锁住背景滚动，并记下原值（关闭时原样恢复，避免把 body 永久锁死）
  if (bodyOverflowBeforeLightbox === null) bodyOverflowBeforeLightbox = document.body.style.overflow || ''
  document.body.style.overflow = 'hidden'
}

function closeLightbox() {
  if (!lightboxSrc.value) return
  lightboxSrc.value = ''
  lightboxAlt.value = ''
  if (bodyOverflowBeforeLightbox !== null) {
    document.body.style.overflow = bodyOverflowBeforeLightbox
    bodyOverflowBeforeLightbox = null
  }
}

/** Esc 关闭灯箱（未打开时不做任何事，不影响页面其它键盘交互） */
function onReadKeydown(event) {
  if (event.key === 'Escape' && lightboxSrc.value) closeLightbox()
}

/* ---------- 4) 分享卡片 ---------- */

function showCopyTip(text) {
  copyTip.value = text
  if (copyTipTimer) clearTimeout(copyTipTimer)
  copyTipTimer = window.setTimeout(() => {
    copyTipTimer = null
    if (!disposed) copyTip.value = ''
  }, 2200)
}

/** 复制链接：优先 navigator.clipboard，失败回退 execCommand('copy')，再失败提示手动复制 */
async function copyLink() {
  // 分享的是「当前详情页地址」，去掉可能存在的 hash（锚点不属于页面本身）
  const url = window.location.href.split('#')[0]
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(url)
      if (disposed) return
      showCopyTip('链接已复制')
      return
    }
    throw new Error('clipboard unavailable')
  } catch (e) {
    // 回退方案：临时 textarea + document.execCommand('copy')（http 站点 / 旧浏览器）
    try {
      const ta = document.createElement('textarea')
      ta.value = url
      ta.setAttribute('readonly', 'readonly')
      ta.style.position = 'fixed'
      ta.style.top = '-1000px'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand && document.execCommand('copy')
      document.body.removeChild(ta)
      if (disposed) return
      if (ok) { showCopyTip('链接已复制'); return }
      throw new Error('execCommand failed')
    } catch (e2) {
      if (disposed) return
      console.warn('[CultureDetailView] 复制链接失败（已降级为提示手动复制）：', e2 && e2.message)
      showCopyTip('复制失败，请手动复制地址栏链接')
      dsToast.warning('复制失败，请手动复制地址栏链接')
    }
  }
}

/** 微博 / QQ 分享：用当前页面地址拼官方分享 URL，新窗口打开 */
function shareTo(kind) {
  try {
    const url = window.location.href.split('#')[0]
    const title = (culture.value && culture.value.cultureName) || document.title || ''
    const share = {
      weibo: 'https://service.weibo.com/share/share.php?url=' + encodeURIComponent(url) +
        '&title=' + encodeURIComponent(title),
      qq: 'https://connect.qq.com/widget/shareqq/index.html?url=' + encodeURIComponent(url) +
        '&title=' + encodeURIComponent(title) + '&summary=' + encodeURIComponent(title)
    }[kind]
    if (!share) return
    window.open(share, '_blank', 'noopener,noreferrer')
  } catch (e) {
    console.warn('[CultureDetailView] 分享失败（已忽略）：', e && e.message)
  }
}

/* ---------- 5) 分类名（面包屑用） ---------- */

/** 模块级缓存：分类表很小，同一会话内不重复请求 */
let categoryListCache = null

/**
 * 面包屑里的分类名。详情接口的 culture.category 在多数情况下是 null（SQL 未 join 分类表），
 * 只有 cultureId 时有 categoryId，故这里按需拉一次 /api/culture/categorys 反查名称；
 * 任何失败都只降级为「不显示分类层」，不影响页面。
 */
async function loadCategoryName() {
  const c = culture.value
  categoryName.value = (c && c.category && c.category.categoryName) || ''
  if (!c || categoryName.value || !c.categoryId) return
  try {
    if (!categoryListCache) categoryListCache = await getCategorys()
    if (disposed) return
    const hit = (categoryListCache || []).find(item => String(item.id) === String(c.categoryId))
    if (!disposed) categoryName.value = hit ? hit.categoryName : ''
  } catch (e) {
    if (!disposed) categoryName.value = ''
    console.warn('[CultureDetailView] 分类名加载失败（面包屑将省略分类层）：', e && e.message)
  }
}

/* ---------- 增强功能的启动 / 清理 ---------- */

/** 数据渲染完成后绑定滚动监听、字号、灯箱委托（每次 id 变化后重跑） */
function setupReading() {
  computeReadProgress()
  // 全局监听只绑一次（同一组件实例内 id 变化会重复调用本函数）
  if (!readListenersBound) {
    readListenersBound = true
    window.addEventListener('scroll', onReadScroll, { passive: true })
    window.addEventListener('resize', onReadScroll)
    window.addEventListener('keydown', onReadKeydown)
  }
  // 事件委托只绑一次（rootRef 在整个组件生命周期内都是同一个 DOM）
  if (rootRef.value && !rootRef.value.dataset.readDelegated) {
    rootRef.value.dataset.readDelegated = '1'
    rootRef.value.addEventListener('click', onRootClick)
  }
}

/** 卸载时清理增强功能（滚动 / 键盘 / 灯箱 / 定时器），老主题的监听器由既有逻辑清理 */
function teardownReading() {
  if (readListenersBound) {
    readListenersBound = false
    window.removeEventListener('scroll', onReadScroll)
    window.removeEventListener('resize', onReadScroll)
    window.removeEventListener('keydown', onReadKeydown)
  }
  if (rootRef.value) rootRef.value.removeEventListener('click', onRootClick)
  if (copyTipTimer) { clearTimeout(copyTipTimer); copyTipTimer = null }
  closeLightbox()
}

onMounted(async () => {
  await nextTick()

  // 1) 先取数据并渲染出 DOM，再执行依赖 DOM 的老插件（detail.html 的脚本同样在 DOM 之后生效）
  await loadDetail()
  await nextTick()

  // 2) 原 detail.html <head> 中的样式（bootstrap / loader 已由 front.html 与 FrontLayout 引入）
  loadStyle('/index/css/common.css')
  loadStyle('/index/css/font-awesome.css')
  loadStyle('/index/css/diaspora.css')
  loadStyle('/index/css/default-skin.css')

  // 3) 原 detail.html 的脚本，按原顺序加载（jQuery -> jquery-confirm -> diaspora.js）
  try {
    await loadScripts([
      '/index/js/jquery-1.11.1.min.js',
      '/index/js/diaspora.js'
    ])
  } catch (e) {
    /* 老脚本加载失败时，页面主体与收藏交互（原生 alert 兜底）仍可用 */
  }
  if (disposed) return

  // 4) 初始化交互：jquery-confirm 已就绪，详情/推荐位由 diaspora.js 接管滚动、二维码等行为
  syncSingleHeight()
  window.addEventListener('resize', syncSingleHeight)
  setupToc()
  // 5) 阅读体验增强：字号（localStorage 恢复）、进度条、灯箱委托、面包屑分类名
  initReadFont()
  setupReading()
  loadCategoryName()
  if (loadError.value) errorDialog(loadError.value)
})

/**
 * 构建正文目录并开启阅读进度（Batch3 + Batch5）。
 * 注意：在数据渲染完成后扫描真实 DOM，只补标题 id、不改动正文 HTML。
 *
 * 可用性说明（Batch5 复核 + 无头 Chrome 实测）：
 *   · collectHeadings 会给没有 id 的 h2~h4 生成稳定 id（toc-<slug>-<index>）并写入 DOM，
 *     ArticleToc 的目录项用 scrollToHeading(id, 90) 平滑跳转，90px 已避开老主题
 *     固定在顶部的 #top（50px）与新增的 3px 进度条（实测落点 rect.top≈90px）；
 *   · 已知限制（属老主题既有行为，不在本文件可修范围）：FrontLayout 的 .topbar 在「首次滚动」
 *     时会从文档流切成 position:fixed（高度 164px，文档瞬间变短），若用户在页面还没滚动过时
 *     直接点目录，跳转会被这次布局位移顶偏约 164px；滚动过一次后再点即正常（实测 top≈90px）。
 *     .topbar 由 FrontLayout 渲染，本页无法在不改其它文件的前提下修正，故在此记录。
 *   · 正文里若没有标题（当前示例内容就是纯文本段落），目录整体隐藏，此时顶部进度条
 *     仍由 setupReading 独立工作。
 */
function setupToc() {
  if (readingTracker) { readingTracker.destroy(); readingTracker = null }
  // 正文容器是 .content（.stuff 只是「地址/浏览/时间」元信息行，不含正文）
  const root = document.querySelector('.content.markdown') || document.querySelector('.content')
  let items = collectHeadings(root, { min: 2, max: 4 })
  // 兜底：正文里只有 h1/h5/h6 时也生成目录（详情页正文内不会出现页面标题 h1.title，
  // 页面标题在 .content 之外，所以这里扫 h1~h6 不会把「内容名」混进目录）
  if (!items.length) items = collectHeadings(root, { min: 1, max: 6 })
  tocItems.value = items
  if (!items.length) { readingProgress.value = 0; activeHeading.value = ''; return }
  readingTracker = trackReading(root, items, ({ progress, activeId }) => {
    readingProgress.value = progress
    activeHeading.value = activeId
  })
}

// 同一路由内切换 id（SPA 复用组件）时重新取数，等价于原页面的一次整页请求
watch(() => route.params.id, async () => {
  liked.value = false
  loadError.value = ''
  culture.value = null
  recommendCultures.value = []
  tags.value = []
  categoryName.value = ''
  closeLightbox()
  await loadDetail()
  await nextTick()
  if (disposed) return
  syncSingleHeight()
  setupToc()
  loadCategoryName()
  computeReadProgress()
})

onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('resize', syncSingleHeight)
  if (readingTracker) { readingTracker.destroy(); readingTracker = null }
  teardownReading()
  if (activeDialog && typeof activeDialog.close === 'function') activeDialog.close()
  activeDialog = null
})
</script>

<style scoped>
.culture-tags { margin: 14px 0 6px; }
.culture-tags__label { color: #a99a86; font-size: 13px; }
.culture-tags__item {
  display: inline-block;
  margin: 0 6px 6px 0;
  padding: 2px 10px;
  font-size: 12px;
  color: #6b5b48;
  background: #f7f2ea;
  border: 1px solid #eee5da;
  border-radius: 999px;
  text-decoration: none;
}
.culture-tags__item:hover { color: #fff; background: #b08968; border-color: #b08968; }

/* ===================== 阅读体验增强（全部 scoped，只作用于本页新增容器） ===================== */

/* 1) 顶部固定阅读进度条（3px + 百分比数字），pointer-events:none 不拦截任何点击 */
.read-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  z-index: 9999;
  pointer-events: none;
  background: rgba(0, 0, 0, .06);
}
.read-progress__bar {
  height: 100%;
  width: 0;
  background: linear-gradient(90deg, #d9a06a, #b08968);
  transition: width .12s linear;
}
.read-progress__pct {
  position: absolute;
  top: 6px;
  right: 10px;
  padding: 1px 8px;
  font-size: 11px;
  line-height: 16px;
  color: #6b5b48;
  background: rgba(255, 255, 255, .85);
  border: 1px solid #eee5da;
  border-radius: 999px;
  font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
}

/* 2) 正文字号切换（正文标题区右侧的小按钮组） */
.read-tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  margin: 6px 0 0;
}
.read-tools__label { font-size: 12px; color: #a99a86; letter-spacing: 1px; }
.read-tools__btn {
  min-width: 30px;
  padding: 2px 8px;
  font-size: 12px;
  line-height: 18px;
  color: #6b5b48;
  background: #fff;
  border: 1px solid #eee5da;
  border-radius: 4px;
  cursor: pointer;
}
.read-tools__btn:hover { color: #b08968; border-color: #b08968; }
.read-tools__btn.is-active { color: #fff; background: #b08968; border-color: #b08968; }

/*
  正文字号只作用于正文容器：.content.markdown 消费 --culture-read-font 变量，
  段落 / 列表 / 引用等子元素本身没有自己的 font-size，直接继承即可。
  （.content 的全局 15px 来自 diaspora.css，这里是 scoped 规则，优先级更高，且只影响本页）
*/
.content.markdown { font-size: var(--culture-read-font, 17px); }

/* 3) 图片灯箱 */
.read-lightbox {
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
  background: rgba(0, 0, 0, .82);
  cursor: zoom-out;
  animation: read-lightbox-in .16s ease-out;
}
.read-lightbox__img {
  max-width: 92vw;
  max-height: 88vh;
  border-radius: 4px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, .5);
  cursor: default;
}
.read-lightbox__close {
  position: absolute;
  top: 16px;
  right: 20px;
  width: 38px;
  height: 38px;
  font-size: 24px;
  line-height: 34px;
  color: #fff;
  background: rgba(255, 255, 255, .14);
  border: 1px solid rgba(255, 255, 255, .35);
  border-radius: 50%;
  cursor: pointer;
}
.read-lightbox__close:hover { background: rgba(255, 255, 255, .28); }
.read-lightbox__hint {
  position: absolute;
  bottom: 16px;
  left: 0;
  right: 0;
  margin: 0;
  text-align: center;
  font-size: 12px;
  color: rgba(255, 255, 255, .75);
  cursor: default;
}
@keyframes read-lightbox-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* 4) 分享卡片（正文底部） */
.read-share {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0 26px;
  padding-top: 14px;
  border-top: 1px solid #f0eae1;
}
.read-share__label { font-size: 13px; color: #a99a86; }
.read-share__btn {
  padding: 4px 14px;
  font-size: 13px;
  color: #6b5b48;
  background: #f7f2ea;
  border: 1px solid #eee5da;
  border-radius: 999px;
  cursor: pointer;
}
.read-share__btn:hover { color: #fff; background: #b08968; border-color: #b08968; }
.read-share__tip { font-size: 12px; color: #b08968; }
</style>
