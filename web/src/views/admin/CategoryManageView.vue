<template>
  <!--
    分类管理（移植自 frontend/templates/admin/category.html）

    只渲染原模板 <main class="coder-layout-content"> 内部内容（从 <div class="container-fluid"> 开始），
    侧边栏 / 顶栏由 AdminLayout.vue 提供。

    ★ 表格替换试点（本页已不依赖 bootstrap-table）★
      表格由 Vue 自己渲染（v-for + 计算属性排序 + 自建分页），不再调用 $('#categoryTable').bootstrapTable(...)。
      但对外可见的一切保持不变：
        · 容器 id/class：#toolbar / #categoryTable / .ad-table-wrap / .ad-card* 全部保留
          （#search 保留为新增按钮的兼容 id，但已改为 @click 直连 openAdd，不再走 data-method="search"）；
        · 弹窗 id 与表单字段：#categoryAddModal / #categoryEditModal / #categoryAddForm /
          #categoryEditForm / #e_id / #e_categoryName / name="categoryName" 原样不动；
        · data-method 分发仍用于两个弹窗的保存按钮（save / editSave）；
        · 增删改查走同一套 web/src/api/admin.js 接口，payload 结构未变；
        · 成功/失败提示仍走 dsToast / confirmDelete（以及 $.confirm 兼容层的 confirmBox）。
      仍保留 jQuery 的部分：bootstrap 3 弹窗（modal）、bootstrapValidator 表单校验，
      这些不属于「表格插件」，等弹窗组件化时再一起替换。
      排序：默认沿用后端顺序（order by id desc），点表头才做前端排序，且仅作用于当前页（表头 title 已注明）。
  -->
  <div ref="rootEl" class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-folder-multiple-outline"
      title="分类管理"
      desc="分类决定前台内容的归类与导航；删除分类会解除其下文化的分类归属（内容本身保留），且不可恢复。"
    >
      <template #actions>
        <!--
          说明：#toolbar 这个 id 原样保留（回归脚本会用它）。
          历史上这里还有个 id="search" 的按钮 + doMethod.search 分支，实际却是「新增分类」入口、
          而且读的 #q_categoryName 输入框根本不存在（后端也没有按名过滤）——已清理为明确的新增按钮：
          直接 @click 调 openAdd()，不再走 data-method 分发（id="search" 保留只为兼容旧引用）。
        -->
        <div id="toolbar" class="ad-toolbar">
          <button id="search" type="button" class="btn btn-primary btn-sm" title="新增分类" @click="openAdd()">
            <i class="mdi mdi-plus"></i> 新增分类
          </button>
        </div>
      </template>
    </AdminPageHeader>

    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-format-list-bulleted"></i> 分类列表</h5>
        <div class="ad-card__actions">
          <span class="ad-hint">共 {{ total }} 条 · 第 {{ page }} / {{ maxPage }} 页</span>
          <button type="button" class="btn btn-default btn-sm" :disabled="loading" @click="reload()">
            <i class="mdi mdi-refresh" :class="{ 'mdi-spin': loading }"></i> 刷新
          </button>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <!--
            保留 Bootstrap 3 的表格类（table / table-bordered / table-hover）保证视觉与老后台一致，
            同时叠加全局 ad-table 规范；外层仍是 .ad-table-wrap。
            v-for 直接挂在 <tr> 上（不用 <template v-else>，避免 Vue 生成 Fragment 造成补丁错位）。
          -->
          <table id="categoryTable" class="table table-bordered table-hover ad-table">
            <thead>
              <tr>
                <th style="width: 40px;" class="text-center">
                  <input
                    type="checkbox"
                    :checked="allChecked"
                    :disabled="!displayRows.length"
                    title="全选/取消全选"
                    @change="toggleAll"
                  />
                </th>
                <th style="width: 140px;" class="ad-sort" :title="sortTitle('id')" @click="toggleSort('id')">
                  分类编号 <i :class="sortIcon('id')"></i>
                </th>
                <th class="ad-sort" :title="sortTitle('categoryName')" @click="toggleSort('categoryName')">
                  分类名称 <i :class="sortIcon('categoryName')"></i>
                </th>
                <th style="width: 130px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="4" class="text-center text-muted">加载中…</td>
              </tr>
              <tr v-else-if="!displayRows.length">
                <td colspan="4">
                  <AdminEmpty icon="mdi mdi-folder-open-outline"
                              text="暂无分类，点右上角「新增分类」创建第一个分类" />
                </td>
              </tr>
              <tr v-for="row in displayRows" :key="row.id">
                <td class="text-center"><input type="checkbox" :value="row.id" v-model="selectedIds" /></td>
                <td class="ad-num">{{ row.id }}</td>
                <td>{{ row.categoryName }}</td>
                <td class="ad-actions">
                  <button type="button" class="btn btn-xs btn-default" title="编辑" @click="edit(row.id)">
                    <i class="mdi mdi-pencil"></i>
                  </button>
                  <button type="button" class="btn btn-xs btn-default" title="删除" @click="del(row.id)">
                    <i class="mdi mdi-window-close"></i>
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <!-- 分页（自建）：上一页 / 下一页 + 第 x/y 页 + 共 N 条 + 每页条数 -->
      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">共 {{ total }} 条记录{{ sortKey ? '（排序仅作用于当前页）' : '' }}</span>
          <select v-model.number="pageSize" class="form-control input-sm ad-select" @change="changePageSize">
            <option :value="10">每页 10 条</option>
            <option :value="20">每页 20 条</option>
            <option :value="50">每页 50 条</option>
          </select>
          <span class="ad-toolbar__spacer"></span>
          <ul class="pagination pagination-sm ad-pager">
            <li :class="{ disabled: page <= 1 }">
              <a href="javascript:void(0)" @click="goTo(page - 1)">上一页</a>
            </li>
            <li class="active"><a href="javascript:void(0)">{{ page }} / {{ maxPage }}</a></li>
            <li :class="{ disabled: page >= maxPage }">
              <a href="javascript:void(0)" @click="goTo(page + 1)">下一页</a>
            </li>
          </ul>
        </div>
      </div>
    </div>

    <!-- 新增表单start -->
    <div class="modal fade" id="categoryAddModal" tabindex="-1" role="dialog" aria-labelledby="categoryAddModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close"><span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="categoryAddModalLabel">新增分类</h4>
          </div>
          <form id="categoryAddForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">
              <div class="form-group">
                <label class="control-label">分类名称：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="categoryName" placeholder="例如：传统节日">
                <p class="ad-help">分类名称用于前台导航与内容归类，建议保持简短、不重复。</p>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
              <button type="button" data-method="save" class="btn btn-primary">保存分类</button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 新增表单end -->
    <!-- 修改表单start -->
    <div class="modal fade" id="categoryEditModal" tabindex="-1" role="dialog" aria-labelledby="categoryEditModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close"><span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="categoryEditModalLabel">编辑分类</h4>
          </div>
          <form id="categoryEditForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">
              <input type="hidden" id="e_id" class="form-control" name="id">
              <div class="form-group">
                <label class="control-label">分类名称：<span class="text-danger">*</span></label>
                <input type="text" id="e_categoryName" class="form-control" name="categoryName">
                <p class="ad-help">改名后前台导航与已归类内容会立即使用新名称。</p>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
              <button type="button" data-method="editSave" class="btn btn-primary">保存修改</button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 修改表单end -->
  </div>
</template>

<script setup>
/**
 * 分类管理（1:1 移植自 frontend/templates/admin/category.html）
 *
 * 表格替换试点：**本页已彻底移除 bootstrap-table**
 *   · 旧实现：$('#categoryTable').bootstrapTable({ ajax, columns, pagination ... })
 *     —— 表格 DOM（表头 / 表体 / 分页条 / 列筛选 / 刷新按钮）全部由 jQuery 插件运行时拼 HTML 字符串生成，
 *        行内操作列还用 onclick="edit(id)" 拼字符串注入（XSS 面更大 + 依赖 window.edit / window.del）。
 *   · 新实现：Vue 自己渲染 <table>（表头固定 + 表体 v-for）+ 计算属性排序 + 自建分页；
 *        行内按钮改成 @click，不再拼 HTML；数据由本组件的 load() 直接调 categoryList。
 *   接口（web/src/api/admin.js）与 payload 完全没变：
 *     categoryList({ page, pageSize }) -> { total, rows }   分页列表（后端 order by id desc）
 *     categorySave(Category)                                新增 / 修改（后端按 id 是否存在分流）
 *     categoryDelete(id) / categoryRestore({ id })          删除 / 撤销恢复
 *
 * 仍然使用 jQuery 的地方（有意保留，都不属于「表格插件」）：
 *   · bootstrap 3 弹窗 #categoryAddModal / #categoryEditModal 的显示与隐藏；
 *   · bootstrapValidator 表单校验（#categoryAddForm / #categoryEditForm）；
 *   · 根节点上收窄后的 data-method 事件分发（save / editSave 两个保存按钮）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { categoryDelete, categoryList, categoryRestore, categorySave } from '@/api/admin'
import { toast as dsToast, confirmDelete } from '@/utils/notify'

/** jQuery 由 admin.html 在 <head> 中同步加载，模块执行时已可用 */
const $ = window.jQuery

/** 组件根节点（原模板的 <div class="container-fluid">），用于收窄原模板里的全局事件绑定 */
const rootEl = ref(null)

/** 卸载标记：异步回调落地时兜底，避免操作已销毁的表格 / 弹窗 */
let disposed = false

/** 自建定时器，onBeforeUnmount 统一清理 */
const timers = new Set()
/** jconfirm 弹窗实例，onBeforeUnmount 统一关闭 */
const dialogs = new Set()

/**
 * 延时执行并登记定时器（列表刷新型操作放到下一个事件循环，
 * 避免与 bootstrap modal 的隐藏过渡抢同一帧；对用户而言与原模板的立即刷新等价）
 */
function later(fn, delay = 0) {
  const id = window.setTimeout(() => {
    timers.delete(id)
    if (!disposed) fn()
  }, delay)
  timers.add(id)
  return id
}

function clearTimers() {
  timers.forEach(id => window.clearTimeout(id))
  timers.clear()
}

/* ==================== 列表状态（Vue 表格数据源） ==================== */

/** 当前页数据（顺序 = 后端返回顺序：id 倒序） */
const rows = ref([])
/** 总条数（后端返回） */
const total = ref(0)
/** 当前页码（从 1 开始） */
const page = ref(1)
/** 每页条数 */
const pageSize = ref(10)
/** 加载中（首屏即为 true，避免闪一下空状态） */
const loading = ref(true)
/** 勾选列选中的行 id */
const selectedIds = ref([])
/** 排序字段：'' = 沿用后端默认顺序（id 倒序），'id' / 'categoryName' = 前端排序 */
const sortKey = ref('')
/** 排序方向：'asc' | 'desc' */
const sortDir = ref('desc')

const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

/** id 数值比较（缺失按 0 处理，避免 NaN 让 sort 结果不稳定） */
function numId(v) {
  const n = Number(v)
  return isFinite(n) ? n : 0
}

/**
 * 显示行 = 当前页数据 + 可选的前端排序。
 * 注意：排序只作用于「当前页」——旧实现虽然开了 sortable，但分页是服务端模式（sidePagination: 'server'），
 * 后端 CategoryMapper 也没有动态 order by，点表头同样只影响当页，因此行为等价。
 */
const displayRows = computed(() => {
  const list = rows.value
  if (!sortKey.value) return list
  const dir = sortDir.value === 'asc' ? 1 : -1
  const key = sortKey.value
  return list.slice().sort((a, b) => {
    let ret
    if (key === 'id') ret = numId(a.id) - numId(b.id)
    // 分类名按中文拼音比较；完全同名时退回 id 倒序，保证顺序稳定
    else ret = String(a.categoryName || '').localeCompare(String(b.categoryName || ''), 'zh-Hans-CN')
    if (ret === 0 && key !== 'id') ret = numId(b.id) - numId(a.id)
    return ret * dir
  })
})

/** 勾选列是否全选（只在当前页范围内判断） */
const allChecked = computed(() => displayRows.value.length > 0 && selectedIds.value.length === displayRows.value.length)

/**
 * 点表头切换排序：未点过任何表头时保持后端默认顺序（order by id desc，sortKey=''）；
 * 首次点某个表头先按「正序」（相对默认的 id 倒序一定有可见变化，不会出现「点了没反应」），
 * 再点同一个表头切换为倒序。排序只作用于当前页（表头 title 已注明）。
 */
function toggleSort(key) {
  if (sortKey.value !== key) {
    sortKey.value = key
    sortDir.value = 'asc'
    return
  }
  sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
}

function sortIcon(key) {
  if (sortKey.value !== key) return 'mdi mdi-sort-variant'
  return sortDir.value === 'asc' ? 'mdi mdi-sort-ascending' : 'mdi mdi-sort-descending'
}

/** 表头 tip：明确「点表头排序」以及「排序只作用于当前页」（避免被误认为全量排序） */
function sortTitle(key) {
  const label = key === 'id' ? '分类编号' : '分类名称'
  const tip = '（排序仅作用于当前页）'
  return sortKey.value !== key
    ? `点击按${label}排序${tip}`
    : `再点一次切换为${sortDir.value === 'asc' ? '倒序' : '正序'}${tip}`
}

/** 全选 / 取消全选（与回收站页同一套写法） */
function toggleAll(e) {
  selectedIds.value = e.target.checked ? displayRows.value.map(r => r.id) : []
}

/* ==================== 列表加载 ==================== */

/**
 * 拉取当前页。失败时清空列表并提示（旧 bootstrap-table 的 ajax error 分支只显示空表、
 * 不告诉用户原因，这里按后台统一规范补一条 dsToast）。
 */
async function load() {
  loading.value = true
  let data = null
  try {
    data = await categoryList({ page: page.value, pageSize: pageSize.value })
  } catch (err) {
    if (disposed) return
    rows.value = []
    total.value = 0
    selectedIds.value = []
    loading.value = false
    dsToast.error((err && err.message) || '分类列表加载失败')
    return
  }
  if (disposed) return

  rows.value = (data && Array.isArray(data.rows)) ? data.rows : []
  total.value = Number((data && data.total) || 0)
  // 勾选只保留仍在当前页的记录，避免勾选态被带到已经翻走的数据上
  const alive = new Set(rows.value.map(r => r.id))
  selectedIds.value = selectedIds.value.filter(id => alive.has(id))

  // 删掉末页最后一条后自动回到有效页，不停在空白页
  const target = Math.min(page.value, maxPage.value)
  if (!rows.value.length && target < page.value) {
    page.value = target
    return load()
  }
  loading.value = false
}

/** 刷新列表：原 $("#categoryTable").bootstrapTable('refresh') 的等价实现 */
function refreshList() {
  later(load)
}

/** 回到第一页并刷新 */
function reload() {
  page.value = 1
  return load()
}

/** 翻页（越界直接忽略） */
function goTo(p) {
  if (p < 1 || p > maxPage.value || p === page.value) return
  page.value = p
  load()
}

/** 切换每页条数：回到第一页重新拉取（与旧 bootstrap-table 的 pageSize 行为一致） */
function changePageSize() {
  page.value = 1
  load()
}

/** 从当前页数据里按 id 找行（替代旧的 getRowByUniqueId） */
function findRow(id) {
  return rows.value.find(r => String(r.id) === String(id)) || null
}

/* ==================== 提示 / 校验（沿用原实现） ==================== */

/** jconfirm 弹窗（原模板 $.confirm 的等价封装，文案一致） */
function confirmBox(options) {
  if (!$ || typeof $.confirm !== 'function') {
    window.alert(options.content)
    return null
  }
  const box = $.confirm(options)
  dialogs.add(box)
  return box
}

/** 表单序列化：原模板依赖全局插件 jquery.serialize-object（admin.html 未加载），这里做等价实现 */
function serializeObject(selector) {
  const data = {}
  $(selector).serializeArray().forEach(item => { data[item.name] = item.value })
  return data
}

/** bootstrapValidator 校验（沿用后台其它页面 culture.html 的调用方式；插件未就绪时放行，避免页面不可用） */
function validateForm(selector) {
  const validator = $(selector).data('bootstrapValidator')
  if (!validator) return true
  validator.validate()
  return validator.isValid()
}

/** 无权限：原模板 else if ("403" == data) 分支 */
function alertNoPermission(modalSelector) {
  dsToast.error('你无权访问');
  $(modalSelector).modal('hide');
}

/** 后端把权限错误放在 message 里（ApiResult.error(403, "无权限")），据此还原原模板的 403 分支 */
function isNoPermission(err) {
  var message = (err && err.message) || '';
  return message.indexOf('无权限') > -1 || message.indexOf('没有权限') > -1;
}

/* ==================== 行操作 ==================== */

/** 原模板 edit(id)：打开编辑弹窗并回填行数据（行数据改从 Vue 的 rows 里取，替代 getRowByUniqueId） */
function edit(id) {
  const row = findRow(id)
  if (!row) {
    dsToast.error('未找到该分类，请刷新后重试')
    return
  }
  $('#categoryEditModal').modal({
    show: true,
    backdrop: 'static'
  });
  //重置表单（只清校验状态，不清空输入值，保持原模板行为）
  var validator = $('#categoryEditForm').data('bootstrapValidator');
  if (validator) validator.resetForm();

  $("#e_id").val(row.id);
  $("#e_categoryName").val(row.categoryName);
}

/** 原模板 del(id)：删除前统一二次确认（列出该分类名与影响），确认后才调用删除接口 */
function del(id) {
  const row = findRow(id)
  confirmDelete({
    name: (row && row.categoryName) || '该分类',
    extra: '删除后该分类下的文化会失去分类归属（内容本身保留），前台分类导航不再显示，且不可恢复。',
    onConfirm: () => doDelete(id)
  })
}

function doDelete(id) {
  //发送ajax请求删除数据
  categoryDelete(id).then(function () {
    if (disposed) return
    refreshList()
    dsToast.withUndo('分类已删除', async () => {
      await categoryRestore({ id })
      if (disposed) return
      refreshList()
      dsToast.success('分类已恢复')
    })
  }).catch(function (err) {
    if (disposed) return
    confirmBox({
      title: '温馨提示',
      content: (err && err.message) || '删除失败',
      type: 'red',
      typeAnimated: true,
      buttons: {
        close: {
          text: '关闭'
        }
      }
    })
  })
}

/**
 * 打开「新增分类」弹窗（原 doMethod.add）。
 * 现在由 #toolbar 里的新增按钮直接 @click 调用，不再经过 data-method 分发。
 */
function openAdd() {
  $('#categoryAddModal').modal({
    show: true,
    backdrop: 'static'
  });
  //重置表单校验状态（原模板未声明校验，这里配合下方 bootstrapValidator 使用）
  var validator = $('#categoryAddForm').data('bootstrapValidator');
  if (validator) validator.resetForm();
}

/**
 * data-method 分发：只保留弹窗底部两个按钮（保存分类 / 保存修改）。
 * 历史上这里还有 add / search 两个分支：
 *   · add    → 已收敛为上面的 openAdd()（按钮改为 @click 直接调用）；
 *   · search → 读的是本页并不存在的 #q_categoryName（旧模板遗留），且后端 CategoryQuery
 *              没有按名称过滤字段（CategoryMapper.queryData 固定 order by id desc），
 *              等价行为就是重新拉取当前页 —— 已删除，避免留下一条「看起来能搜索」的死分支。
 * 若将来要做搜索：补 #q_categoryName 输入框 + 后端 name 过滤，再加一个 search 分支即可。
 */
const doMethod = {
  save: function () {

    //提交表单
    var formParamObj = serializeObject('#categoryAddForm');
    if (!validateForm('#categoryAddForm')) return;   //验证通过
    categorySave(formParamObj).then(function () {
      if (disposed) return
      $('#categoryAddModal').modal('hide');
      refreshList()
    }).catch(function (err) {
      if (disposed) return
      if (isNoPermission(err)) { alertNoPermission('#categoryAddModal'); return }
      confirmBox({
        title: '温馨提示',
        content: (err && err.message) || '保存失败',
        type: 'red',
        typeAnimated: true,
        buttons: {
          close: {
            text: '关闭'
          }
        }
      })
    })

  },
  editSave: function () {
    //提交表单（原模板此处为 $.ajax({async:false})，REST 版由 axios promise 承接）
    var formParamObj = serializeObject('#categoryEditForm');
    if (!validateForm('#categoryEditForm')) return;
    categorySave(formParamObj).then(function () {
      if (disposed) return
      confirmBox({
        title: '温馨提示',
        content: '修改成功',
        type: 'green',
        buttons: {
          omg: {
            text: '谢谢',
            btnClass: 'btn-green',
          }
        }
      });
      $('#categoryEditModal').modal('hide');
      refreshList()
    }).catch(function (err) {
      if (disposed) return
      if (isNoPermission(err)) { alertNoPermission('#categoryEditModal'); return }
      confirmBox({
        title: '温馨提示',
        content: (err && err.message) || '修改失败',
        type: 'red',
        typeAnimated: true,
        buttons: {
          close: {
            text: '关闭'
          }
        }
      })
    })
  }

}

/** 校验规则：原 category.html 未显式声明，这里沿用后台 culture.html 的 bootstrapValidator 写法，只校验分类名称 */
function initValidator() {
  if (!$ || !$.fn || typeof $.fn.bootstrapValidator !== 'function') return
  $('#categoryAddForm,#categoryEditForm').bootstrapValidator({
    live: 'enabled',//字段值有变化就触发验证 disabled,submitted 当点击提交时验证并展示错误信息
    message: '信息不合法',
    feedbackIcons: {
      valid: 'glyphicon glyphicon-ok',
      invalid: 'glyphicon glyphicon-remove',
      validating: 'glyphicon glyphicon-refresh'
    },
    fields: {
      categoryName: {
        message: '分类名称不合法',
        validators: {
          notEmpty: {
            message: '分类名称必须填写,不能为空'
          }
        }
      }
    }
  });
}

/** 事件绑定：原模板为 $("button,a").on('click') 全局绑定，SPA 下收窄到本组件根节点，避免影响侧边栏 / 顶栏 */
function bindActions() {
  $(rootEl.value).on('click', 'button,a', function () {
    //获取到 a标签里面配置 data-method
    var methodName = $(this).data('method');
    if (methodName && doMethod[methodName]) {
      doMethod[methodName]();
    }
  });
}

onMounted(async () => {
  // 等 DOM 渲染完成后再执行老插件（与页面插件「先渲染 DOM、再初始化」的要求一致）
  await nextTick()
  if (disposed) return

  initValidator();
  bindActions();
  load();   // 表格数据由 Vue 组件自己拉取（旧实现是 bootstrap-table 的 ajax 选项）

  // 兼容保留：旧模板行内 onclick="edit(id)"/"del(id)" 需要全局函数。
  // 本页 DOM 已改用 @click，且 grep 确认仓库内没有其它模块读这两个全局
  // （CultureManageView / AnnouncementManageView 各自挂自己的），
  // 但为了不改变对外行为（外部脚本按 id 调用），仍照旧挂载并在卸载时清理。
  window.edit = edit;
  window.del = del;
})

onBeforeUnmount(() => {
  // 清理阶段整体兜底：任何清理异常都不能中断 SPA 路由切换
  // （曾因某个页面卸载清理抛错，导致之后所有后台菜单点击都失效）
  try {
    disposed = true

    // 定时器
    clearTimers()

    // 全局事件解绑
    if (rootEl.value) $(rootEl.value).off('click')

    // 全局函数（避免污染后台其余路由）
    if (window.edit === edit) delete window.edit
    if (window.del === del) delete window.del

    // jconfirm 弹窗：close() 之后仍有动画延时，这里立即移除自建弹窗与背景层
    dialogs.forEach(box => {
      try {
        if (!box) return
        if (typeof box.close === 'function') box.close()
        if (box.$el) box.$el.remove()
        if (box.$jconfirmBg) box.$jconfirmBg.remove()
      } catch (e) { /* 已关闭 */ }
    })
    dialogs.clear()

    // bootstrapValidator
    try {
      if ($('#categoryAddForm').data('bootstrapValidator')) $('#categoryAddForm').bootstrapValidator('destroy')
      if ($('#categoryEditForm').data('bootstrapValidator')) $('#categoryEditForm').bootstrapValidator('destroy')
    } catch (e) { /* 未初始化 */ }

    // 说明：原本这里还有 bootstrapTable('destroy')；表格已改由 Vue 渲染，
    // 相关 DOM 随组件卸载自动回收，不再需要销毁表格插件实例（也不再依赖 $.fn.bootstrapTable）。

    // 自建 modal：bootstrap 3 会把 .modal-backdrop 与 body.modal-open 留在页面上，需一并清理
    if (rootEl.value) {
      $(rootEl.value).find('.modal').each(function () {
        try { $(this).modal('hide') } catch (e) { /* 忽略 */ }
      })
    }
    $('body').removeClass('modal-open')
    $('.modal-backdrop').remove()

  } catch (err) {
    console.warn('[cleanup]', err && err.message)
  }
})
</script>

<!-- 原 category.html 末尾的 .my-container/.myBtn-content 样式块已随「新增」按钮改版移除（这些类已无使用方） -->
<style scoped>
/* 每页条数下拉宽度 */
.ad-select { width: 130px; }
/* 分页条紧贴卡片脚 */
.ad-pager { margin: 0; }
/* 可排序表头（点表头切换排序） */
.ad-sort { cursor: pointer; user-select: none; white-space: nowrap; }
.ad-sort:hover { color: var(--ad-primary); }
.ad-sort i { margin-left: 4px; color: var(--ad-muted); }
/* 行内操作按钮间距（旧 bootstrap-table 的两个按钮挨在一起，这里保持同样紧凑） */
.ad-actions .btn + .btn { margin-left: 4px; }
</style>
