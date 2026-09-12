<template>
  <div class="container-fluid ad-page">
    <AdminPageHeader
      :icon="tab === 'op' ? 'mdi mdi-history' : 'mdi mdi-alert-octagon-outline'"
      :title="tab === 'op' ? '操作日志' : '前端错误'"
      :desc="tab === 'op'
        ? '记录后台的写操作（新增 / 修改 / 删除 / 审核），用于追溯「谁在什么时候改了什么」；只读，不提供删除入口。'
        : '浏览器端未捕获异常 / 未处理 Promise 拒绝的上报（免登录上报，后端最多保留最近 200 条）；只读看板。'"
    >
      <template #actions>
        <template v-if="tab === 'op'">
          <select v-model="module" class="form-control input-sm ad-select" @change="reload">
            <option value="">全部模块</option>
            <option value="culture">文化</option>
            <option value="category">分类</option>
            <option value="announcement">公告</option>
            <option value="sentence">句子</option>
            <option value="user">用户</option>
            <option value="mail">邮件</option>
            <option value="tag">标签</option>
            <option value="comment">评论</option>
          </select>
          <input v-model.trim="keyword" class="form-control input-sm ad-input" placeholder="操作人 / 摘要关键词"
                 @keyup.enter="reload" />
          <button class="btn btn-primary btn-sm" @click="reload"><i class="mdi mdi-magnify"></i> 查询</button>
        </template>
        <template v-else>
          <select v-model.number="errLimit" class="form-control input-sm ad-select" @change="loadErrors">
            <option :value="10">显示 10 条</option>
            <option :value="50">显示 50 条</option>
            <option :value="100">显示 100 条</option>
          </select>
          <button class="btn btn-primary btn-sm" :disabled="errLoading" @click="loadErrors">
            <i class="mdi mdi-refresh"></i> 刷新
          </button>
        </template>
      </template>
    </AdminPageHeader>

    <!-- 标签页切换：本地 ref，不动路由（与回收站页的「内容类型」切换同一套写法） -->
    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-view-dashboard-outline"></i> 日志类型</h5>
        <div class="ad-card__actions">
          <button type="button" class="btn btn-sm" :class="tab === 'op' ? 'btn-primary' : 'btn-default'"
                  @click="switchTab('op')">
            <i class="mdi mdi-history"></i> 操作日志
          </button>
          <button type="button" class="btn btn-sm" :class="tab === 'err' ? 'btn-primary' : 'btn-default'"
                  @click="switchTab('err')">
            <i class="mdi mdi-alert-octagon-outline"></i> 前端错误
            <span v-if="errLoaded" class="ad-badge" :class="tab === 'err' ? 'ad-badge--primary' : ''">{{ errTotal }}</span>
          </button>
        </div>
      </div>
    </div>

    <!-- ==================== 操作日志 ==================== -->
    <div class="ad-card" v-if="tab === 'op'">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-format-list-bulleted"></i> 日志明细</h5>
        <div class="ad-card__actions">
          <span class="ad-hint">共 {{ total }} 条 · 第 {{ page }} / {{ maxPage }} 页</span>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th style="width:140px;">时间</th>
                <th style="width:110px;">操作人</th>
                <th style="width:90px;">模块</th>
                <th style="width:80px;">动作</th>
                <th>摘要</th>
                <th style="width:90px;">结果</th>
                <th style="width:80px;">耗时</th>
                <th style="width:130px;">IP</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="8"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
              </tr>
              <tr v-else-if="!rows.length">
                <td colspan="8">
                  <AdminEmpty icon="mdi mdi-clipboard-text"
                              text="暂无日志，调整模块或关键词后再查一次" />
                </td>
              </tr>
              <tr v-for="row in rows" :key="row.id">
                <td class="ad-num">{{ row.createTime }}</td>
                <td>{{ row.username || '-' }}</td>
                <td>{{ row.module || '-' }}</td>
                <td>{{ actionText(row.action) }}</td>
                <td class="ad-clip ad-clip--wide">{{ row.detail || ('#' + (row.targetId || '')) }}</td>
                <td>
                  <span v-if="row.success === 1" class="ad-badge ad-badge--success">成功</span>
                  <span v-else class="ad-badge ad-badge--danger">失败</span>
                </td>
                <td class="ad-num">{{ row.costMs != null ? row.costMs + 'ms' : '-' }}</td>
                <td class="ad-num">{{ row.ip || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">共 {{ total }} 条记录</span>
          <span class="ad-toolbar__spacer"></span>
          <ul class="pagination pagination-sm ad-pager">
            <li :class="{ disabled: page <= 1 }"><a href="javascript:void(0)" @click="go(page - 1)">上一页</a></li>
            <li class="active"><a href="javascript:void(0)">{{ page }} / {{ maxPage }}</a></li>
            <li :class="{ disabled: page >= maxPage }"><a href="javascript:void(0)" @click="go(page + 1)">下一页</a></li>
          </ul>
        </div>
      </div>
    </div>

    <!-- ==================== 前端错误 ==================== -->
    <div class="ad-card" v-else>
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-alert-octagon-outline"></i> 最近的前端错误</h5>
        <div class="ad-card__actions">
          <span class="ad-hint">累计上报 {{ errTotal }} 次 · 当前显示 {{ errRows.length }} 条</span>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th style="width:150px;">时间</th>
                <th style="width:130px;">来源 IP</th>
                <th style="width:200px;">页面</th>
                <th>错误信息</th>
                <th style="width:180px;">来源位置</th>
                <th style="width:180px;">UA</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="errLoading">
                <td colspan="6"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
              </tr>
              <tr v-else-if="!errRows.length">
                <td colspan="6">
                  <AdminEmpty icon="mdi mdi-emoticon-happy-outline"
                              text="暂无前端错误上报（没消息就是好消息）" />
                </td>
              </tr>
              <tr v-for="(row, index) in errRows" :key="index">
                <td class="ad-num">{{ row.at || '-' }}</td>
                <td class="ad-num">{{ row.ip || '-' }}</td>
                <td class="ad-clip" :title="row.url || ''">{{ row.url || '-' }}</td>
                <td class="ad-clip ad-clip--wide" :title="row.msg || row.raw || ''">{{ row.msg || row.raw || '-' }}</td>
                <td class="ad-clip" :title="row.source || ''">{{ row.source || '-' }}</td>
                <td class="ad-clip" :title="row.ua || ''">{{ row.ua || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { clientErrors, opLogList } from '@/api/admin'
import { toast as dsToast } from '@/utils/notify'

/**
 * 日志看板：操作日志（/api/admin/log/list）+ 前端错误（/api/admin/client-errors）。
 * 两个标签页用本地 ref 切换（不新增路由）；排版统一到 src/styles/admin-ui.css 的 ad-* 规范。
 */
/** 当前标签页：'op' 操作日志 / 'err' 前端错误 */
const tab = ref('op')

/** 组件已卸载：卸载后不再回写任何状态 */
let disposed = false

/* ==================== 操作日志 ==================== */

const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const module = ref('')
const keyword = ref('')
const loading = ref(true)

const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

const ACTION_TEXT = { save: '保存', delete: '删除', audit: '审核', upload: '上传', login: '登录' }
function actionText(a) { return ACTION_TEXT[a] || a || '-' }

async function load() {
  loading.value = true
  try {
    const data = await opLogList({ page: page.value, pageSize, module: module.value, keyword: keyword.value })
    if (disposed) return
    rows.value = (data && data.rows) || []
    total.value = (data && data.total) || 0
  } catch (e) {
    if (disposed) return
    rows.value = []
    total.value = 0
  } finally {
    if (!disposed) loading.value = false
  }
}

function reload() {
  page.value = 1
  load()
}

function go(p) {
  if (p < 1 || p > maxPage.value) return
  page.value = p
  load()
}

/* ==================== 前端错误（Batch6） ==================== */

const errRows = ref([])
const errTotal = ref(0)
const errLimit = ref(50)
const errLoading = ref(false)
/** 是否已经拉取过一次（用于标签上的角标：没拉过就不显示数字，避免误报 0） */
const errLoaded = ref(false)

/**
 * rows 里每项是后端存的 JSON 字符串（{"at","ip","url","msg","source","ua"}）。
 * 解析失败（或已是对象）时原样保留，用 raw 展示，绝不因为一条脏数据让整页报错。
 */
function parseClientError(item) {
  if (item && typeof item === 'object') return item
  const text = item == null ? '' : String(item)
  try {
    const obj = JSON.parse(text)
    if (obj && typeof obj === 'object') return obj
  } catch (e) {
    /* 解析失败：原样展示 */
  }
  return { raw: text }
}

async function loadErrors() {
  errLoading.value = true
  try {
    const data = await clientErrors({ limit: errLimit.value })
    if (disposed) return
    const list = (data && data.rows) || []
    errRows.value = list.map(parseClientError)
    errTotal.value = (data && data.total) || 0
    errLoaded.value = true
  } catch (e) {
    if (disposed) return
    errRows.value = []
    errTotal.value = 0
    dsToast.error((e && e.message) || '前端错误加载失败')
  } finally {
    if (!disposed) errLoading.value = false
  }
}

/** 切换标签页：第一次进「前端错误」时按需拉取（不给用户没点开的标签页发请求） */
function switchTab(next) {
  if (tab.value === next) return
  tab.value = next
  if (next === 'err' && !errLoaded.value) loadErrors()
}

onMounted(load)

onBeforeUnmount(() => {
  disposed = true
})
</script>

<style scoped>
/* 统一的窄筛选控件宽度（替代散落的 inline style="display:inline-block;width:140px"） */
.ad-select { width: 150px; }
.ad-input { width: 200px; }
.ad-header__actions .ad-select,
.ad-header__actions .ad-input { width: 180px; }

/* 摘要 / URL / 错误信息 / UA 列：过长时换行截断，避免把表格撑破（悬浮可看完整值） */
.ad-clip { max-width: 190px; word-break: break-all; }
/* 错误信息列最需要空间 */
.ad-clip--wide { max-width: 380px; }

.ad-pager { margin: 0; }
</style>
