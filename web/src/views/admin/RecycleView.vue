<template>
  <div class="container-fluid ad-page">
    <!--
      回收站：内容被逻辑删除后进入这里，可「恢复」或「彻底删除」。
      接口：/api/admin/recycle/counts | /list | /purge（type 为单类型，必填），
            恢复复用 /api/admin/{type}/restore（{id} 或 {ids}）。
      只做前端交互，不新增全局样式；视觉统一走 ad-* 类与 admin 组件。
    -->
    <AdminPageHeader
      icon="mdi mdi-delete-restore"
      title="回收站"
      desc="这里是被删除的内容，可恢复或彻底删除；彻底删除不可撤销，相关关联也会一并清理。"
    >
      <template #actions>
        <button class="btn btn-default btn-sm" :disabled="loading" @click="refreshAll">
          <i class="mdi mdi-refresh"></i> 刷新
        </button>
      </template>
    </AdminPageHeader>

    <!-- 类型切换（单类型查询：接口 type 必填） -->
    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-filter-variant"></i> 内容类型</h5>
        <div class="ad-card__actions">
          <span class="ad-hint">全部类型合计 {{ totalAll }} 条</span>
        </div>
      </div>
      <div class="ad-card__body">
        <div class="ad-toolbar">
          <button
            v-for="t in TYPES"
            :key="t.key"
            type="button"
            class="btn btn-sm"
            :class="t.key === activeType ? 'btn-primary' : 'btn-default'"
            @click="switchType(t.key)"
          >
            <i :class="t.icon"></i> {{ t.label }}
            <span class="ad-badge" :class="t.key === activeType ? 'ad-badge--primary' : ''">{{ countOf(t.key) }}</span>
          </button>
        </div>
        <p class="ad-help">切换类型会重新查询该类型的已删除内容；恢复与彻底删除都只作用于当前选中的类型。</p>
      </div>
    </div>

    <!-- 概览 -->
    <div class="ad-tiles">
      <AdminStatTile
        label="回收站总量"
        :value="totalAll"
        icon="mdi mdi-delete-sweep-outline"
        :tone="totalAll > 0 ? 'danger' : 'success'"
        hint="全部类型已删除内容合计"
      />
      <AdminStatTile
        :label="currentTypeLabel + '数量'"
        :value="countOf(activeType)"
        icon="mdi mdi-tag-outline"
        tone="warning"
        :hint="'当前查询类型：' + currentTypeLabel"
      />
    </div>

    <!-- 列表 -->
    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title">
          <i class="mdi mdi-format-list-bulleted"></i> {{ currentTypeLabel }}列表
        </h5>
        <div class="ad-card__actions">
          <input
            v-model.trim="keyword"
            class="form-control input-sm"
            style="width: 190px;"
            placeholder="名称 / 标题关键词"
            @keyup.enter="reload"
          />
          <button class="btn btn-default btn-sm" :disabled="loading" @click="reload">
            <i class="mdi mdi-magnify"></i> 查询
          </button>
        </div>
      </div>

      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th style="width: 40px;">
                  <input
                    type="checkbox"
                    :checked="allChecked"
                    :disabled="!rows.length"
                    title="全选/取消全选"
                    @change="toggleAll"
                  />
                </th>
                <th>名称 / 标题</th>
                <th style="width: 90px;">类型</th>
                <th style="width: 155px;">删除时间</th>
                <th style="width: 155px;">创建时间</th>
                <th style="width: 200px;">备注</th>
                <th style="width: 175px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="7" class="text-center text-muted">加载中…</td>
              </tr>
              <tr v-else-if="!rows.length">
                <td colspan="7">
                  <AdminEmpty
                    icon="mdi mdi-delete-empty-outline"
                    :text="'回收站里没有' + currentTypeLabel + (keyword ? '匹配「' + keyword + '」的记录' : '，干干净净')"
                  />
                </td>
              </tr>
              <tr v-for="row in rows" :key="row.id">
                <td><input type="checkbox" :value="row.id" v-model="selectedIds" /></td>
                <td class="rec-title">{{ titleOf(row) }}</td>
                <td><span class="ad-badge">{{ currentTypeLabel }}</span></td>
                <td class="ad-num">{{ formatTime(row.deletedTime) }}</td>
                <td class="ad-num">{{ formatTime(row.createTime) }}</td>
                <td class="rec-extra">{{ row.extra || '—' }}</td>
                <td class="ad-actions">
                  <button class="btn btn-success btn-xs" :disabled="busy" @click="restoreRow(row)">
                    <i class="mdi mdi-backup-restore"></i> 恢复
                  </button>
                  <button class="btn btn-danger btn-xs" :disabled="busy" @click="purgeRow(row)">
                    <i class="mdi mdi-delete-forever"></i> 彻底删除
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">已选 {{ selectedIds.length }} 项 · 共 {{ total }} 条</span>
          <button class="btn btn-default btn-sm" :disabled="!rows.length" @click="invertSelection">反选</button>
          <button class="btn btn-success btn-sm" :disabled="!selectedIds.length || busy" @click="batchRestore">
            <i class="mdi mdi-backup-restore"></i> 批量恢复
          </button>
          <button class="btn btn-danger btn-sm" :disabled="!selectedIds.length || busy" @click="batchPurge">
            <i class="mdi mdi-delete-forever"></i> 批量彻底删除
          </button>
          <span class="ad-toolbar__spacer"></span>
          <span class="ad-hint">第 {{ page }} / {{ maxPage }} 页</span>
          <button class="btn btn-default btn-sm" :disabled="page <= 1 || loading" @click="go(page - 1)">上一页</button>
          <button class="btn btn-default btn-sm" :disabled="page >= maxPage || loading" @click="go(page + 1)">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 回收站（对应后端 /api/admin/recycle/* 与各实体的 restore 接口）
 *
 * 交互要点：
 *   · type 是**单类型必填**，所以用按钮组做单类型切换，标签上显示各类型数量。
 *   · 恢复：单行 { id }，批量 { ids }，成功后刷新列表与数量。
 *   · 彻底删除：走 confirmDelete / 危险 confirmDialog，调 recyclePurge（不可撤销）。
 *   · 分页固定 10 条/页；所有请求失败都给 dsToast.error，卸载后不再改状态（disposed）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import AdminStatTile from '@/components/admin/AdminStatTile.vue'
import {
  announcementRestore,
  categoryRestore,
  commentRestore,
  cultureRestore,
  recycleCounts,
  recycleList,
  recyclePurge,
  sentenceRestore,
  tagRestore,
  userRestore
} from '@/api/admin'
import { toast as dsToast, confirmDelete, confirmDialog } from '@/utils/notify'

/** 类型元数据：顺序即标签页顺序，key 与接口 type / counts 字段一致 */
const TYPES = [
  { key: 'culture', label: '文化', icon: 'mdi mdi-image-multiple-outline' },
  { key: 'category', label: '分类', icon: 'mdi mdi-shape-outline' },
  { key: 'tag', label: '标签', icon: 'mdi mdi-tag-multiple' },
  { key: 'announcement', label: '公告', icon: 'mdi mdi-bullhorn-outline' },
  { key: 'sentence', label: '句子', icon: 'mdi mdi-format-quote-open-outline' },
  { key: 'comment', label: '评论', icon: 'mdi mdi-comment-outline' },
  { key: 'user', label: '用户', icon: 'mdi mdi-account-outline' }
]

/** 类型 → 恢复接口（单个传 { id }，批量传 { ids }） */
const RESTORE_API = {
  culture: cultureRestore,
  category: categoryRestore,
  tag: tagRestore,
  announcement: announcementRestore,
  sentence: sentenceRestore,
  comment: commentRestore,
  user: userRestore
}

const PAGE_SIZE = 10

const counts = reactive({})
const activeType = ref('culture')
const keyword = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const selectedIds = ref([])
const loading = ref(true)
const busy = ref(false)

let disposed = false
onBeforeUnmount(() => { disposed = true })

const currentTypeLabel = computed(() => {
  const t = TYPES.find(x => x.key === activeType.value)
  return t ? t.label : activeType.value
})
const totalAll = computed(() => TYPES.reduce((sum, t) => sum + countOf(t.key), 0))
const maxPage = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const allChecked = computed(() => rows.value.length > 0 && selectedIds.value.length === rows.value.length)

function toNum(v) {
  const n = Number(v)
  return isFinite(n) ? n : 0
}
function countOf(key) {
  return toNum(counts[key])
}
function titleOf(row) {
  return row.title || row.name || ('#' + row.id)
}
/** yyyy-MM-dd HH:mm（后端时间可能是 ISO 或 'yyyy-MM-dd HH:mm:ss'） */
function formatTime(value) {
  if (!value) return '—'
  const d = new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value).slice(0, 16).replace('T', ' ')
  const p = n => (n < 10 ? '0' + n : String(n))
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
}

/* ==================== 加载 ==================== */

async function loadCounts() {
  try {
    const data = await recycleCounts()
    if (disposed) return
    TYPES.forEach(t => { counts[t.key] = toNum(data && data[t.key]) })
  } catch (e) {
    if (!disposed) dsToast.error(e.message || '回收站数量加载失败')
  }
}

async function loadList() {
  loading.value = true
  try {
    let data = await recycleList({
      type: activeType.value,
      keyword: keyword.value,
      page: page.value,
      pageSize: PAGE_SIZE
    })
    if (disposed) return
    let list = (data && data.rows) || []
    let sum = (data && data.total) || 0
    // 彻底删除/恢复后可能把最后一页清空：自动回退到最后一个有数据的页
    const lastPage = Math.max(1, Math.ceil(sum / PAGE_SIZE))
    if (!list.length && page.value > lastPage) {
      page.value = lastPage
      data = await recycleList({
        type: activeType.value,
        keyword: keyword.value,
        page: page.value,
        pageSize: PAGE_SIZE
      })
      if (disposed) return
      list = (data && data.rows) || []
      sum = (data && data.total) || 0
    }
    rows.value = list
    total.value = sum
    selectedIds.value = []
  } catch (e) {
    if (disposed) return
    rows.value = []
    total.value = 0
    selectedIds.value = []
    dsToast.error(e.message || '回收站列表加载失败')
  } finally {
    if (!disposed) loading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadCounts(), loadList()])
}

function switchType(key) {
  if (key === activeType.value) return
  activeType.value = key
  page.value = 1
  selectedIds.value = []
  loadList()
}

function reload() {
  page.value = 1
  loadList()
}

function go(p) {
  if (p < 1 || p > maxPage.value) return
  page.value = p
  loadList()
}

/* ==================== 勾选 ==================== */

function toggleAll(e) {
  selectedIds.value = e.target.checked ? rows.value.map(r => r.id) : []
}

function invertSelection() {
  const checked = selectedIds.value
  selectedIds.value = rows.value.map(r => r.id).filter(id => checked.indexOf(id) === -1)
}

/* ==================== 恢复 ==================== */

async function restoreRow(row) {
  const api = RESTORE_API[activeType.value]
  if (!api) return
  busy.value = true
  try {
    await api({ id: row.id })
    if (disposed) return
    dsToast.success('已恢复「' + titleOf(row) + '」')
    await refreshAll()
  } catch (e) {
    if (!disposed) dsToast.error(e.message || '恢复失败')
  } finally {
    if (!disposed) busy.value = false
  }
}

function batchRestore() {
  const ids = selectedIds.value.slice()
  if (!ids.length) return
  const label = currentTypeLabel.value
  confirmDialog({
    title: '确认批量恢复',
    content: '确定要恢复选中的 ' + ids.length + ' 条' + label + '吗？',
    detail: '恢复后这些内容会重新出现在「' + label + '管理」的列表中。',
    confirmText: '恢复',
    onConfirm: async () => {
      const api = RESTORE_API[activeType.value]
      if (!api) return
      try {
        await api({ ids })
        if (disposed) return
        dsToast.success('已恢复 ' + ids.length + ' 条' + label)
        await refreshAll()
      } catch (e) {
        if (!disposed) dsToast.error(e.message || '批量恢复失败')
      }
    }
  })
}

/* ==================== 彻底删除（不可撤销） ==================== */

async function purge(ids, label) {
  const res = await recyclePurge({ type: activeType.value, ids })
  if (disposed) return
  const count = (res && res.count != null) ? res.count : ids.length
  dsToast.success('已彻底删除 ' + count + ' 条' + label)
  await refreshAll()
}

function purgeRow(row) {
  const name = titleOf(row)
  confirmDelete({
    name,
    extra: '彻底删除后无法恢复，相关关联也会清理。',
    onConfirm: async () => {
      busy.value = true
      try {
        await purge([row.id], currentTypeLabel.value)
      } catch (e) {
        if (!disposed) dsToast.error(e.message || '彻底删除失败')
      } finally {
        if (!disposed) busy.value = false
      }
    }
  })
}

function batchPurge() {
  const ids = selectedIds.value.slice()
  if (!ids.length) return
  const label = currentTypeLabel.value
  confirmDialog({
    title: '确认彻底删除',
    content: '确定要彻底删除选中的 ' + ids.length + ' 条' + label + '吗？',
    detail: '彻底删除不可撤销，这些内容与相关关联都会被清理，无法再恢复。',
    danger: true,
    confirmText: '彻底删除',
    onConfirm: async () => {
      busy.value = true
      try {
        await purge(ids, label)
      } catch (e) {
        if (!disposed) dsToast.error(e.message || '批量彻底删除失败')
      } finally {
        if (!disposed) busy.value = false
      }
    }
  })
}

onMounted(refreshAll)
</script>

<style scoped>
/* 标题较长时换行而不是撑破表格 */
.rec-title {
  max-width: 320px;
  font-weight: 500;
  word-break: break-all;
}
.rec-extra {
  max-width: 220px;
  color: var(--ad-text-sub);
  font-size: 12.5px;
  word-break: break-all;
}
/* 类型按钮里的数量徽章跟着按钮走 */
.ad-toolbar .btn .ad-badge { margin-left: 4px; }
</style>
