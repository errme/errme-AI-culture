<template>
  <div class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-comment-check-outline"
      title="评论审核"
      desc="前台留言默认「待审核」，通过后才会展示；内容已做 HTML 白名单清洗（脚本、事件属性、危险协议都会被剔除）。"
    >
      <template #actions>
        <select v-model.number="status" class="form-control input-sm ad-select" @change="reload">
          <option :value="-1">全部状态</option>
          <option :value="0">待审核</option>
          <option :value="1">已通过</option>
          <option :value="2">已拒绝</option>
        </select>
        <input v-model.trim="keyword" class="form-control input-sm ad-input" placeholder="内容/昵称关键词"
               @keyup.enter="reload" />
        <button class="btn btn-primary btn-sm" @click="reload"><i class="mdi mdi-magnify"></i> 查询</button>
      </template>
    </AdminPageHeader>

    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title">
          <i class="mdi mdi-format-list-bulleted"></i> 评论列表
          <span class="ad-card__sub">待审核 {{ pending }} 条</span>
        </h5>
        <div class="ad-card__actions">
          <span class="ad-hint">已选 {{ selectedIds.length }} 条</span>
          <button class="btn btn-success btn-sm" :disabled="!selectedIds.length" @click="batchAudit(1)">
            <i class="mdi mdi-check"></i> 批量通过
          </button>
          <button class="btn btn-warning btn-sm" :disabled="!selectedIds.length" @click="batchAudit(2)">
            <i class="mdi mdi-close"></i> 批量拒绝
          </button>
          <button class="btn btn-info btn-sm" :disabled="!selectedIds.length" @click="batchRecheck">
            <i class="mdi mdi-filter-variant"></i> 重新过词表
          </button>
          <button class="btn btn-danger btn-sm" :disabled="!selectedIds.length" @click="batchRemove">
            <i class="mdi mdi-delete"></i> 批量删除
          </button>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th style="width:44px;">
                  <input type="checkbox" :checked="allChecked" :disabled="!rows.length" @change="toggleAll" title="全选本页">
                </th>
                <th style="width:60px;">ID</th>
                <th style="width:150px;">所属文化</th>
                <th style="width:110px;">昵称</th>
                <th>内容</th>
                <th style="width:140px;">时间</th>
                <th style="width:90px;">状态</th>
                <th style="width:210px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="8"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
              </tr>
              <tr v-else-if="!rows.length">
                <td colspan="8">
                  <AdminEmpty icon="mdi mdi-comment-outline"
                              text="暂无评论，调整状态或关键词后再查一次" />
                </td>
              </tr>
              <tr v-for="row in rows" :key="row.id">
                <td><input type="checkbox" :value="row.id" v-model="selectedIds"></td>
                <td class="ad-num">{{ row.id }}</td>
                <td>
                  <a :href="'/culture/' + row.cultureId" target="_blank" class="text-primary">
                    {{ row.cultureName || ('#' + row.cultureId) }}
                  </a>
                </td>
                <td>{{ row.nickname || '匿名' }}</td>
                <!-- 内容已在服务端白名单清洗，这里安全预览 -->
                <td class="ad-clip" v-html="row.content"></td>
                <td class="ad-num">{{ row.createTime }}</td>
                <td>
                  <span v-if="row.status === 0" class="ad-badge ad-badge--warning">待审</span>
                  <span v-else-if="row.status === 1" class="ad-badge ad-badge--success">已通过</span>
                  <span v-else class="ad-badge">已拒绝</span>
                </td>
                <td class="ad-actions">
                  <button v-if="row.status !== 1" class="btn btn-success btn-xs" @click="audit(row, 1)">通过</button>
                  <button v-if="row.status !== 2" class="btn btn-warning btn-xs" @click="audit(row, 2)">拒绝</button>
                  <button class="btn btn-danger btn-xs" @click="remove(row)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">共 {{ total }} 条 · 第 {{ page }} / {{ maxPage }} 页</span>
          <span class="ad-toolbar__spacer"></span>
          <ul class="pagination pagination-sm ad-pager">
            <li :class="{ disabled: page <= 1 }"><a href="javascript:void(0)" @click="go(page - 1)">上一页</a></li>
            <li class="active"><a href="javascript:void(0)">{{ page }} / {{ maxPage }}</a></li>
            <li :class="{ disabled: page >= maxPage }"><a href="javascript:void(0)" @click="go(page + 1)">下一页</a></li>
          </ul>
        </div>
      </div>
    </div>

    <!--
      敏感词表（独立卡片，与上面的评论列表分区）。
      命中处理：1=直接拒绝（评论不入库） 2=转待审核（入库但强制待审）；停用的词不参与匹配。
    -->
    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title">
          <i class="mdi mdi-filter-variant"></i> 敏感词表
          <span class="ad-card__sub">词表变更后约 60 秒内生效（服务端有缓存），可用上方「重新过词表」立即复查评论</span>
        </h5>
        <div class="ad-card__actions">
          <span class="ad-hint">已选 {{ sensitiveSelectedIds.length }} 个</span>
          <button class="btn btn-danger btn-sm" :disabled="!sensitiveSelectedIds.length" @click="sensitiveBatchRemove">
            <i class="mdi mdi-delete"></i> 批量删除
          </button>
          <input v-model.trim="sensitiveKeyword" class="form-control input-sm ad-input"
                 placeholder="词 / 备注关键词" @keyup.enter="reloadSensitive" />
          <button class="btn btn-primary btn-sm" @click="reloadSensitive">
            <i class="mdi mdi-magnify"></i> 查询
          </button>
          <button class="btn btn-success btn-sm" @click="startSensitiveAdd">
            <i class="mdi mdi-plus"></i> 新增敏感词
          </button>
        </div>
      </div>

      <!-- 新增 / 编辑（行内表单，不弹窗：词表通常要连续录多条） -->
      <div class="ad-card__body" v-if="sensitiveForm.show">
        <p class="ad-section-title">
          {{ sensitiveForm.id ? ('编辑敏感词 #' + sensitiveForm.id) : '新增敏感词' }}
        </p>
        <div class="ad-form-grid">
          <div class="form-group">
            <label class="control-label">敏感词：<span class="text-danger">*</span></label>
            <input v-model.trim="sensitiveForm.word" class="form-control" placeholder="最多 100 字，不可与已有词重复"
                   @keyup.enter="saveSensitive" />
          </div>
          <div class="form-group">
            <label class="control-label">命中处理：</label>
            <select v-model.number="sensitiveForm.action" class="form-control">
              <option :value="1">直接拒绝（评论不入库）</option>
              <option :value="2">转待审核（入库后待审）</option>
            </select>
          </div>
          <div class="form-group">
            <label class="control-label">启用：</label>
            <div class="checkbox">
              <label><input type="checkbox" v-model="sensitiveForm.enabled" /> 启用（停用的词不参与匹配）</label>
            </div>
          </div>
          <div class="form-group">
            <label class="control-label">备注：</label>
            <input v-model.trim="sensitiveForm.remark" class="form-control" placeholder="可选，说明为什么加这个词" />
          </div>
        </div>
        <div class="ad-toolbar">
          <button class="btn btn-primary btn-sm" :disabled="sensitiveSaving" @click="saveSensitive">
            <i class="mdi mdi-content-save"></i> 保存
          </button>
          <button class="btn btn-default btn-sm" @click="cancelSensitiveForm">取消</button>
          <span class="ad-help">判定方式：HTML 清洗后的纯文本 + 大小写不敏感包含匹配。</span>
        </div>
      </div>

      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th style="width:44px;">
                  <input type="checkbox" :checked="sensitiveAllChecked" :disabled="!sensitiveRows.length"
                         @change="toggleAllSensitive" title="全选本页">
                </th>
                <th style="width:70px;">ID</th>
                <th style="width:200px;">敏感词</th>
                <th style="width:170px;">命中处理</th>
                <th style="width:90px;">启用</th>
                <th>备注</th>
                <th style="width:160px;">创建时间</th>
                <th style="width:130px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="sensitiveLoading">
                <td colspan="8"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
              </tr>
              <tr v-else-if="!sensitiveRows.length">
                <td colspan="8">
                  <AdminEmpty icon="mdi mdi-filter-outline"
                              text="暂无敏感词，点右上角「新增敏感词」添加第一个" />
                </td>
              </tr>
              <tr v-for="row in sensitiveRows" :key="row.id">
                <td><input type="checkbox" :value="row.id" v-model="sensitiveSelectedIds"></td>
                <td class="ad-num">{{ row.id }}</td>
                <td><strong>{{ row.word }}</strong></td>
                <td>
                  <span v-if="row.action === 1" class="ad-badge ad-badge--danger">直接拒绝</span>
                  <span v-else class="ad-badge ad-badge--warning">转待审核</span>
                </td>
                <td>
                  <input type="checkbox" :checked="row.enabled === 1"
                         @change="toggleSensitive(row, $event.target.checked)" title="启用/停用">
                </td>
                <td class="ad-clip" :title="row.remark || ''">{{ row.remark || '-' }}</td>
                <td class="ad-num">{{ formatTime(row.createTime) }}</td>
                <td class="ad-actions">
                  <button class="btn btn-primary btn-xs" @click="startSensitiveEdit(row)">编辑</button>
                  <button class="btn btn-danger btn-xs" @click="removeSensitive(row)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">共 {{ sensitiveTotal }} 个词 · 第 {{ sensitivePage }} / {{ sensitiveMaxPage }} 页</span>
          <span class="ad-toolbar__spacer"></span>
          <ul class="pagination pagination-sm ad-pager">
            <li :class="{ disabled: sensitivePage <= 1 }">
              <a href="javascript:void(0)" @click="goSensitive(sensitivePage - 1)">上一页</a>
            </li>
            <li class="active"><a href="javascript:void(0)">{{ sensitivePage }} / {{ sensitiveMaxPage }}</a></li>
            <li :class="{ disabled: sensitivePage >= sensitiveMaxPage }">
              <a href="javascript:void(0)" @click="goSensitive(sensitivePage + 1)">下一页</a>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { commentAudit, commentBatchAudit, commentBatchDelete, commentDelete, commentList, commentPendingCount, commentRecheck, commentRestore, sensitiveBatchDelete, sensitiveDelete, sensitiveList, sensitiveSave } from '@/api/admin'
import { toast as dsToast, confirmDelete, confirmDialog } from '@/utils/notify'

/**
 * 评论审核（对应后端 /api/admin/comment/* 与 /api/admin/sensitive/*）
 * 排版统一到 src/styles/admin-ui.css 的 ad-* 规范（页头 / 卡片 / 表格 / 空状态 / 分页脚）。
 * 页面分两块卡片：评论列表（审核/删除/重新过词表）与敏感词表（增删改/启停）。
 */
const rows = ref([])
const total = ref(0)
const pending = ref(0)
const page = ref(1)
const pageSize = 10
const status = ref(0)      // 默认看待审核
const keyword = ref('')
const loading = ref(true)

/** 组件已卸载：卸载后不再回写任何状态（沿用页面既有的 disposed 模式） */
let disposed = false

const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

/** 顶部轻提示（统一交互，type: green/red/orange） */
function toast(msg, type = 'green') {
  const fn = type === 'red' ? dsToast.error : type === 'orange' ? dsToast.warning : dsToast.success
  fn(msg)
}

/** 时间展示：兼容 ISO 串（2024-01-01T10:00:00.000+08:00）与已格式化的 'yyyy-MM-dd HH:mm:ss' */
function formatTime(value) {
  if (!value) return '-'
  const s = String(value)
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(s)) return s.slice(0, 19)
  const d = new Date(s)
  if (isNaN(d.getTime())) return s
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function load() {
  loading.value = true
  selectedIds.value = []
  try {
    const data = await commentList({ page: page.value, pageSize, status: status.value, keyword: keyword.value })
    if (disposed) return
    rows.value = (data && data.rows) || []
    total.value = (data && data.total) || 0
    const p = await commentPendingCount()
    if (disposed) return
    pending.value = (p && p.pending) || 0
  } catch (e) {
    if (disposed) return
    rows.value = []
    total.value = 0
    toast(e.message || '加载失败', 'red')
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

async function audit(row, next) {
  try {
    await commentAudit(row.id, next)
    if (disposed) return
    toast(next === 1 ? '已通过该评论' : '已拒绝该评论')
    load()
  } catch (e) {
    if (disposed) return
    toast(e.message || '操作失败', 'red')
  }
}

function remove(row) {
  const excerpt = (row.content || '').length > 16 ? row.content.slice(0, 16) + '…' : (row.content || '')
  confirmDelete({
    target: '这条评论',
    name: excerpt,
    extra: '删除后该评论立即从前台文化详情页消失，且不可恢复。',
    onConfirm: async () => {
      await commentDelete(row.id)
      load()
      dsToast.withUndo('评论已删除', async () => {
        await commentRestore({ id: row.id })
        load()
        dsToast.success('评论已恢复')
      })
    }
  })
}

/* ==================== 批量操作（Batch4） ==================== */

/** 已勾选的评论 id */
const selectedIds = ref([])
const allChecked = computed(() => rows.value.length > 0 && selectedIds.value.length === rows.value.length)

function toggleAll(e) {
  selectedIds.value = e.target.checked ? rows.value.map(r => r.id) : []
}

async function batchAudit(next) {
  const ids = selectedIds.value.slice()
  if (!ids.length) { dsToast.warning('请先勾选要处理的评论'); return }
  // 「批量拒绝」属于会影响前台展示的破坏性操作：先走红色确认弹窗
  if (next === 2) {
    confirmDialog({
      title: '批量拒绝',
      content: `确定拒绝选中的 ${ids.length} 条评论吗？`,
      detail: '拒绝后这些评论不会展示在前台；如判断有误，可重新勾选后再点「批量通过」。',
      danger: true,
      confirmText: '确认拒绝',
      onConfirm: () => doBatchAudit(ids, next)
    })
    return
  }
  doBatchAudit(ids, next)
}

async function doBatchAudit(ids, next) {
  try {
    const res = await commentBatchAudit({ ids, status: next })
    if (disposed) return
    dsToast.success(`已${next === 1 ? '通过' : '拒绝'} ${(res && res.count) || ids.length} 条评论`)
    selectedIds.value = []
    load()
  } catch (e) {
    if (disposed) return
    dsToast.error(e.message || '批量操作失败')
  }
}

/**
 * 重新过词表：把选中的评论重新跑一遍敏感词表（只处理仍处于「待审核」的评论，
 * 命中「直接拒绝」的词会被置为已拒绝）。返回 { checked, rejected }。
 */
async function batchRecheck() {
  const ids = selectedIds.value.slice()
  if (!ids.length) { dsToast.warning('请先勾选要重新检查的评论'); return }
  confirmDialog({
    title: '重新过词表',
    content: `用当前敏感词表重新检查选中的 ${ids.length} 条评论？`,
    detail: '只检查其中仍处于「待审核」的评论；命中「直接拒绝」的词会被自动置为已拒绝。',
    confirmText: '开始检查',
    onConfirm: async () => {
      try {
        const res = await commentRecheck({ ids })
        if (disposed) return
        dsToast.success(`已检查 ${(res && res.checked) || 0} 条、拒绝 ${(res && res.rejected) || 0} 条`)
        selectedIds.value = []
        load()
      } catch (e) {
        if (disposed) return
        dsToast.error((e && e.message) || '重新过词表失败')
      }
    }
  })
}

function batchRemove() {
  const ids = selectedIds.value.slice()
  if (!ids.length) { dsToast.warning('请先勾选要删除的评论'); return }
  confirmDelete({
    target: `选中的 ${ids.length} 条评论`,
    extra: '删除后这些评论立即从前台文化详情页消失，且不可恢复。',
    onConfirm: async () => {
      const res = await commentBatchDelete({ ids })
      selectedIds.value = []
      load()
      dsToast.withUndo(`已删除 ${(res && res.count) || ids.length} 条评论`, async () => {
        await commentRestore({ ids })
        load()
        dsToast.success('已恢复 ' + ids.length + ' 条评论')
      })
    }
  })
}

/* ==================== 敏感词表（Batch6） ==================== */

const sensitiveRows = ref([])
const sensitiveTotal = ref(0)
const sensitivePage = ref(1)
const SENSITIVE_PAGE_SIZE = 10
const sensitiveKeyword = ref('')
const sensitiveLoading = ref(false)
const sensitiveSaving = ref(false)
const sensitiveSelectedIds = ref([])
/** 行内新增/编辑表单：id 为 null 表示新增 */
const sensitiveForm = ref({ show: false, id: null, word: '', action: 2, enabled: true, remark: '' })

const sensitiveMaxPage = computed(() => Math.max(1, Math.ceil(sensitiveTotal.value / SENSITIVE_PAGE_SIZE)))
const sensitiveAllChecked = computed(() =>
  sensitiveRows.value.length > 0 && sensitiveSelectedIds.value.length === sensitiveRows.value.length)

async function loadSensitive() {
  sensitiveLoading.value = true
  try {
    const data = await sensitiveList({
      keyword: sensitiveKeyword.value,
      page: sensitivePage.value,
      pageSize: SENSITIVE_PAGE_SIZE
    })
    if (disposed) return
    sensitiveRows.value = (data && data.rows) || []
    sensitiveTotal.value = (data && data.total) || 0
  } catch (e) {
    if (disposed) return
    sensitiveRows.value = []
    sensitiveTotal.value = 0
    dsToast.error((e && e.message) || '敏感词加载失败')
  } finally {
    if (!disposed) sensitiveLoading.value = false
  }
}

function reloadSensitive() {
  sensitivePage.value = 1
  sensitiveSelectedIds.value = []
  loadSensitive()
}

function goSensitive(p) {
  if (p < 1 || p > sensitiveMaxPage.value) return
  sensitivePage.value = p
  sensitiveSelectedIds.value = []
  loadSensitive()
}

function toggleAllSensitive(e) {
  sensitiveSelectedIds.value = e.target.checked ? sensitiveRows.value.map(r => r.id) : []
}

function startSensitiveAdd() {
  sensitiveForm.value = { show: true, id: null, word: '', action: 2, enabled: true, remark: '' }
}

function startSensitiveEdit(row) {
  sensitiveForm.value = {
    show: true,
    id: row.id,
    word: row.word || '',
    action: row.action === 1 ? 1 : 2,
    enabled: row.enabled !== 0,
    remark: row.remark || ''
  }
}

function cancelSensitiveForm() {
  sensitiveForm.value = { show: false, id: null, word: '', action: 2, enabled: true, remark: '' }
}

async function saveSensitive() {
  const form = sensitiveForm.value
  const word = (form.word || '').trim()
  if (!word) { dsToast.warning('敏感词不能为空'); return }
  if (word.length > 100) { dsToast.warning('敏感词长度不能超过 100 个字符'); return }
  sensitiveSaving.value = true
  try {
    const payload = {
      word,
      action: form.action === 1 ? 1 : 2,
      enabled: form.enabled ? 1 : 0,
      remark: (form.remark || '').trim() || null
    }
    if (form.id != null) payload.id = form.id
    await sensitiveSave(payload)
    if (disposed) return
    dsToast.success(form.id != null ? '已保存修改' : '已新增敏感词')
    cancelSensitiveForm()
    loadSensitive()
  } catch (e) {
    if (disposed) return
    dsToast.error((e && e.message) || '保存失败')
  } finally {
    if (!disposed) sensitiveSaving.value = false
  }
}

/** 列表里的启用开关：直接落库（save 需要完整字段，所以把该行其它字段一起带上） */
async function toggleSensitive(row, checked) {
  const next = checked ? 1 : 0
  try {
    await sensitiveSave({
      id: row.id,
      word: row.word,
      action: row.action,
      enabled: next,
      remark: row.remark
    })
    if (disposed) return
    row.enabled = next
    dsToast.success(next === 1 ? `已启用「${row.word}」` : `已停用「${row.word}」`)
  } catch (e) {
    if (disposed) return
    dsToast.error((e && e.message) || '操作失败')
    // 失败时重新拉一次当前页，把开关的视觉状态同步回服务端状态（勾选框是 :checked 单向绑定）
    loadSensitive()
  }
}

function removeSensitive(row) {
  confirmDelete({
    name: row.word,
    extra: '删除后该词不再参与评论匹配；同名词再次新增时会复用被删除的记录。',
    onConfirm: async () => {
      try {
        await sensitiveDelete(row.id)
        if (disposed) return
        dsToast.success(`已删除「${row.word}」`)
        loadSensitive()
      } catch (e) {
        if (disposed) return
        dsToast.error((e && e.message) || '删除失败')
      }
    }
  })
}

function sensitiveBatchRemove() {
  const ids = sensitiveSelectedIds.value.slice()
  if (!ids.length) { dsToast.warning('请先勾选要删除的敏感词'); return }
  confirmDelete({
    target: `选中的 ${ids.length} 个敏感词`,
    extra: '删除后这些词不再参与评论匹配；不可恢复（可重新新增同名词）。',
    onConfirm: async () => {
      try {
        const res = await sensitiveBatchDelete({ ids })
        if (disposed) return
        sensitiveSelectedIds.value = []
        dsToast.success(`已删除 ${(res && res.count) || ids.length} 个敏感词`)
        loadSensitive()
      } catch (e) {
        if (disposed) return
        dsToast.error((e && e.message) || '批量删除失败')
      }
    }
  })
}

onMounted(() => {
  load()
  loadSensitive()
})

onBeforeUnmount(() => {
  disposed = true
})
</script>

<style scoped>
/* 统一的窄筛选控件宽度（替代散落的 inline style="display:inline-block;width:130px"） */
.ad-select { width: 130px; }
.ad-input { width: 180px; }
.ad-header__actions .ad-select { width: 120px; }
.ad-header__actions .ad-input { width: 170px; }

/* 评论内容列：过长时换行截断 */
.ad-clip { max-width: 420px; word-break: break-word; }

.ad-pager { margin: 0; }
.ad-actions .btn + .btn { margin-left: 4px; }
</style>
