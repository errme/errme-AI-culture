<template>
  <FrontLayout>
    <!--
      原 frontend/templates/index/index.html 的 body 内容（1:1 移植）。
      loader / topbar / footer / cd_top 四个 th:replace 片段由 FrontLayout 承载
      （index.html 启用了 cd_top，FrontLayout 默认 cdTop = true）。
      标签、class、内联 style、层级与原模板保持完全一致。
    -->

    <!-- 轮播图 -->
    <div class="banner">

      <div id="slidershow" class="carousel slide" data-ride="carousel">
        <ol class="carousel-indicators">

        </ol>
        <div class="carousel-inner">

        </div>
        <a href="#slidershow" class="left carousel-control" data-slide="prev">
          <span class="glyphicon glyphicon-chevron-left"></span>
        </a>
        <a href="#slidershow" class="right carousel-control" data-slide="next">
          <span class="glyphicon glyphicon-chevron-right"></span>
        </a>
      </div>

    </div>

    <!-- muscic（原页面 <audio> 与 onclick="music_s();" 均被注释掉，页面里没有可用的音乐逻辑，保持原样） -->
    <div class="music_mp3">

      <div>
        <img src="/index/images/music3.png" id="music_png" alt="" loading="lazy">
      </div>

    </div>

    <!-- tashou -->
    <!-- 打字机：th:each="announcement : ${announcements}" -->
    <div class="tashuo" v-for="announcement in announcements" :key="announcement.id">
      <div class="tashuo_content">
        <i class="quote-before fa fa-quote-left"></i>
        <span v-html="announcement.announcement"></span>
        <i class="quote-after fa fa-quote-right"></i>
      </div>
      <div class="tashuo_title typed"></div>
      <div class="tashou_bottom"></div>
    </div>


    <!-- 句子：th:each="sentence : ${sentences}" -->
    <div class="sentence-section">
      <div class="inner-width">
        <h1>Good words and good sentences</h1>
        <div class="border"></div>
        <div class="sliders owl-carousel">
          <div class="sentence" v-for="sentence in sentences" :key="sentence.id">
            <div class="sen-info">
              <img class="sen-pic" :src="avatarUrl(sentence.createImg)" alt="" loading="lazy">
              <div class="sen-name">
                <span>{{ sentence.createName }}</span>
                {{ formatYmd(sentence.createTime) }}
              </div>
            </div>
            <p v-html="sentence.content"></p>
          </div>

        </div>
      </div>
    </div>


    <!-- 第三人称 / 热门文化：th:each="culture : ${cultures}" -->
    <div class="ta">
      <div class="you_title">
        <span class="en">热门</span> <span class="cn">&nbsp;&nbsp;/&nbsp;&nbsp;文化</span><br>
        <img src="/index/images/1.gif" loading="lazy">
      </div>


      <div class="ta_article">
        <div class="primary">
          <div class="post" v-for="culture in cultures" :key="culture.id">
            <a :href="`/culture/${culture.id}`" class="posttitle">
              <img :src="coverUrl(culture.fmUrl)" class="cover posttitle" loading="lazy">
            </a>
            <div class="else">
              <p>{{ formatYmd(culture.createTime) }}</p>
              <h3><a :href="`/culture/${culture.id}`" class="posttitle">{{ culture.cultureName }}</a>
              </h3>
              <p v-html="culture.desc"></p>
              <p class="here">
                <span class="icon-letter"><img src="/index/images/ll.png" loading="lazy"> {{ culture.view }}</span>
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!--
      原 index.html <head> 里的两个 underscore 模板（#template_point / #template_image）
      无法放在 SFC 模板里：Vue 的编译器把模板中的 <script>/<style> 当作「有副作用的标签」处理，
        - npm run dev（compiler 的 dev 构建）会直接报错：
          "Tags with side effect (<script> and <style>) are ignored in client component templates."
        - 生产构建虽然不报错，但会把这两个节点从渲染结果里丢弃，underscore 就取不到模板了。
      因此模板内容按原文件逐字保存在 <script setup> 的 TEMPLATE_POINT / TEMPLATE_IMAGE 中，
      由 ensureUnderscoreTemplates() 在挂载时注入到 <head>（与原页面所在位置一致，DOM 上依旧看不到）。
    -->
  </FrontLayout>
</template>

<script setup>
/**
 * 首页（移植自 frontend/templates/index/index.html）
 *
 * 数据：GET /api/home -> { announcements, sentences, hotCultures, cultureToday }
 *      对应原 NavController#index 的 model；原模板里的 ${cultures} 就是 queryHotCulture()
 *      （即接口的 hotCultures）；cultureToday 在原 index.html 里没有对应区块，故不渲染。
 * 脚本：原 index.html <head> 的页面专用脚本（underscore / jsmodern / index.js / typed / owl），
 *      在挂载后按依赖顺序加载；jquery / bootstrap 已由 front.html 全局引入，不重复加载。
 * 样式：index.css / responsive.css / owl / bootstrap 由 front.html 引入，loader.css、cd-top.css
 *      由 FrontLayout 引入；common.css 与 font-awesome.css 来自原 topbar 片段，这里补载。
 */
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { getHome } from '@/api/front'
import { coverUrl, avatarUrl } from '@/utils/format'
import { loadScript, loadScripts, loadStyle, whenJQuery } from '@/utils/loadScript'
import { useSeo } from '@/utils/seo'

/**
 * SEO：首页是站点权重最高的一页，用「站点名 + 一句话定位」作标题，
 * 描述写清站点提供什么内容（文化图文 / 句子随笔），便于搜索结果摘要命中主题。
 */
useSeo({
  title: '遇你 · 传统文化',
  description: '遇你 · 传统文化 —— 记录传统文化的图文与温柔文字：文化专栏、纸篓句子与随笔，把看见的、读到的、喜欢的一一留下。',
  keywords: '遇你,传统文化,文化专栏,传统文化网站,句子,随笔,苍耳',
  image: '/index/images/banner_1.png',
  type: 'website'
})

/** 首页数据（对应原模板的 model：announcements / sentences / cultures） */
const announcements = ref([])
const sentences = ref([])
/** 原模板变量名 ${cultures}，数据来源是后端 queryHotCulture()（接口字段 hotCultures） */
const cultures = ref([])

/** 卸载时需要清理的东西：标题定时器 / 轮播实例 / 我们注册的事件 */
let titleTimer = null
let originTitle = ''
let carouselEl = null
let bannerResizeHandler = null
let visibilityHandler = null

/** yyyy-M-d（对应原模板里的 #dates.format(x, 'yyyy-M-d')，月/日不补零） */
function formatYmd(value) {
  if (!value) return ''
  // 后端 Date 可能被序列化成时间戳（毫秒）或 'yyyy-MM-dd HH:mm:ss'，两种都兼容
  const d = value instanceof Date
    ? value
    : new Date(typeof value === 'number' ? value : String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/**
 * 原 index.html <head> 里的两个 underscore 模板（内容与原模板逐字一致）。
 * 它们不能写进 <template>：Vue 编译期会报「有副作用的标签」并在 dev 下直接失败、生产构建里丢弃节点，
 * 所以这里原样保存文本，由 ensureUnderscoreTemplates() 在挂载时注入 <head>，
 * 供 index.js 的 banner() 与下面的 renderBanner() 用 _.template($('#template_point').html()) 取用。
 */
const TEMPLATE_POINT = `
        <%$.each(model,function(i,item){%>
        <li class="<%=i==0?'active':''%>" data-target="#slidershow" datta-slide-to="<%=i%> "></li>
        <%});%>
    `
const TEMPLATE_IMAGE = `
        <%$.each(model,function(i,item){%>
        <div class="item <%=i==0?'active':''%> ">
            <%if(isMobile){%>
            <a href="" class="m_imageBox">
                <img src="<%=item.mb%>" alt="">
            </a>
            <%}
            else{%>
            <a href="" class="pc_imageBox">
                <img src="<%=item.pc%>" alt="">
            </a>
            <%}
            %>
        </div>
        <%});%>
    `

/**
 * 把两个 underscore 模板注入 <head>（原页面就在 <head>；已存在则跳过）。
 * 注入后不再移除：index.js 里的 banner() 会给自己注册一个无法解绑的 window resize 回调，
 * 离开首页后该回调仍会去取这两个模板，留着它们（惰性、不可见）才不会在别处 resize 时报错，
 * 这也和原页面「模板在整个页面生命周期内一直存在」一致。
 */
function ensureUnderscoreTemplates() {
  const templates = [
    { id: 'template_point', content: TEMPLATE_POINT },
    { id: 'template_image', content: TEMPLATE_IMAGE }
  ]
  templates.forEach(function (item) {
    if (document.getElementById(item.id)) return
    const el = document.createElement('script')
    el.type = 'text/template'
    el.id = item.id
    el.textContent = item.content
    document.head.appendChild(el)
  })
}

/**
 * 原 index.html <head> 里的页面专用脚本。
 * front.html 已经静态引入了 underscore / jsmodern / typed / owl，这里做存在性判断，缺哪个补哪个，
 * 避免这些老插件被重复执行。
 */
const PAGE_LIBS = [
  { src: '/index/lib/underscore/underscore-min.js', ready: () => !!window._ },
  { src: '/index/js/jsmodern.js', ready: () => !!window.jsModern },
  { src: '/index/js/typed.js', ready: () => !!(window.jQuery && window.jQuery.fn.typed) },
  { src: '/index/lib/OwlCarousel/owl.carousel.min.js', ready: () => !!(window.jQuery && window.jQuery.fn.owlCarousel) }
]

/**
 * 按原页面顺序加载脚本：underscore -> jsmodern -> typed -> owl -> index.js。
 * index.js 是页面交互初始化脚本（banner / 打字机 / console），放在最后加载：
 * 它内部走 $(document).ready，与原页面「所有 head 脚本都先于 document ready 就绪」的效果一致。
 */
async function loadPageScripts() {
  const missing = PAGE_LIBS.filter(lib => !lib.ready()).map(lib => lib.src)
  if (missing.length) await loadScripts(missing)
  await loadScript('/index/js/index.js')
}

/** 对应 index.js 的 banner()：用上面两个 underscore 模板渲染轮播图 */
function renderBanner($) {
  const $point = $('#template_point')
  const $image = $('#template_image')
  if (!$point.length || !$image.length) return
  const isMobile = $(window).width() <= 992
  const myData = [
    { pc: '/index/images/banner_1.png', mb: '/index/images/banner_sm_1.png' },
    { pc: '/index/images/banner_2.png', mb: '/index/images/banner_sm_2.png' },
    { pc: '/index/images/banner_3.png', mb: '/index/images/banner_sm_3.png' }
  ]
  const templatePoint = window._.template($point.html())
  const templateImage = window._.template($image.html())
  $('.carousel-indicators').html(templatePoint({ model: myData }))
  $('.carousel-inner').html(templateImage({ model: myData, isMobile: isMobile }))
}

/**
 * 页面交互初始化，对应：
 *   - index.js 的 banner() 与 $(document).ready 里的 typed 初始化
 *   - index.html body 末尾两段内联 <script>（owl 轮播、离开页面改标题）
 * 每次挂载都会执行（SPA 路由切回来时 index.js 不会重复执行，靠这里保证效果一致）。
 */
function initPageInteractions($) {
  // 1) 轮播图：先渲染再初始化，等价于原页面的 data-ride="carousel"（默认 5s 自动切换、hover 暂停）
  renderBanner($)
  carouselEl = document.getElementById('slidershow')
  if (carouselEl) $(carouselEl).carousel()

  // 2) 打字机（原 index.js 的 typed 参数，typeSpeed / backDelay / loop 保持一致）
  $('.typed').typed({
    strings: ['TA说', '遇你', '清白又勇敢'],
    typeSpeed: 400,
    backDelay: 1000,
    // loop
    loop: true
  })

  // 3) 句子区轮播（原 index.html 末尾第一段内联脚本）
  $('.owl-carousel').owlCarousel({
    margin: 10,
    responsiveClass: true,
    responsive: {
      0: {
        items: 1
      },
      680: {
        items: 2
      },
      960: {
        items: 3
      }
    }
  })

  // 4) 窗口尺寸变化时重建轮播图（原 index.js 的 banner() 里注册的 resize）
  bannerResizeHandler = () => renderBanner($)
  $(window).on('resize', bannerResizeHandler)

  // 5) 离开网页时改 title（原 index.html 末尾第二段内联脚本）
  originTitle = document.title
  visibilityHandler = function () {
    if (document.hidden) {
      // 原页面这里写的是相对路径 ./images/faviconlea.ico（会解析到 /images/），按迁移规则改为绝对路径
      $('[rel="shortcut icon"]').attr('href', '/index/images/faviconlea.ico')
      document.title = '(●—●)喔哟，崩溃啦！'
      clearTimeout(titleTimer)
    } else {
      $('[rel="shortcut icon"]').attr('href', '/index/images/favicon.ico')
      document.title = '(/≧▽≦/)咦！又好了！ ' + originTitle
      titleTimer = setTimeout(function () {
        document.title = originTitle
      }, 2000)
    }
  }
  document.addEventListener('visibilitychange', visibilityHandler)
}

onMounted(async () => {
  await nextTick()

  // ==== 样式：原 topbar 片段带来的 common.css / font-awesome.css（FrontLayout 只负责 DOM，不加载这部分 CSS） ====
  loadStyle('/index/css/common.css')
  loadStyle('/index/css/font-awesome.css')

  // ==== 数据：GET /api/home（对应原 NavController#index 的 model） ====
  // 先取数据把 v-for 渲染出来，老插件（打字机、owl）才找得到元素
  try {
    const data = (await getHome()) || {}
    announcements.value = data.announcements || []
    sentences.value = data.sentences || []
    cultures.value = data.hotCultures || []
  } catch (e) {
    console.warn('[HomeView] 首页数据加载失败：', e && e.message)
  }
  await nextTick()

  // ==== 脚本：原 index.html <head> 的页面专用脚本，按顺序加载 ====
  // underscore 模板先注入（index.js 的 $(document).ready 里会立刻用到）
  ensureUnderscoreTemplates()
  try {
    await loadPageScripts()
  } catch (e) {
    console.warn('[HomeView] 页面脚本加载失败：', e && e.message)
  }

  // ==== 交互初始化（轮播 / 打字机 / owl / 改标题） ====
  try {
    const $ = await whenJQuery()
    initPageInteractions($)
  } catch (e) {
    console.warn('[HomeView] 页面交互初始化失败：', e && e.message)
  }
})

onBeforeUnmount(() => {
  const $ = window.jQuery

  // 1) 离开网页改标题的监听与定时器
  if (visibilityHandler) document.removeEventListener('visibilitychange', visibilityHandler)
  visibilityHandler = null
  if (titleTimer) {
    clearTimeout(titleTimer)
    titleTimer = null
  }
  // 标题确实被「离开页面」逻辑改过才还原，避免覆盖下一个路由的 title
  if (document.title === '(●—●)喔哟，崩溃啦！' ||
    document.title.indexOf('(/≧▽≦/)咦！又好了！ ') === 0) {
    document.title = originTitle
  }

  if (!$) return

  // 2) 我们注册的 banner resize
  if (bannerResizeHandler) {
    $(window).off('resize', bannerResizeHandler)
    bannerResizeHandler = null
  }

  // 3) bootstrap 轮播：停掉自动切换定时器
  if (carouselEl) {
    try {
      $(carouselEl).carousel('pause')
    } catch (e) { /* 插件未就绪，无需处理 */ }
    carouselEl = null
  }

  // 4) 句子区 owl 轮播：销毁实例（内部会解绑它注册的 resize 等事件）
  try {
    $('.owl-carousel').trigger('destroy.owl.carousel')
  } catch (e) { /* 未初始化，无需处理 */ }

  // 5) 打字机：只对已存在的实例调 reset（清除内部 setTimeout / 光标），避免误建新实例
  try {
    $('.typed').each(function () {
      if ($(this).data('typed')) $(this).typed('reset')
    })
  } catch (e) { /* 未初始化，无需处理 */ }
})
</script>
