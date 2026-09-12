<!--
  全站搜索页（寻一寻）

  数据：GET /api/search（见 web/src/api/front.js 的 search / searchFiltered）
    params: { keyword, page, pageSize, type: 'all' | 'culture' | 'sentence' }
    返回：{ keyword, totalCulture, totalSentence, cultures[], sentences[] }
    cultures[i].highlight 为后端生成的高亮片段（已转义 + <em> 标注的**安全 HTML**，直接 v-html）。
    另：GET /api/search/hot（免登录，热门词）、GET /api/search/history（需登录，最近搜索；未登录不请求）。

  组合筛选（Batch5 + 服务端化）：分类下拉（/api/culture/categorys）、标签下拉（/api/tag/list）、
    时间范围（最近一周/一月/一年/全部）。三个条件现在**全部由后端 /api/search 过滤**
    （categoryId / tagId / startTime / endTime，见 ApiSearchController#search），
    前端只负责把条件换算成参数发出去，不再拉 50 条候选回来本地过滤，
    因此筛选结果是全量口径（总数 = 服务端总数），不受候选池大小限制。
    条件写进 URL query，可直接分享 / 回退恢复；顶部以 chip 展示生效条件，可单个移除或一键清除。
    注意：分类 / 标签只作用于文化结果（句子表没有这两个字段），生效时句子区整块隐藏。

  结构 / class 全部复用现有页面：
    · 顶部搜索条 = CultureListView 的 .slider-area + .form-inline 结构；
    · 文化结果   = CultureListView 的 .proerty-th / .box-two.proerty-item 卡片；
    · 句子结果   = SentenceView 的 .item.box / .userInfo / .zhipian 结构。
  页面自身不新增任何全局样式：样式表沿用原有的 /index/css/culture/style.css
  （文化卡片与 .box 卡片必需，与列表页同一套），见下方 PAGE_STYLES。
  文件末尾的 scoped 样式只作用于本页新增容器（`.sh-hit` 高亮片段、`.sf*` 筛选区与空状态），
  不污染全站样式。

  注意：句子页的 zhilou.css 里有一条全局 `* { box-sizing: inherit }`，
  在本页引入会破坏文化卡片的栅格布局，故不加载；句子卡片改用行内样式补齐
  （宽度 / 间距 / 内边距），外观仍与站点其它卡片一致。
-->
<template>
  <FrontLayout>

    <!-- ===== 搜索条（沿用文化列表页 slider-area 结构） ===== -->
    <div class="slider-area" style="height: 250px">
      <div class="container slider-content">
        <div class="row">
          <div class="col-lg-8 col-lg-offset-2 col-md-10 col-md-offset-1 col-sm-12">
            <form action="" class="form-inline" @submit.prevent="submit">
              <div class="form-group">
                <input type="text" id="q_keyword" class="form-control" placeholder="寻一寻，输入关键词"
                       v-model="keywordInput">
              </div>
              <i class="fa fa-search" style="color: pink; margin-left: 20px" @click="submit"></i>
            </form>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 结果区（沿用文化列表页 content-area 结构） ===== -->
    <div class="content-area recent-property" style="padding-bottom: 60px; background-color: rgb(252, 252, 252);">
      <div class="container">
        <div class="row">
          <div class="col-md-12 padding-top-40 properties-page">

            <!-- ===== 热门搜索 / 最近搜索（搜索框下方，点击即搜索） ===== -->
            <div v-if="hotKeywords.length || historyKeywords.length" class="col-md-12"
                 style="margin-bottom: 10px;">
              <div v-if="hotKeywords.length" style="line-height: 2;">
                <b style="color: #555;">热门搜索：</b>
                <a v-for="h in hotKeywords" :key="'hot-' + h.keyword" href="javascript:void(0)"
                   style="margin-right: 16px; color: #777;"
                   @click="searchKeyword(h.keyword)">{{ h.keyword }}</a>
              </div>
              <!-- 最近搜索：仅登录用户可见（未登录时不请求 /search/history，避免 401） -->
              <div v-if="historyKeywords.length" style="line-height: 2;">
                <b style="color: #555;">最近搜索：</b>
                <a v-for="k in historyKeywords" :key="'his-' + k" href="javascript:void(0)"
                   style="margin-right: 16px; color: #777;"
                   @click="searchKeyword(k)">{{ k }}</a>
              </div>
            </div>

            <!--
              ===== 组合筛选（分类 / 标签 / 时间范围） =====
              三个条件全部由后端 /api/search 过滤（见 api/front.js 的 searchFiltered 注释）：
              条件变化 -> 写进 URL query -> watcher 触发重新请求（服务端筛选，全量口径）。
            -->
            <div class="col-md-12 sf">
              <div class="sf__bar">
                <div class="sf__field">
                  <label class="sf__label" for="sf_category">分类</label>
                  <select id="sf_category" class="sf__select" v-model="categoryModel">
                    <option value="">全部分类</option>
                    <option v-for="c in categorys" :key="c.id" :value="String(c.id)">{{ c.categoryName }}</option>
                  </select>
                </div>
                <div class="sf__field">
                  <label class="sf__label" for="sf_tag">标签</label>
                  <select id="sf_tag" class="sf__select" v-model="tagModel">
                    <option value="">全部标签</option>
                    <option v-for="t in tags" :key="t.id" :value="String(t.id)">{{ t.name }}</option>
                  </select>
                </div>
                <div class="sf__field">
                  <label class="sf__label" for="sf_range">时间</label>
                  <select id="sf_range" class="sf__select" v-model="rangeModel">
                    <option v-for="opt in RANGE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                  </select>
                </div>
                <button v-if="hasActiveFilters" type="button" class="sf__clear" @click="clearFilters">清除全部</button>
              </div>

              <!-- 当前生效的筛选条件（chip，可单个移除） -->
              <div v-if="activeChips.length" class="sf__chips">
                <span class="sf__chips-label">当前筛选：</span>
                <span v-for="chip in activeChips" :key="chip.key" class="sf__chip">
                  {{ chip.label }}
                  <button type="button" class="sf__chip-x" :aria-label="'移除筛选：' + chip.label"
                          @click="removeFilter(chip.key)">×</button>
                </span>
              </div>

              <p class="sf__note">
                筛选由服务端执行（结果与总数均为全量口径）；分类与标签只作用于文化结果，
                选择后句子区会隐藏。
              </p>
            </div>

            <!-- 没有关键词：给一句引导，不请求接口 -->
            <div v-if="!keyword" class="col-md-12" style="text-align: center; color: #9E9E9E; padding: 60px 0;">
              输入关键词，寻一寻你想找的文字
            </div>

            <template v-else>
              <!-- 空结果：友好空状态（复用页面既有的灰底居中风格 + scoped 样式） -->
              <div v-if="!isSearching && !hasAnyResult" class="col-md-12 sf-empty">
                <div class="sf-empty__icon">(・_・)</div>
                <p class="sf-empty__title">没有找到与「{{ keyword }}」匹配的内容</p>
                <p class="sf-empty__hint">
                  <template v-if="hasActiveFilters">已应用 {{ activeChips.length }} 个筛选条件，
                    <a href="javascript:void(0)" @click="clearFilters">清除全部筛选</a> 看看更多结果
                  </template>
                  <template v-else>换个关键词试试，或到 <a href="/culture">文化列表</a> / <a href="/sentence">句子</a> 里逛逛</template>
                </p>
              </div>

              <template v-else>
              <div v-if="isSearching" class="col-md-12 sf-loading">搜索中…</div>

              <!-- ================= 文化 ================= -->
              <div class="col-md-12" v-if="isSearching || cultures.length">
                <h3 style="margin-bottom: 20px;">
                  文化
                  <small style="color: #9E9E9E;">共 {{ totalCulture }} 条</small>
                </h3>

                <div id="cultureContent" class="proerty-th">
                  <div v-for="c in cultures" :key="c.id" class="col-sm-6 col-md-3 p0">
                    <div class="box-two proerty-item">
                      <div class="item-thumb">
                        <a :href="'/culture/' + c.id"><img :src="coverUrl(c.fmUrl)" loading="lazy"></a>
                      </div>

                      <div class="item-entry overflow">
                        <!--
                          标题 / 描述高亮：
                          · 后端 highlight 已做 HTML 转义并只插入 <em>，可直接 v-html（**不要再转义一次**）；
                          · titleHtml = 名称命中片段（没有则回退到原始 cultureName 纯文本）；
                          · descHtml  = 描述/摘要命中片段（没有则不显示）。
                        -->
                        <h5>
                          <a :href="'/culture/' + c.id">
                            <template v-if="c.titleHtml"><span v-html="c.titleHtml"></span></template>
                            <template v-else>{{ c.cultureName }} </template>
                          </a>
                        </h5>
                        <div class="dot-hr"></div>
                        <p v-if="c.descHtml" class="sh-hit" v-html="c.descHtml"></p>
                        <span class="pull-left"><b> 地址 :</b> {{ c.address }} </span>
                        <span class=" pull-right"><b> 浏览 :</b> {{ c.view }}</span>
                        <div class="property-icon"></div>
                      </div>
                    </div>
                  </div>

                  <p v-if="!cultures.length" class="not-found" style="clear: both; color: #9E9E9E; padding: 20px 15px;">
                    没有找到相关内容
                  </p>
                </div>
              </div>

              <!-- ================= 句子（分类 / 标签筛选只作用于文化，故此时隐藏句子区） ================= -->
              <div class="col-md-12" style="clear: both;" v-if="showSentences && (isSearching || sentences.length)">
                <h3 style="margin: 30px 0 20px;">
                  句子
                  <small style="color: #9E9E9E;">共 {{ totalSentence }} 条</small>
                </h3>

                <div class="contentTwo box" style="background: none; border: 0; box-shadow: none; padding: 0;">
                  <!-- 结构与 SentenceView 一致（.item.box），布局用行内样式补齐 -->
                  <div class="item box" v-for="s in sentences" :key="s.id" :content="s.content"
                       style="width: 320px; min-height: 200px; float: left; margin: 0 24px 24px 0; position: relative;">
                    <div class="userInfo box" style="background: none; border: 0; box-shadow: none; padding: 0;">
                      <img :src="avatarUrl(s.createImg)"
                           style="width: 60px; height: 60px; float: left; border-radius: 50%;" loading="lazy">
                      <div class="user_R box" style="background: none; border: 0; box-shadow: none; padding: 0;">
                        <div class="zhipian_name">{{ s.createName }}</div>
                        <div class="zhipian_time">{{ formatYmd(s.createTime) }}</div>
                      </div>
                    </div>
                    <div class="zhipian box" :content="s.content"
                         style="clear: left; margin-top: 20px; line-height: 1.8em; word-wrap: break-word; background: none; border: 0; box-shadow: none; padding: 0;">
                      {{ s.content }}
                    </div>
                  </div>

                  <p v-if="!sentences.length" class="not-found" style="clear: both; color: #9E9E9E; padding: 20px 15px;">
                    没有找到相关内容
                  </p>
                  <div style="clear: both;"></div>
                </div>
              </div>

              <!-- 结果多于首页容量时的提示（接口的 page/pageSize 对两块结果同时生效） -->
              <div class="col-md-12" style="clear: both; color: #9E9E9E; padding: 10px 15px;"
                   v-if="totalCulture > cultures.length || (showSentences && totalSentence > sentences.length)">
                结果较多，当前仅展示前 {{ PAGE_SIZE }} 条
              </div>
              <!-- 分类 / 标签生效时句子区被隐藏，这里说明一句，避免用户以为句子丢了 -->
              <div class="col-md-12" style="clear: both; color: #9E9E9E; padding: 10px 15px;"
                   v-else-if="!showSentences">
                分类 / 标签筛选只作用于文化内容，已隐藏句子结果；只想看句子请把类型切到「句子」或清除该筛选
              </div>
              </template>
            </template>

          </div>
        </div>
      </div>
    </div>

  </FrontLayout>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { getCategorys, getTags, searchFiltered, searchHistory, searchHot } from '@/api/front'
import { coverUrl, avatarUrl } from '@/utils/format'
import { loadStyle } from '@/utils/loadScript'
import { useFrontUserStore } from '@/stores/frontUser'

/** 热门搜索 / 最近搜索条数（后端上限 20，默认 10） */
const TOP_LIMIT = 10

/** 每页条数（与文化列表页 4 列栅格对齐：12 = 3 行） */
const PAGE_SIZE = 12

/**
 * 时间范围选项：`days` 用来换算成后端能识别的 `yyyy-MM-dd`（startTime）。
 * 后端 /api/search 支持 startTime / endTime（闭区间，只给日期时 endTime 按当天 23:59:59 处理），
 * 因此「最近 N 天」只需把 startTime 传成「N 天前的日期」即可。
 */
const RANGE_OPTIONS = [
  { value: 'all', label: '全部时间', days: 0 },
  { value: 'week', label: '最近一周', days: 7 },
  { value: 'month', label: '最近一月', days: 30 },
  { value: 'year', label: '最近一年', days: 365 }
]

/** URL query 的合法值集合（非法值一律忽略，避免被随手改的 URL 影响） */
const RANGE_VALUES = RANGE_OPTIONS.map(o => o.value)

/** 原站点样式（文化卡片 .box-two.proerty-item / .box 必需，与列表页同一套） */
const PAGE_STYLES = [
  '/index/css/common.css',
  '/index/css/font-awesome.css',
  '/index/css/culture/font-awesome.min.css',
  '/index/css/culture/style.css'
]

const route = useRoute()
const router = useRouter()
const user = useFrontUserStore()

/** 输入框内容（与 URL 上的 keyword 同步） */
const keywordInput = ref('')
/** 当前生效的关键词（来自 URL） */
const keyword = ref('')
/** 服务端返回的文化 / 句子结果（筛选已在服务端完成，前端不再二次过滤） */
const cultures = ref([])
const sentences = ref([])
const totalCulture = ref(0)
const totalSentence = ref(0)
/** 请求中标记（用于「搜索中…」与空状态判断，避免闪烁） */
const loading = ref(false)

/** ===== 组合筛选状态（唯一数据源是 URL query，见 syncFromQuery） ===== */
/** 分类下拉数据（getCategorys）+ 当前选中分类 id（字符串，'' = 全部） */
const categorys = ref([])
const filterCategoryId = ref('')
/** 标签下拉数据（getTags）+ 当前选中标签 id（字符串，'' = 全部） */
const tags = ref([])
const filterTagId = ref('')
/** 时间范围（all | week | month | year） */
const filterRange = ref('all')

/** 热门搜索词（免登录）/ 最近搜索（仅登录用户，未登录时保持空数组 = 不显示） */
const hotKeywords = ref([])
const historyKeywords = ref([])

/** 请求序号：只认最后一次请求的结果，避免快速连续搜索时旧响应覆盖新结果 */
let reqId = 0
/** 组件已卸载：卸载后不再回写状态 */
let disposed = false

/* =====================================================================================
 * 组合筛选（分类 / 标签 / 时间范围）
 *
 * 后端能力（ApiSearchController#search）：/api/search 支持
 *   keyword / page / pageSize / type + categoryId / tagId / startTime / endTime。
 * 所以三个条件都是**服务端筛选**：条件变化只做一件事 —— 写进 URL query，
 * 由 watcher 用新参数重新请求（总数与列表口径完全一致）。
 *   · 分类：categoryId -> biz_culture.category_id；
 *   · 标签：tagId -> biz_culture_tag 关联（后端用 exists，不会因多标签重复计数）；
 *   · 时间：startTime（yyyy-MM-dd）-> created_at 闭区间下界。
 * 分类 / 标签只作用于文化结果（句子表没有这两个字段），生效时句子区整块隐藏。
 * ===================================================================================== */

/** 当前选中分类名 / 标签名（chip 文案用；列表还没加载完时退回显示 id） */
const categoryLabel = computed(() => {
  const hit = categorys.value.find(c => String(c.id) === filterCategoryId.value)
  return hit ? hit.categoryName : filterCategoryId.value
})
const tagLabel = computed(() => {
  const hit = tags.value.find(t => String(t.id) === filterTagId.value)
  return hit ? hit.name : filterTagId.value
})
const rangeLabel = computed(() => {
  const hit = RANGE_OPTIONS.find(o => o.value === filterRange.value)
  return hit ? hit.label : filterRange.value
})

/** 生效的条件 chips（可单个移除） */
const activeChips = computed(() => {
  const chips = []
  if (filterCategoryId.value) chips.push({ key: 'categoryId', label: '分类：' + categoryLabel.value })
  if (filterTagId.value) chips.push({ key: 'tagId', label: '标签：' + tagLabel.value })
  if (filterRange.value !== 'all') chips.push({ key: 'range', label: '时间：' + rangeLabel.value })
  return chips
})
const hasActiveFilters = computed(() => activeChips.value.length > 0)

/** 分类 / 标签只作用于文化结果：这两个条件生效时句子区无法匹配，直接隐藏 */
const showSentences = computed(() => !filterCategoryId.value && !filterTagId.value)

/**
 * 时间范围 -> 后端 startTime（`yyyy-MM-dd`，本地时区当天 00:00，闭区间下界）。
 * all / 未知值返回 ''（= 不发送该参数）。用本地日期而不是 toISOString()：
 * 后者是 UTC，东八区凌晨会算成前一天。
 */
function rangeStartDate() {
  const opt = RANGE_OPTIONS.find(o => o.value === filterRange.value)
  if (!opt || !opt.days) return ''
  const d = new Date(Date.now() - opt.days * 24 * 60 * 60 * 1000)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 是否有任意结果（空状态判断用） */
const hasAnyResult = computed(() => cultures.value.length > 0 || (showSentences.value && sentences.value.length > 0))

/** 正在搜索：此时不展示空状态 */
const isSearching = computed(() => loading.value)

/** 下拉的 v-model 代理：写入即同步 URL（唯一数据源），由 URL watcher 驱动重新搜索 */
const categoryModel = computed({
  get: () => filterCategoryId.value,
  set: v => applyFilters({ categoryId: v })
})
const tagModel = computed({
  get: () => filterTagId.value,
  set: v => applyFilters({ tagId: v })
})
const rangeModel = computed({
  get: () => filterRange.value,
  set: v => applyFilters({ range: v })
})

/* =====================================================================================
 * 组合筛选（分类 / 标签 / 时间范围）
 *
 * 后端能力（ApiSearchController#search）：/api/search 支持
 *   keyword / page / pageSize / type + categoryId / tagId / startTime / endTime。
 * 所以三个条件都是**服务端筛选**：条件变化只做一件事 —— 写进 URL query，
 * 由 watcher 用新参数重新请求（总数与列表口径完全一致）。
 *   · 分类：categoryId -> biz_culture.category_id；
 *   · 标签：tagId -> biz_culture_tag 关联（后端用 exists，不会因多标签重复计数）；
 *   · 时间：startTime（yyyy-MM-dd）-> created_at 闭区间下界。
 * 分类 / 标签只作用于文化结果（句子表没有这两个字段），生效时句子区整块隐藏。
 * ===================================================================================== */

/** 单条件移除（chip 上的 ×） */
function removeFilter(key) {
  applyFilters({ [key]: key === 'range' ? 'all' : '' })
}

/** 清空全部筛选条件（保留关键词） */
function clearFilters() {
  applyFilters({ categoryId: '', tagId: '', range: 'all' })
}

/**
 * 把筛选条件写进 URL query（router.replace，便于分享/回退；不污染前进后退历史）。
 * 空条件不写进 query，URL 保持干净：/search?keyword=x&categoryId=1&tagId=2&range=week
 */
function applyFilters(patch) {
  const next = {
    categoryId: filterCategoryId.value,
    tagId: filterTagId.value,
    range: filterRange.value,
    ...patch
  }
  const query = {}
  if (keyword.value) query.keyword = keyword.value
  if (next.categoryId) query.categoryId = String(next.categoryId)
  if (next.tagId) query.tagId = String(next.tagId)
  if (next.range && next.range !== 'all') query.range = String(next.range)
  // 路径固定 /search：没有关键词时也保留筛选条件（用户可以先选条件再输入关键词）
  router.replace({ path: '/search', query })
}

/** 从 URL query 恢复筛选状态（非法值忽略），作为「筛选条件」的唯一数据源 */
function syncFromQuery() {
  const q = route.query
  keyword.value = q.keyword == null ? '' : String(q.keyword).trim()
  filterCategoryId.value = q.categoryId == null ? '' : String(q.categoryId)
  filterTagId.value = q.tagId == null ? '' : String(q.tagId)
  const range = q.range == null ? 'all' : String(q.range)
  filterRange.value = RANGE_VALUES.indexOf(range) >= 0 ? range : 'all'
}

/**
 * 标签下拉数据：只需要标签本身（名称 + id）。
 * 以前这里还要拿「该标签下的文化 id 集合」做前端过滤，现在 tagId 直接交给后端，
 * 反查那一步已删除（少一次请求，也不再受候选池 50 条限制）。
 */
async function loadFilterOptions() {
  try {
    const list = await getCategorys()
    if (disposed) return
    categorys.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (disposed) return
    categorys.value = []
    console.warn('[SearchView] 分类列表加载失败（分类筛选不可用）：', e && e.message)
  }
  try {
    const list = await getTags()
    if (disposed) return
    tags.value = Array.isArray(list) ? list : []
  } catch (e) {
    if (disposed) return
    tags.value = []
    console.warn('[SearchView] 标签列表加载失败（标签筛选不可用）：', e && e.message)
  }
}

/**
 * 拆分后端下发的 highlight（**已转义 + 只含 <em> 的安全 HTML**，直接 v-html，不要再转义）：
 *   · 名称命中   → 首段是名称片段（可能带 " · 描述片段"）：首段做标题，其余做描述摘要；
 *   · 仅描述命中 → 整体做描述摘要，标题回退到原始 cultureName。
 */
function splitHighlight(c) {
  const raw = String((c && c.highlight) || '')
  if (!raw) return { titleHtml: '', descHtml: '' }
  const sep = raw.indexOf(' · ')
  const first = sep < 0 ? raw : raw.slice(0, sep)
  const name = String((c && c.cultureName) || '').trim()
  // 去掉 <em> 与常见实体后与原始名称比对，判断首段是不是「名称高亮片段」
  const plain = first.replace(/<\/?em>/gi, '')
    .replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"').replace(/&#39;/gi, "'")
    .replace(/&amp;/gi, '&')
    .trim()
  if (name && plain === name) {
    return { titleHtml: first, descHtml: sep < 0 ? '' : raw.slice(sep + 3) }
  }
  return { titleHtml: '', descHtml: raw }
}

/** 热门搜索词（匿名可访问；失败只降级为不显示，不打扰用户） */
async function loadHot() {
  try {
    const list = await searchHot({ limit: TOP_LIMIT })
    if (disposed) return
    hotKeywords.value = (Array.isArray(list) ? list : [])
      .filter(item => item && item.keyword)
      .map(item => ({ keyword: String(item.keyword), searchCount: item.searchCount }))
  } catch (e) {
    if (disposed) return
    hotKeywords.value = []
    console.warn('[SearchView] 热门搜索加载失败（已忽略）：', e && e.message)
  }
}

/**
 * 最近搜索：**仅登录用户请求**。
 * /search/history 未登录会 401，而 http 拦截器对 401 会清 Token 并跳登录页，
 * 所以未登录时直接不请求（优雅降级为不显示），任何失败也只静默忽略、不弹错误提示。
 */
async function loadHistory() {
  if (!user.isLogin) {
    historyKeywords.value = []
    return
  }
  try {
    const list = await searchHistory({ limit: TOP_LIMIT })
    if (disposed) return
    historyKeywords.value = (Array.isArray(list) ? list : []).map(k => String(k)).filter(Boolean)
  } catch (e) {
    if (disposed) return
    historyKeywords.value = []
    console.warn('[SearchView] 搜索历史加载失败（已忽略）：', e && e.message)
  }
}

/** 点击热门词 / 历史词：写回输入框并立即搜索 */
function searchKeyword(kw) {
  const next = String(kw == null ? '' : kw).trim()
  if (!next) return
  keywordInput.value = next
  submit()
}

/** 对应原模板 [[ ${#dates.format(sentence.createTime, 'y-M-d')} ]] */
function formatYmd(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/**
 * 检索：把关键词 + 三个筛选条件一起发给 /api/search，由**服务端**过滤。
 *   · categoryId / tagId 只在文化侧生效（后端行为），句子侧由后端忽略；
 *     选中分类/标签时句子区整体隐藏（showSentences），所以不会出现「句子没被过滤」的错位感；
 *   · startTime 由时间下拉换算成 `yyyy-MM-dd`；
 *   · 不再放大 pageSize：服务端筛选后总数与列表口径一致，第一页 12 条就是真实的第 1 页。
 */
async function fetchResults(kw) {
  const id = ++reqId
  if (!kw) {
    cultures.value = []
    sentences.value = []
    totalCulture.value = 0
    totalSentence.value = 0
    loading.value = false
    return
  }
  loading.value = true
  try {
    const data = await searchFiltered({
      keyword: kw,
      page: 1,
      pageSize: PAGE_SIZE,
      type: 'all',
      categoryId: filterCategoryId.value,
      tagId: filterTagId.value,
      startTime: rangeStartDate()
    })
    if (disposed || id !== reqId) return
    // 文化结果带上拆分好的高亮片段（标题 / 描述），句子结果结构不变
    cultures.value = ((data && data.cultures) || []).map(c => Object.assign({}, c, splitHighlight(c)))
    sentences.value = (data && data.sentences) || []
    totalCulture.value = (data && data.totalCulture) || 0
    totalSentence.value = (data && data.totalSentence) || 0
    // 后端会为登录用户记录本次搜索历史，搜完刷新一次「最近搜索」
    if (user.isLogin) loadHistory()
  } catch (e) {
    if (disposed || id !== reqId) return
    cultures.value = []
    sentences.value = []
    totalCulture.value = 0
    totalSentence.value = 0
    console.warn('[SearchView] 搜索失败：', e && e.message)
  } finally {
    if (!disposed && id === reqId) loading.value = false
  }
}

/** 提交：把关键词同步进 URL（replace，不污染后退历史）后重新检索；保留已选筛选条件 */
function submit() {
  const kw = keywordInput.value.trim()
  const current = keyword.value
  if (kw === current) {
    // URL 没变化 -> watch 不会触发，这里直接重查一次
    fetchResults(kw)
    return
  }
  const query = {}
  if (kw) query.keyword = kw
  if (filterCategoryId.value) query.categoryId = filterCategoryId.value
  if (filterTagId.value) query.tagId = filterTagId.value
  if (filterRange.value !== 'all') query.range = filterRange.value
  router.replace({ path: '/search', query })
}

/**
 * URL 是唯一数据源：关键词与筛选条件都从 query 恢复，
 * 首次进入 / 浏览器前进后退 / 筛选变化 都会经由这里重新检索。
 * （依赖项里把 4 个 query 拼成一个字符串，避免同一 tick 内多次 replace 触发多次请求）
 */
watch(
  () => [route.query.keyword, route.query.categoryId, route.query.tagId, route.query.range]
    .map(v => (v == null ? '' : String(v))).join('\u0000'),
  () => {
    syncFromQuery()
    if (keyword.value !== keywordInput.value) keywordInput.value = keyword.value
    fetchResults(keyword.value)
  },
  { immediate: true }
)

// 样式按需加载（与 CultureListView / SentenceView 的写法一致，避免首屏无卡片样式）
PAGE_STYLES.forEach(href => { loadStyle(href).catch(() => {}) })

// 热门搜索（匿名可访问）+ 最近搜索（仅登录用户）+ 筛选下拉数据
onMounted(() => {
  loadHot()
  loadHistory()
  loadFilterOptions()
})

onBeforeUnmount(() => {
  disposed = true
})
</script>

<style scoped>
/* 搜索结果高亮：后端 highlight 已转义，只补一条小样式让 <em> 更醒目
   （v-html 生成的节点没有 scoped 属性，必须用 :deep）。
   句子卡片与页面其它部分不受影响。 */
.sh-hit {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #9E9E9E;
  line-height: 1.7;
  word-break: break-word;
  /* 高亮片段最长 200 字：两行截断，避免把卡片撑得很高 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.sh-hit :deep(em),
.item-entry h5 :deep(em) {
  font-style: normal;
  color: #E0533D;
  font-weight: 600;
}

/* ===================== 组合筛选 + 空状态（全部 scoped，只作用于本页新增容器） ===================== */
.sf {
  margin-bottom: 14px;
  padding: 14px 15px 10px;
  background: #fff;
  border: 1px solid #f0eae1;
  border-radius: 6px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, .03);
}
.sf__bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}
.sf__field { display: flex; align-items: center; gap: 6px; }
.sf__label { font-size: 13px; color: #777; white-space: nowrap; }
.sf__select {
  height: 32px;
  min-width: 120px;
  padding: 0 8px;
  font-size: 13px;
  color: #555;
  background: #fff;
  border: 1px solid #e4ddd3;
  border-radius: 4px;
  outline: none;
}
.sf__select:focus { border-color: #E0533D; }
.sf__clear {
  margin-left: auto;
  padding: 5px 14px;
  font-size: 13px;
  color: #E0533D;
  background: #fdf1ee;
  border: 1px solid #f6d5cd;
  border-radius: 999px;
  cursor: pointer;
}
.sf__clear:hover { color: #fff; background: #E0533D; border-color: #E0533D; }
.sf__chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.sf__chips-label { font-size: 12px; color: #9E9E9E; }
.sf__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px 2px 10px;
  font-size: 12px;
  color: #6b5b48;
  background: #f7f2ea;
  border: 1px solid #eee5da;
  border-radius: 999px;
}
.sf__chip-x {
  width: 16px;
  height: 16px;
  padding: 0;
  font-size: 13px;
  line-height: 14px;
  color: #a99a86;
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
}
.sf__chip-x:hover { color: #fff; background: #E0533D; }
.sf__note {
  margin: 10px 0 0;
  font-size: 12px;
  color: #b5aca1;
  line-height: 1.7;
}
.sf-loading {
  padding: 10px 15px 0;
  font-size: 13px;
  color: #9E9E9E;
}
/* 友好空状态（沿用页面既有的灰底居中口径，不改动全站样式） */
.sf-empty {
  padding: 60px 15px 70px;
  text-align: center;
  color: #9E9E9E;
}
.sf-empty__icon {
  font-size: 30px;
  color: #d8cfc4;
  letter-spacing: 2px;
}
.sf-empty__title {
  margin: 14px 0 6px;
  font-size: 16px;
  color: #7d7469;
}
.sf-empty__hint { margin: 0; font-size: 13px; line-height: 1.9; }
.sf-empty__hint a { color: #E0533D; text-decoration: none; }
.sf-empty__hint a:hover { text-decoration: underline; }
</style>
