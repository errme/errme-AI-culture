<template>
  <div class="container-fluid ad-page">
    <!--
      角色管理：左侧角色列表（名称 / 编码 / 成员数 / 状态），右侧编辑表单 + 按钮权限。
      接口：/api/admin/role/list | /save | /delete、/api/admin/permission/buttons | /button，
            以及 /api/admin/permission/roles（只用于展示各角色的菜单权限数量）。
      菜单可见性单独在「菜单权限设置」页配置，本页只管角色本身与按钮级权限。
    -->
    <AdminPageHeader
      icon="mdi mdi-account-group-outline"
      title="角色管理"
      desc="角色决定成员能看哪些菜单（菜单权限页）与能做哪些操作（本页按钮权限）。"
    >
      <template #actions>
        <router-link class="btn btn-default btn-sm" to="/admin/permission">
          <i class="mdi mdi-sitemap"></i> 菜单权限设置
        </router-link>
        <button class="btn btn-primary btn-sm" @click="startCreate">
          <i class="mdi mdi-plus"></i> 新建角色
        </button>
      </template>
    </AdminPageHeader>

    <div class="row">
      <!-- 左：角色列表 -->
      <div class="col-md-4">
        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-account-multiple-outline"></i> 角色列表</h5>
            <div class="ad-card__actions">
              <span class="ad-badge">{{ roles.length }} 个角色</span>
              <button class="btn btn-default btn-xs" :disabled="loadingRoles" @click="refreshAll">
                <i class="mdi mdi-refresh"></i>
              </button>
            </div>
          </div>
          <div class="ad-card__body">
            <p v-if="loadingRoles" class="ad-hint" style="text-align: center;">加载中…</p>
            <AdminEmpty
              v-else-if="!roles.length"
              icon="mdi mdi-account-off-outline"
              text="还没有角色，先新建一个吧"
            >
              <template #action>
                <button class="btn btn-primary btn-sm" @click="startCreate">新建角色</button>
              </template>
            </AdminEmpty>
            <div v-else class="role-list">
              <button
                v-for="r in roles"
                :key="r.id"
                type="button"
                class="role-item"
                :class="{ 'is-active': !creating && r.id === currentRoleId }"
                @click="pickRole(r)"
              >
                <span class="role-item__head">
                  <span class="role-item__name">
                    {{ r.name || r.code || ('角色#' + r.id) }}
                    <span v-if="r.id === BUILTIN_ADMIN_ID" class="ad-badge ad-badge--primary">内置</span>
                  </span>
                  <span class="ad-badge" :class="statusOf(r) === 1 ? 'ad-badge--success' : 'ad-badge--danger'">
                    {{ statusOf(r) === 1 ? '启用' : '停用' }}
                  </span>
                </span>
                <span class="role-item__meta">
                  <code>{{ r.code || '—' }}</code>
                  <span>成员 {{ r.userCount || 0 }}</span>
                  <span>菜单 {{ menuCountOf(r.id) }}</span>
                </span>
              </button>
            </div>
            <p class="ad-help">成员数量来自后台用户表；菜单数量来自菜单权限设置，点击行进入编辑。</p>
          </div>
        </div>
      </div>

      <!-- 右：编辑 + 按钮权限 -->
      <div class="col-md-8">
        <AdminEmpty
          v-if="!showForm"
          icon="mdi mdi-account-edit-outline"
          text="请选择左侧角色进行编辑，或新建一个角色"
        >
          <template #action>
            <button class="btn btn-primary btn-sm" @click="startCreate">新建角色</button>
          </template>
        </AdminEmpty>

        <template v-else>
          <div class="ad-card">
            <div class="ad-card__head">
              <h5 class="ad-card__title">
                <i class="mdi mdi-account-edit-outline"></i>
                {{ creating ? '新建角色' : '编辑角色：' + (form.name || form.code || ('#' + form.id)) }}
              </h5>
              <div class="ad-card__actions">
                <span v-if="dirty" class="ad-dirty">有未保存的修改</span>
                <button v-if="!creating" class="btn btn-danger btn-sm" :disabled="saving" @click="removeRole">
                  <i class="mdi mdi-delete"></i> 删除角色
                </button>
              </div>
            </div>

            <div class="ad-card__body">
              <p class="ad-section-title">基本信息</p>
              <div class="ad-form-grid">
                <div class="form-group">
                  <label>角色名称</label>
                  <input v-model.trim="form.name" class="form-control" placeholder="如：内容编辑" />
                </div>
                <div class="form-group">
                  <label>角色编码</label>
                  <input
                    v-model.trim="form.code"
                    class="form-control"
                    :disabled="isBuiltinAdmin"
                    placeholder="如：editor"
                  />
                  <p v-if="isBuiltinAdmin" class="ad-help">
                    管理员角色为系统内置，编码不可修改（避免后台失去管理员入口）；后端同样会校验。
                  </p>
                  <p v-else class="ad-help">2-32 位字母、数字、下划线或短横线（{{ CODE_HINT }}），且不能与其它角色重复。</p>
                </div>
                <div class="form-group">
                  <label>排序</label>
                  <input v-model.number="form.sort" type="number" class="form-control" placeholder="10" />
                  <p class="ad-help">数字越小越靠前。</p>
                </div>
                <div class="form-group">
                  <label>状态</label>
                  <select v-model.number="form.status" class="form-control" :disabled="isBuiltinAdmin">
                    <option :value="1">启用</option>
                    <option :value="0">停用</option>
                  </select>
                  <p v-if="isBuiltinAdmin" class="ad-help">管理员角色不可停用；后端同样会校验。</p>
                  <p v-else class="ad-help">停用后该角色下的成员会失去对应权限。</p>
                </div>
              </div>

              <div class="form-group">
                <label>描述</label>
                <textarea
                  v-model.trim="form.description"
                  class="form-control"
                  rows="3"
                  placeholder="这个角色负责什么（选填）"
                ></textarea>
              </div>

              <div class="ad-sticky-actions">
                <button class="btn btn-primary" :disabled="saving || !dirty" @click="saveRole">
                  <i class="mdi mdi-content-save"></i> {{ creating ? '创建角色' : '保存修改' }}
                </button>
                <button class="btn btn-default" :disabled="saving" @click="resetForm">重置</button>
                <span class="ad-toolbar__spacer"></span>
                <span class="ad-hint">{{ dirty ? '修改后请记得保存' : '当前内容已与服务器一致' }}</span>
              </div>
            </div>
          </div>

          <!-- 按钮权限：仅编辑已有角色时可配置（新角色还没有 id） -->
          <div v-if="creating" class="ad-card">
            <div class="ad-card__body">
              <p class="ad-section-title">按钮权限</p>
              <p class="ad-hint">先保存角色，创建成功后即可为它勾选按钮级权限。</p>
            </div>
          </div>

          <div v-else class="ad-card">
            <div class="ad-card__head">
              <h5 class="ad-card__title"><i class="mdi mdi-toggle-switch-outline"></i> 按钮权限（可执行的操作）</h5>
              <div class="ad-card__actions">
                <span v-if="permDirty" class="ad-dirty">有未保存的修改</span>
                <button
                  class="btn btn-default btn-sm"
                  :disabled="loadingButtons || !buttons.length"
                  @click="checkAllButtons"
                >全选</button>
                <button
                  class="btn btn-default btn-sm"
                  :disabled="loadingButtons || !buttons.length"
                  @click="clearAllButtons"
                >清空</button>
                <button
                  class="btn btn-primary btn-sm"
                  :disabled="loadingButtons || savingButtons || !permDirty"
                  @click="saveButtons"
                >
                  <i class="mdi mdi-content-save"></i> 保存按钮权限
                </button>
              </div>
            </div>
            <div class="ad-card__body">
              <p class="ad-hint">
                勾选该角色可以执行的按钮级操作（如删除、批量删除）。菜单/目录的可见性请在
                <router-link to="/admin/permission">菜单权限设置</router-link> 里配置。
              </p>
              <p v-if="loadingButtons" class="ad-hint">加载中…</p>
              <AdminEmpty
                v-else-if="!buttons.length"
                icon="mdi mdi-toggle-switch-off-outline"
                text="没有可配置的按钮权限（请先在权限表里登记按钮）"
              />
              <div v-else class="role-perms">
                <label v-for="b in buttons" :key="b.id" class="role-perm">
                  <input type="checkbox" :value="b.id" v-model="grantedIds" />
                  <span>{{ b.title || b.name }}</span>
                  <code v-if="b.name && b.name !== b.title" class="role-perm__code">{{ b.name }}</code>
                </label>
              </div>
              <p v-if="buttons.length" class="ad-help">
                已选 {{ grantedIds.length }} / {{ buttons.length }} 项；保存后该角色对应的按钮才会显示给成员。
              </p>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 角色管理（对应后端 /api/admin/role/* 与 /api/admin/permission/button*）
 *
 * 交互要点：
 *   · 左选右编：点左侧角色 → 表单回填 + 拉取该角色的按钮权限；「新建角色」清空表单进入新增模式。
 *   · 表单（名称/编码/排序/状态/描述）与按钮权限分别保存，各自有「有未保存的修改」提示。
 *   · id=1 的内置管理员角色：编码与状态禁用（后端同样校验），避免后台失去唯一管理员入口。
 *   · 删除会做二次确认；后端返回 400（如角色下仍有成员）时用 dsToast.error 展示后端消息。
 *   · 所有请求失败都给 dsToast.error；卸载后不再改状态（disposed）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import {
  permissionButtonSave,
  permissionButtons,
  permissionRoles,
  roleDelete,
  roleList,
  roleSave
} from '@/api/admin'
import { toast as dsToast, confirmDelete } from '@/utils/notify'

/** 内置管理员角色 id（编码与状态不可改） */
const BUILTIN_ADMIN_ID = 1
const CODE_RE = /^[a-zA-Z0-9_-]{2,32}$/
const CODE_HINT = '[a-zA-Z0-9_-]{2,32}'

const roles = ref([])
const loadingRoles = ref(true)
/** roleId -> 菜单权限数量（来自 permissionRoles，取不到则显示 —） */
const menuCounts = reactive({})

const creating = ref(false)
const currentRoleId = ref(null)
const form = reactive({ id: null, code: '', name: '', description: '', sort: 10, status: 1 })
/** 表单快照（JSON 字符串）用于判断是否有未保存的修改 */
const snapshot = ref('')
const originalCode = ref('')
const saving = ref(false)

const buttons = ref([])
const grantedIds = ref([])
const savedGranted = ref([])
const loadingButtons = ref(false)
const savingButtons = ref(false)

let disposed = false
onBeforeUnmount(() => { disposed = true })

const currentRole = computed(() => roles.value.find(r => r.id === currentRoleId.value) || null)
const showForm = computed(() => creating.value || !!currentRole.value)
const isBuiltinAdmin = computed(() => !creating.value && currentRoleId.value === BUILTIN_ADMIN_ID)
const formKey = computed(() => JSON.stringify({
  code: form.code,
  name: form.name,
  description: form.description,
  sort: Number(form.sort) || 0,
  status: Number(form.status) === 0 ? 0 : 1
}))
const dirty = computed(() => showForm.value && formKey.value !== snapshot.value)
const permDirty = computed(() =>
  JSON.stringify(grantedIds.value.slice().sort()) !== JSON.stringify(savedGranted.value.slice().sort())
)

function statusOf(role) {
  return role && Number(role.status) === 0 ? 0 : 1
}
function menuCountOf(roleId) {
  return Object.prototype.hasOwnProperty.call(menuCounts, roleId) ? menuCounts[roleId] : '—'
}

/* ==================== 表单回填 / 重置 ==================== */

function clearForm() {
  form.id = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.sort = 10
  form.status = 1
  originalCode.value = ''
  snapshot.value = formKey.value
}

function applyRole(role) {
  form.id = role.id
  form.code = role.code || ''
  form.name = role.name || ''
  form.description = role.description || ''
  form.sort = Number(role.sort) || 0
  form.status = statusOf(role)
  originalCode.value = form.code
  snapshot.value = formKey.value
}

function pickRole(role) {
  creating.value = false
  currentRoleId.value = role.id
  applyRole(role)
  loadButtons(role.id)
}

function startCreate() {
  creating.value = true
  currentRoleId.value = null
  clearForm()
  form.sort = (roles.value.length + 1) * 10
  snapshot.value = formKey.value
  buttons.value = []
  grantedIds.value = []
  savedGranted.value = []
}

function resetForm() {
  if (creating.value) {
    startCreate()
    return
  }
  if (currentRole.value) {
    applyRole(currentRole.value)
    dsToast.info('已还原为服务器上的内容')
  }
}

/* ==================== 加载 ==================== */

async function loadRoles() {
  loadingRoles.value = true
  try {
    const list = await roleList()
    if (disposed) return
    roles.value = (list || []).map(r => ({ ...r, status: statusOf(r) }))
    const keep = currentRoleId.value == null ? null : roles.value.find(r => r.id === currentRoleId.value)
    if (keep) {
      applyRole(keep)
      loadButtons(keep.id)
    } else if (!creating.value) {
      currentRoleId.value = null
      clearForm()
      if (roles.value.length) pickRole(roles.value[0])
    }
  } catch (e) {
    if (disposed) return
    roles.value = []
    dsToast.error(e.message || '角色列表加载失败')
  } finally {
    if (!disposed) loadingRoles.value = false
  }
}

/** 菜单权限数量只是辅助展示，失败不打断角色管理主流程 */
async function loadMenuCounts() {
  try {
    const list = (await permissionRoles()) || []
    if (disposed) return
    Object.keys(menuCounts).forEach(k => { delete menuCounts[k] })
    list.forEach(r => {
      const id = r.id != null ? r.id : r.roleId
      if (id == null) return
      menuCounts[id] = Array.isArray(r.menuIds) ? r.menuIds.length : 0
    })
  } catch (e) {
    // 静默：左侧只是把菜单数量显示为「—」，不影响角色编辑
  }
}

async function loadButtons(roleId) {
  loadingButtons.value = true
  buttons.value = []
  grantedIds.value = []
  savedGranted.value = []
  try {
    const data = await permissionButtons(roleId)
    if (disposed) return
    // 快速连续切换角色时丢弃过期响应
    if (roleId !== currentRoleId.value) return
    const all = ((data && data.all) || []).slice().sort(
      (a, b) => (Number(a.sort) || 0) - (Number(b.sort) || 0) || (Number(a.id) || 0) - (Number(b.id) || 0)
    )
    const granted = (((data && data.granted) || []).map(v => Number(v))).filter(v => !isNaN(v))
    buttons.value = all
    grantedIds.value = granted.slice()
    savedGranted.value = granted.slice()
  } catch (e) {
    if (!disposed) dsToast.error(e.message || '按钮权限加载失败')
  } finally {
    if (!disposed) loadingButtons.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadRoles(), loadMenuCounts()])
}

/* ==================== 保存 / 删除 ==================== */

async function saveRole() {
  if (!form.name) {
    dsToast.warning('请填写角色名称')
    return
  }
  if (!isBuiltinAdmin.value && (creating.value || form.code !== originalCode.value) && !CODE_RE.test(form.code || '')) {
    dsToast.warning('角色编码需为 2-32 位字母、数字、下划线或短横线：' + CODE_HINT)
    return
  }
  const wasCreating = creating.value
  const payload = {
    code: form.code,
    name: form.name,
    description: form.description,
    sort: Number(form.sort) || 0,
    status: Number(form.status) === 0 ? 0 : 1
  }
  if (!wasCreating && form.id != null) payload.id = form.id
  saving.value = true
  try {
    const res = await roleSave(payload)
    if (disposed) return
    dsToast.success(wasCreating ? '角色已创建' : '角色已保存')
    const newId = res == null
      ? null
      : (typeof res === 'number' ? res : (res.id != null ? res.id : null))
    await loadRoles()
    if (disposed) return
    if (wasCreating) {
      // 后端可能只返回 count，这里再按编码兜底定位新角色
      const created = (newId != null ? roles.value.find(r => r.id === newId) : null) ||
        roles.value.find(r => r.code === payload.code)
      if (created) pickRole(created)
      else dsToast.warning('角色已保存，请在左侧列表中选中它继续配置按钮权限')
    }
    loadMenuCounts()
  } catch (e) {
    if (!disposed) dsToast.error(e.message || '保存失败')
  } finally {
    if (!disposed) saving.value = false
  }
}

function removeRole() {
  const role = currentRole.value
  if (!role) return
  const name = role.name || role.code || ('角色#' + role.id)
  confirmDelete({
    name,
    extra: '删除后该角色的成员会失去对应权限；角色下仍有成员时无法删除。',
    onConfirm: async () => {
      try {
        await roleDelete(role.id)
        if (disposed) return
        dsToast.success('已删除角色「' + name + '」')
        currentRoleId.value = null
        clearForm()
        await refreshAll()
      } catch (e) {
        // 后端返回 400（例如角色下仍有成员）时展示后端消息
        if (!disposed) dsToast.error(e.message || '删除失败')
      }
    }
  })
}

/* ==================== 按钮权限 ==================== */

function checkAllButtons() {
  grantedIds.value = buttons.value.map(b => b.id)
}
function clearAllButtons() {
  grantedIds.value = []
}

async function saveButtons() {
  if (currentRoleId.value == null) return
  const roleId = currentRoleId.value
  const ids = grantedIds.value.slice()
  savingButtons.value = true
  try {
    await permissionButtonSave({ roleId, permissionIds: ids })
    if (disposed) return
    savedGranted.value = ids.slice()
    dsToast.success('按钮权限已保存')
  } catch (e) {
    if (!disposed) dsToast.error(e.message || '按钮权限保存失败')
  } finally {
    if (!disposed) savingButtons.value = false
  }
}

onMounted(refreshAll)
</script>

<style scoped>
/* 左侧角色列表：整行可点，选中态与菜单权限页保持一致 */
.role-list { display: flex; flex-direction: column; gap: 8px; }
.role-item {
  display: block; width: 100%; text-align: left; cursor: pointer;
  padding: 10px 12px; border: 1px solid var(--ad-border); border-radius: var(--ad-radius-sm);
  background: #fff; transition: border-color .15s, background .15s;
}
.role-item:hover { border-color: #c7d2fe; background: #f8faff; }
.role-item.is-active { border-color: var(--ad-primary); background: var(--ad-primary-weak); }
.role-item__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.role-item__name { font-size: 13.5px; font-weight: 600; color: var(--ad-text); }
.role-item__name .ad-badge { margin-left: 6px; font-weight: 400; }
.role-item__meta {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-top: 5px; font-size: 12px; color: var(--ad-muted);
}
.role-item__meta code {
  background: #f1f5f9; color: #7c8698; font-size: 11px;
  padding: 1px 5px; border-radius: 4px;
}

/* 按钮权限勾选区：紧凑多列排布 */
.role-perms { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.role-perm {
  display: inline-flex; align-items: center; gap: 6px; margin: 0;
  font-weight: 400; font-size: 13px; color: var(--ad-text);
}
.role-perm input { margin: 0; }
.role-perm__code { background: #f1f5f9; color: #7c8698; font-size: 11px; padding: 1px 5px; border-radius: 4px; }
</style>
