<template>
  <div class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-tag-multiple"
      title="标签管理"
      desc="标签用于前台「猜你喜欢」与检索聚合，一条文化可以勾选多个标签；删除标签会同时解除它与文化的绑定。"
    >
      <template #actions>
        <input v-model.trim="newName" class="form-control input-sm ad-input" placeholder="新标签名称"
               @keyup.enter="addTag" />
        <button class="btn btn-primary btn-sm" :disabled="saving" @click="addTag">
          <i class="mdi mdi-plus"></i> 新增标签
        </button>
      </template>
    </AdminPageHeader>

    <div class="ad-card">
      <!-- 说明：card-toolbar 只作为老回归脚本（tools/ui-check.mjs）的选择器标记保留，视觉一律以 ad-card__head 为准 -->
      <div class="ad-card__head card-toolbar">
        <h5 class="ad-card__title"><i class="mdi mdi-format-list-bulleted"></i> 全部标签</h5>
        <div class="ad-card__actions">
          <span class="ad-hint">已选 {{ selectedIds.length }} 个</span>
          <button class="btn btn-danger btn-sm" :disabled="!selectedIds.length" @click="batchRemove">
            <i class="mdi mdi-delete"></i> 批量删除
          </button>
          <!-- 合并标签：源标签的关联改挂到目标标签，源标签随后被删除（不可逆） -->
          <select v-model="mergeSourceId" class="form-control input-sm ad-select" :disabled="!tags.length"
                  title="源标签（合并后会被删除）">
            <option value="">源标签…</option>
            <option v-for="t in tags" :key="'src-' + t.id" :value="t.id">{{ t.name }}</option>
          </select>
          <span class="ad-hint">→</span>
          <select v-model="mergeTargetId" class="form-control input-sm ad-select" :disabled="!tags.length"
                  title="目标标签（保留）">
            <option value="">目标标签…</option>
            <option v-for="t in tags" :key="'tgt-' + t.id" :value="t.id">{{ t.name }}</option>
          </select>
          <button class="btn btn-warning btn-sm" :disabled="!mergeSourceId || !mergeTargetId" @click="mergeTags">
            <i class="mdi mdi-call-merge"></i> 合并标签
          </button>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <table class="ad-table">
            <thead>
              <tr>
                <th style="width:44px;">
                  <input type="checkbox" :checked="allChecked" :disabled="!tags.length" @change="toggleAll" title="全选">
                </th>
                <th style="width:80px;">ID</th>
                <th>标签名</th>
                <th style="width:110px;">排序</th>
                <th style="width:180px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="5"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
              </tr>
              <tr v-else-if="!tags.length">
                <td colspan="5">
                  <AdminEmpty icon="mdi mdi-tag-outline" text="暂无标签，在上方输入名称即可添加第一个标签" />
                </td>
              </tr>
              <tr v-for="tag in tags" :key="tag.id">
                <td><input type="checkbox" :value="tag.id" v-model="selectedIds"></td>
                <td class="ad-num">{{ tag.id }}</td>
                <td>
                  <input v-if="editingId === tag.id" v-model.trim="editingName" class="form-control input-sm"
                         placeholder="新标签名" />
                  <span v-else>{{ tag.name }}</span>
                </td>
                <td class="ad-num">
                  <input v-if="editingId === tag.id" v-model.number="editingSort" type="number"
                         class="form-control input-sm" />
                  <span v-else>{{ tag.sort }}</span>
                </td>
                <td class="ad-actions">
                  <template v-if="editingId === tag.id">
                    <button class="btn btn-success btn-xs" @click="saveEdit(tag)">保存</button>
                    <button class="btn btn-default btn-xs" @click="cancelEdit">取消</button>
                  </template>
                  <template v-else>
                    <button class="btn btn-primary btn-xs" title="改名走 /tag/rename，不会清空 slug" @click="startEdit(tag)">
                      重命名
                    </button>
                    <button class="btn btn-danger btn-xs" @click="removeTag(tag)">删除</button>
                  </template>
                </td>
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
import { tagBatchDelete, tagDelete, tagList, tagMerge, tagRename, tagRestore, tagSave } from '@/api/admin'
import { toast as dsToast, confirmDelete, confirmDialog } from '@/utils/notify'

/**
 * 标签管理（对应后端 /api/admin/tag/*）
 * 排版统一到 src/styles/admin-ui.css 的 ad-* 规范（页头 / 卡片 / 表格 / 空状态）。
 * 改名走 /tag/rename（只改 name，保留 slug/sort）；合并走 /tag/merge（不可逆）。
 */
const tags = ref([])
const loading = ref(true)
const saving = ref(false)
const newName = ref('')
const editingId = ref(null)
const editingName = ref('')
const editingSort = ref(0)

/** 组件已卸载：卸载后不再回写任何状态 */
let disposed = false

/** 顶部轻提示（统一交互，type: green/red/orange） */
function toast(msg, type = 'green') {
  const fn = type === 'red' ? dsToast.error : type === 'orange' ? dsToast.warning : dsToast.success
  fn(msg)
}

async function load() {
  loading.value = true
  selectedIds.value = []
  try {
    tags.value = (await tagList()) || []
  } catch (e) {
    tags.value = []
    toast(e.message || '标签加载失败', 'red')
  } finally {
    loading.value = false
  }
}

async function addTag() {
  if (!newName.value) return
  saving.value = true
  try {
    await tagSave({ name: newName.value, sort: tags.value.length * 10 + 10 })
    newName.value = ''
    await load()
  } catch (e) {
    toast(e.message || '新增失败', 'red')
  } finally {
    saving.value = false
  }
}

function startEdit(tag) {
  editingId.value = tag.id
  editingName.value = tag.name
  editingSort.value = tag.sort
}

function cancelEdit() {
  editingId.value = null
  editingName.value = ''
}

async function saveEdit(tag) {
  const name = (editingName.value || '').trim()
  if (!name) { dsToast.error('标签名不能为空'); return }
  const nameChanged = name !== tag.name
  const sortChanged = Number(editingSort.value) !== Number(tag.sort)
  if (!nameChanged && !sortChanged) { cancelEdit(); return }
  try {
    // 改名走 rename：只改 name，slug / sort 保持不变
    // （save 会把未传的 slug 覆盖成 null，等于清空 slug，所以不能用它来改名）
    if (nameChanged) await tagRename({ id: tag.id, name })
    // 排序变更仍走 save：显式带上原 slug，避免被清空
    if (sortChanged) {
      const payload = { id: tag.id, name, sort: editingSort.value }
      if (tag.slug) payload.slug = tag.slug
      await tagSave(payload)
    }
    if (disposed) return
    cancelEdit()
    await load()
    if (disposed) return
    dsToast.success(nameChanged ? `已重命名为「${name}」` : '排序已更新')
  } catch (e) {
    if (disposed) return
    dsToast.error((e && e.message) || '保存失败')
  }
}

function removeTag(tag) {
  const doDelete = async () => {
    await tagDelete(tag.id)
    await load()
    dsToast.withUndo('标签「' + tag.name + '」已删除', async () => {
      await tagRestore({ id: tag.id })
      await load()
      dsToast.success('已恢复标签「' + tag.name + '」')
    }, { detail: '已绑定该标签的文化关联不会自动恢复，可重新勾选。' })
  }
  confirmDelete({
    name: tag.name,
    extra: '删除后，已绑定该标签的文化会自动解绑，前台标签筛选里不再出现该标签。',
    onConfirm: doDelete
  })
}

/* ==================== 批量操作（Batch4） ==================== */

const selectedIds = ref([])
const allChecked = computed(() => tags.value.length > 0 && selectedIds.value.length === tags.value.length)

function toggleAll(e) {
  selectedIds.value = e.target.checked ? tags.value.map(t => t.id) : []
}

function batchRemove() {
  const ids = selectedIds.value.slice()
  if (!ids.length) return
  confirmDelete({
    target: `选中的 ${ids.length} 个标签`,
    extra: '删除后这些标签与文化的绑定会一并解除，前台标签筛选里不再出现，且不可恢复。',
    onConfirm: async () => {
      const res = await tagBatchDelete({ ids })
      selectedIds.value = []
      load()
      dsToast.withUndo(`已删除 ${(res && res.count) || ids.length} 个标签`, async () => {
        await tagRestore({ ids })
        await load()
        dsToast.success('已恢复 ' + ids.length + ' 个标签')
      })
    }
  })
}

/* ==================== 合并标签（Batch6） ==================== */

/** 合并的源标签 / 目标标签（空串表示未选择） */
const mergeSourceId = ref('')
const mergeTargetId = ref('')

function mergeTags() {
  const source = tags.value.find(t => String(t.id) === String(mergeSourceId.value))
  const target = tags.value.find(t => String(t.id) === String(mergeTargetId.value))
  if (!source || !target) { dsToast.warning('请先选择源标签和目标标签'); return }
  if (source.id === target.id) { dsToast.warning('源标签和目标标签不能相同'); return }
  confirmDialog({
    title: '合并标签',
    content: `把「${source.name}」合并到「${target.name}」？`,
    detail: '合并不可逆：源标签的关联会改挂到目标标签，源标签随后被删除。',
    danger: true,
    confirmText: '确认合并',
    onConfirm: async () => {
      try {
        const res = await tagMerge({ sourceId: source.id, targetId: target.id })
        if (disposed) return
        mergeSourceId.value = ''
        mergeTargetId.value = ''
        await load()
        if (disposed) return
        dsToast.success(
          `已合并「${source.name}」→「${target.name}」：改挂 ${(res && res.moved) || 0} 条、合并 ${(res && res.merged) || 0} 条`
        )
      } catch (e) {
        if (disposed) return
        dsToast.error((e && e.message) || '合并失败')
      }
    }
  })
}

onMounted(load)

onBeforeUnmount(() => {
  disposed = true
})
</script>

<style scoped>
/* 统一的窄输入框宽度（替代散落的 inline style="width:180px"） */
.ad-input { width: 190px; }
.ad-header__actions .ad-input { width: 190px; }
/* 合并标签的源/目标下拉（卡片操作区内 form-control 默认撑满，需要固定宽度） */
.ad-select { width: 150px; }

/* .card-toolbar 仅作老回归脚本（tools/ui-check.mjs）的选择器标记，
   主题里的 24px 内边距在这里归零，布局一律以 ad-card__head 为准 */
.ad-card__head.card-toolbar { padding: 12px 16px; }

.ad-actions .btn + .btn { margin-left: 6px; }
</style>
