<script>
/**
 * 文化列表页 —— frontend/templates/index/culture.html 的 1:1 移植
 *
 * · DOM 标签 / class / 内联 style / 层级与原文件完全一致（含原页内联 JS 动态拼接出的卡片结构）
 * · 原文件 cd_top 片段是注释掉的 → <FrontLayout :cd-top="false">
 * · 原文件 topbar 的 th:include 未附带任何内联 style（detail.html 才有 margin-top:30px），
 *   故本页不需要额外的样式包裹层
 * · 唯一新增的绑定是 @submit.prevent（原页面回车会整页提交，SPA 下需阻止），不产生任何 DOM 差异
 *
 * 模块级共享状态 legacyJq：
 * 原页面用 jQuery 1.11.1 + bootstrap-select + bootstrap-paginator。这些脚本由 loadScript
 * 按 src 去重，整站只会真正执行一次；jQuery 1.11.1 实例在首次挂载时经 noConflict(true)
 * 收回本地（全局 jQuery 仍是 front.html 引入的 3.4.1）。因此该实例必须缓存在模块作用域，
 * 保证「列表 → 详情 → 返回列表」二次进入本页时插件依旧可用。
 */
let legacyJq = null
</script>

<template>
  <FrontLayout :cd-top="false">

    <div class="slider-area" style="height: 250px">
      <div class="container slider-content">
        <div class="row">
          <div class="col-lg-8 col-lg-offset-2 col-md-10 col-md-offset-1 col-sm-12">
            <form action="" class="form-inline" @submit.prevent="searchCulture">
              <div class="form-group">
                <input type="text" id="q_cultureName" class="form-control" placeholder="Please enter" v-model="cultureName">
              </div>
              <div class="form-group">
                <select id="q_categoryId" name="categoryid" class="selectpicker" data-live-search="false"
                        data-live-search-style="begins" title="ALL" v-model="categoryId">
                  <option value="-1">ALL</option>
                  <option v-for="category in categorys" :key="category.id" :value="category.id">{{ category.categoryName }}</option>

                </select>
              </div>
              <i class="fa fa-search" style="color: pink; margin-left: 20px" @click="searchCulture"></i>
            </form>

          </div>
        </div>
      </div>
    </div>

    <!-- property area -->
    <div class="content-area recent-property" style="padding-bottom: 60px; background-color: rgb(252, 252, 252);">
      <div class="container">
        <div class="row">
          <div class="col-md-12  padding-top-40 properties-page">

            <div class="col-md-12 ">
              <div id="cultureContent" class="proerty-th">

                <!-- 原页内联 JS 拼接出的卡片结构，逐字符对应（原 JS 中被注释掉的 info 简介同样不渲染） -->
                <div v-for="c in cultures" :key="c.id" class="col-sm-6 col-md-3 p0">
                  <div class="box-two proerty-item">
                    <div class="item-thumb">
                      <a :href="'/culture/' + c.id"><img :src="coverUrl(c.fmUrl)" loading="lazy"></a>
                    </div>

                    <div class="item-entry overflow">
                      <h5><a :href="'/culture/' + c.id">{{ c.cultureName }} </a></h5>
                      <div class="dot-hr"></div>
                      <span class="pull-left"><b> 地址 :</b> {{ c.address }} </span>
                      <span class=" pull-right"><b> 浏览 :</b> {{ c.view }}</span>
                      <div class="property-icon"></div>
                    </div>
                  </div>
                </div>

              </div>
            </div>
            <!-- 分页start-->
            <div class="col-md-12">
              <div class="pull-right">
                <div class="pagination">
                  <ul id="pageLimit">
                  </ul>
                </div>
              </div>
            </div>
            <!-- 分页end-->
          </div>
        </div>
      </div>
    </div>

  </FrontLayout>
</template>

<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount } from 'vue'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { getCultureList, getCategorys } from '@/api/front'
import { coverUrl, pickPage } from '@/utils/format'
import { loadStyle, loadScripts } from '@/utils/loadScript'
import { useSeo } from '@/utils/seo'

/**
 * SEO：列表页标题带站点名（「文化列表 · 遇你」），后缀固定便于搜索引擎识别站群归属；
 * 描述概括本页收录的内容（传统文化图文列表），给「文化列表 / 传统文化」类词一个落地摘要。
 */
useSeo({
  title: '文化列表 · 遇你',
  description: '文化列表 · 遇你 —— 按分类浏览传统文化图文：博物馆、非遗、节气与民俗，每一条都有地址、封面与浏览热度。',
  keywords: '文化列表,传统文化,博物馆,非遗,民俗,遇你',
  image: '/index/images/banner_1.png',
  type: 'website'
})

/**
 * 数据来源（与后端一一对应）
 *   GET /api/culture/list  参数 CultureQuery：page、pageSize、cultureName、categoryId（-1 = 全部）
 *   返回 PageList：{ total, rows }（web/src/utils/format.js 的 pickPage 兼容 rows/list）
 *   GET /api/culture/categorys -> List<Category>{ id, categoryName }
 */

/** 原页内联 JS 中的分页参数，保持一致 */
const PAGE_SIZE = 12
let currentPage = 1

const cultures = ref([])
const categorys = ref([])
const cultureName = ref('')
/** 与原页面一致：默认值 -1 即 ALL（后端 whereSql 对 -1 不过滤） */
const categoryId = ref('-1')

/** 组件是否已卸载（异步回调里避免继续更新状态 / 操作 DOM） */
let disposed = false
/** main.js 中计数器 setInterval 的 id（原页面遗留，卸载时清理） */
const legacyTimers = []
/** 撤销 setInterval 捕获的函数 / 其定时器 */
let releaseCapture = null
let releaseTimer = null

/** 原 culture.html <head> 中的样式（bootstrap / loader 已由 front.html 与 FrontLayout 引入） */
const PAGE_STYLES = [
  '/index/css/common.css',                    // 原 common.html topbar 片段引入（FrontLayout 未加载）
  '/index/css/font-awesome.css',              // 原 common.html topbar 片段引入
  '/index/css/culture/font-awesome.min.css',
  '/index/css/culture/bootstrap-select.min.css',
  '/index/css/culture/bootstrap.min.css',
  '/index/css/culture/style.css'
]

/** 原 culture.html <head> 中的脚本，顺序与原文完全一致 */
const PAGE_SCRIPTS = [
  '/index/js/jquery-1.11.1.min.js',
  '/index/js/culture/bootstrap.min.js',
  '/index/js/culture/bootstrap-select.min.js',
  '/index/js/culture/main.js',
  '/index/js/culture/bootstrap-paginator.js'
]

/* ===================== 列表数据 ===================== */

/** CultureQuery 查询参数（空值不下发，落在后端 `<if>` 的不过滤分支） */
function listParams() {
  const params = { page: currentPage, pageSize: PAGE_SIZE }
  if (cultureName.value) params.cultureName = cultureName.value
  if (categoryId.value !== '' && categoryId.value !== null && categoryId.value !== undefined) {
    params.categoryId = categoryId.value
  }
  return params
}

async function fetchList() {
  try {
    const res = await getCultureList(listParams())
    if (disposed) return
    const { rows, total } = pickPage(res)
    cultures.value = rows
    await nextTick()
    if (disposed) return
    // 原页面：Math.ceil(result.total / pageSize)；总数为 0 时插件会抛 "Page out of range"，故至少 1 页
    renderPager(Math.max(1, Math.ceil(total / PAGE_SIZE)))
  } catch (e) {
    if (disposed) return
    console.warn('[CultureListView] 文化列表加载失败：', e)
    cultures.value = []
    await nextTick()
    if (!disposed) renderPager(1)
  }
}

/** 原 searchCulture()：点击放大镜后按当前条件查询（此处回到第 1 页，避免停留在旧页码查不到数据） */
function searchCulture() {
  currentPage = 1
  fetchList()
}

/* ===================== 老插件（下拉筛选 + 分页控件） ===================== */

/**
 * 原页面靠 <head> 中的脚本 + window load 自动初始化 bootstrap-select。
 * SPA 中 window load 早已触发，data-api 不会再执行，因此加载完脚本必须显式初始化，
 * 但初始化时机仍然放在「DOM 已渲染」之后，视觉效果与原页面一致。
 */
function initSelectPicker() {
  if (!legacyJq || !legacyJq.fn || !legacyJq.fn.selectpicker) return
  const $select = legacyJq('#q_categoryId')
  // 已初始化过（二次进入本页）时用 refresh 重新同步 option 与按钮文案
  if ($select.data('selectpicker')) $select.selectpicker('refresh')
  else $select.selectpicker()
}

/** 原 setPage()：bootstrap-paginator 渲染出与原文一致的 ul#pageLimit */
function renderPager(totalPages) {
  if (!legacyJq || !legacyJq.fn || !legacyJq.fn.bootstrapPaginator) return
  legacyJq('#pageLimit').bootstrapPaginator({
    // 设置版本号
    bootstrapMajorVersion: 3,
    // 显示第几页
    currentPage: currentPage,
    // 总页数
    totalPages: totalPages,
    // 当单击操作按钮的时候, 执行该函数, 调用接口渲染页面
    onPageClicked: function (event, originalEvent, type, page) {
      if (disposed) return
      currentPage = page
      // 原回调是 render()（不传参，会把筛选条件丢掉），这里改为带上当前筛选条件，翻页时筛选不被重置
      fetchList()
    }
  })
}

/**
 * 捕获 setInterval：原 main.js 里有一个 2ms 的计数器定时器（本页没有 #counter 元素，
 * 属于原站遗留），在 SPA 中必须随组件卸载一起清理，否则路由切换后会持续泄漏。
 */
function startTimerCapture() {
  const rawSetInterval = window.setInterval
  window.setInterval = function (fn, delay, ...rest) {
    const id = rawSetInterval.call(window, fn, delay, ...rest)
    legacyTimers.push(id)
    return id
  }
  releaseCapture = function () {
    window.setInterval = rawSetInterval
    releaseCapture = null
  }
}

/** 按原页面顺序加载脚本，并在此期间捕获 main.js 启动的定时器 */
async function loadScriptsCapturingTimers(list) {
  if (!releaseCapture) startTimerCapture()
  try {
    await loadScripts(list)
  } catch (e) {
    console.warn('[CultureListView] 原有脚本加载失败：', e)
  }
  // main.js 的计数器定时器由它内部 setTimeout(500ms) 延迟创建，等它跑出来之后再撤销捕获
  if (releaseTimer) clearTimeout(releaseTimer)
  releaseTimer = window.setTimeout(() => {
    releaseTimer = null
    if (releaseCapture) releaseCapture()
  }, 800)
}

/** 是否已拿到挂着原页面插件的 jQuery 实例 */
function hasLegacyPlugins(jq) {
  return !!(jq && jq.fn && jq.fn.selectpicker && jq.fn.bootstrapPaginator)
}

/**
 * 捕获原页面用的 jQuery 实例。
 * 原页面全局只有一个 jQuery 1.11.1；SPA 里 front.html 已全局加载 jQuery 3.4.1。
 * 这里用 noConflict(true) 把 1.11.1 收回本地实例：插件仍绑在 1.11.1 上（外观/行为与原文一致），
 * window.jQuery / $ 依旧还原成调用前的全局版本，不影响 FrontLayout 等其它组件。
 */
function captureLegacyJq() {
  if (hasLegacyPlugins(legacyJq)) return
  const jq = window.jQuery
  if (!jq || !jq.fn) return
  if (/^1\.11\./.test(String(jq.fn.jquery)) && typeof jq.noConflict === 'function') {
    legacyJq = jq.noConflict(true)
  } else {
    legacyJq = jq
  }
}

/** 加载原页面 head 中的样式与脚本 */
async function loadLegacyAssets() {
  PAGE_STYLES.forEach(href => { loadStyle(href).catch(() => {}) })

  // 原 <head> 顺序：jquery-1.11.1 -> bootstrap -> bootstrap-select -> main -> bootstrap-paginator
  await loadScriptsCapturingTimers(PAGE_SCRIPTS)
  captureLegacyJq()

  // 兜底：脚本由 loadScript 按 src 去重、只会执行一次，若同一文档内已被其它页面换成别的 jQuery
  // （拿不到原页面的两个插件），就用带查询串的地址强制重跑一遍原脚本，重新拿到 1.11.1 + 插件
  if (!hasLegacyPlugins(legacyJq)) {
    await loadScriptsCapturingTimers(PAGE_SCRIPTS.map(src => src + '?culture-list=1'))
    captureLegacyJq()
  }
}

/* ===================== 生命周期 ===================== */

onMounted(async () => {
  // 1) 原页面脚本位于 </body> 前：先有 DOM，再执行老插件
  await nextTick()

  // 2) 原 <head> 的样式与脚本（jquery -> bootstrap -> bootstrap-select -> main -> bootstrap-paginator）
  await loadLegacyAssets()
  if (disposed) return

  // 3) 分类下拉：先让 Vue 把 option 渲染出来，再初始化 bootstrap-select，避免插件 UI 与 v-for 打架
  try {
    const list = await getCategorys()
    if (disposed) return
    categorys.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (disposed) return
    console.warn('[CultureListView] 分类加载失败：', e)
    categorys.value = []
  }
  await nextTick()
  if (disposed) return
  initSelectPicker()

  // 4) 首屏列表（对应原页面最后的 render()）
  await fetchList()
})

onBeforeUnmount(() => {
  disposed = true

  // 撤销 setInterval 捕获 + 清理 main.js 启动的定时器
  if (releaseTimer) {
    clearTimeout(releaseTimer)
    releaseTimer = null
  }
  if (releaseCapture) releaseCapture()
  legacyTimers.splice(0).forEach(id => {
    clearInterval(id)
    clearTimeout(id)
  })

  // 解绑分页控件事件 / 销毁 bootstrap-select 的 UI，避免全局残留
  if (legacyJq) {
    try {
      legacyJq('#pageLimit').off('page-clicked').off('page-changed').removeData('bootstrapPaginator')
    } catch (e) { /* 插件未初始化 */ }
    try {
      const $select = legacyJq('#q_categoryId')
      if ($select.data('selectpicker')) $select.selectpicker('destroy')
    } catch (e) { /* 插件未初始化 */ }
  }
  // 保留模块级 legacyJq 供二次进入本页复用（脚本已去重，不会再次执行）
})
</script>

<!--
  说明：原 culture.html <head> 里还有一段内联 <style>.search-btn{...}</style>，
  本页 DOM 中并不存在使用 .search-btn 的元素（搜索图标用的是 .fa-search），
  为遵守「不写全局样式」故不再注入，页面样式与原文件无差异。
-->
