<template>
  <div class="container-fluid ad-page">
    <!--
      菜单权限设置：按角色勾选可见的菜单/目录。
      数据链路：sys_role_permission → sys_permission.menu_id → sys_menu（与 /api/admin/menus 一致），
      所以这里保存后，该角色登录看到的左侧菜单会随之变化。
    -->
    <AdminPageHeader
      icon="mdi mdi-shield-outline"
      title="菜单权限设置"
      desc="先选左侧角色，再勾选它可见的菜单/目录后保存。未勾选的菜单该角色登录后不可见（后端接口仍会二次校验权限）。"
    >
      <template #actions>
        <span v-if="loading" class="ad-hint">加载中…</span>
        <button class="btn btn-default btn-sm" @click="loadAll"><i class="mdi mdi-refresh"></i> 刷新</button>
      </template>
    </AdminPageHeader>

    <div class="row">
      <!-- 左：角色 -->
      <div class="col-md-3">
        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-account-multiple-outline"></i> 角色</h5>
          </div>
          <div class="ad-card__body perm-roles">
            <button
              v-for="r in roles"
              :key="r.id"
              type="button"
              class="perm-role"
              :class="{ 'is-active': r.id === currentRoleId }"
              @click="pickRole(r)"
            >
              <span class="perm-role__name">
                {{ r.name || r.code || ('角色#' + r.id) }}
                <span v-if="myRoleIds.indexOf(r.id) > -1" class="ad-badge ad-badge--primary">我的角色</span>
              </span>
              <span class="perm-role__meta">{{ (r.menuIds || []).length }} 个菜单</span>
            </button>
            <AdminEmpty v-if="!loading && !roles.length" icon="mdi mdi-account-off"
                        text="没有可配置的角色" />
          </div>
        </div>
      </div>

      <!-- 右：菜单树 -->
      <div class="col-md-9">
        <div class="ad-card">
          <!-- 说明：card-toolbar 只作为老回归脚本（tools/ui-check.mjs）的选择器标记保留，视觉以 ad-card__head 为准 -->
          <div class="ad-card__head card-toolbar">
            <h5 class="ad-card__title">
              <i class="mdi mdi-sitemap"></i>
              {{ currentRole ? (currentRole.name || currentRole.code) : '请选择角色' }} 的可见菜单
            </h5>
            <div class="ad-card__actions">
              <span v-if="dirty" class="ad-dirty">有未保存的修改</span>
              <button class="btn btn-default btn-sm" :disabled="!currentRole" @click="selectAll">全选</button>
              <button class="btn btn-default btn-sm" :disabled="!currentRole" @click="clearAll">清空</button>
              <button class="btn btn-primary btn-sm" :disabled="!currentRole || !dirty || saving" @click="save">
                <i class="mdi mdi-content-save"></i> 保存权限
              </button>
            </div>
          </div>
          <div class="ad-card__body">
            <AdminEmpty v-if="!currentRole" icon="mdi mdi-cursor-default"
                        text="请先在左侧选择一个角色" />
            <template v-else>
              <div v-if="selfLockRisk" class="alert alert-warning perm-warn">
                <i class="mdi mdi-alert-outline"></i>
                保存后「{{ currentRole.name || currentRole.code }}」将看不到「菜单权限设置」菜单，
                如果这就是你当前登录的角色，保存后需要让其它管理员帮你改回来。
              </div>
              <div v-for="group in tree" :key="group.id" class="perm-group">
                <label class="perm-group__head">
                  <input
                    type="checkbox"
                    :checked="isGroupAllChecked(group)"
                    :indeterminate.prop="isGroupPartial(group)"
                    @change="toggleGroup(group, $event.target.checked)"
                  />
                  <span class="perm-group__name">
                    <i v-if="group.icon" :class="group.icon"></i> {{ group.name }}
                  </span>
                  <span class="perm-group__count">{{ checkedChildren(group) }} / {{ group.children.length }}</span>
                </label>
                <div class="perm-group__body">
                  <label v-for="child in group.children" :key="child.id" class="perm-item">
                    <input type="checkbox" :value="child.id" v-model="checkedIds" />
                    <span>{{ child.name }}</span>
                    <code v-if="child.url" class="perm-url">{{ child.url }}</code>
                  </label>
                  <p v-if="!group.children.length" class="ad-help">该目录下没有子菜单</p>
                </div>
              </div>
              <AdminEmpty v-if="!tree.length" icon="mdi mdi-sitemap" text="没有菜单数据" />
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 菜单权限设置
 *
 * 交互约定：
 *   · 左侧选角色，右侧勾选菜单；「目录」父节点支持全选/半选（indeterminate）联动。
 *   · 保存为**全量覆盖**该角色的菜单授权（后端事务处理），成功后刷新角色列表。
 *   · 防自锁：若保存会让「当前登录角色」失去本页菜单，保存前弹确认（仍允许保存，避免误操作）。
 */
import { computed, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { adminProfile, permissionMenus, permissionRoles, permissionSave, userRoles } from '@/api/admin'
import { toast as dsToast, confirmDialog } from '@/utils/notify'

const roles = ref([])
const menus = ref([])
const currentRoleId = ref(null)
const checkedIds = ref([])
const savedIds = ref([])
const myRoleIds = ref([])
const loading = ref(false)
const saving = ref(false)

const currentRole = computed(() => roles.value.find(r => r.id === currentRoleId.value) || null)

/** 扁平菜单 → 两级树（目录 + 子菜单）；没有父级的菜单自成一个分组 */
const tree = computed(() => {
  const list = menus.value.slice().sort((a, b) => (a.sort || 0) - (b.sort || 0) || (a.id || 0) - (b.id || 0))
  const byId = {}
  list.forEach(m => { byId[m.id] = { ...m, children: [] } })
  const roots = []
  list.forEach(m => {
    const node = byId[m.id]
    const parent = m.pid && m.pid !== 0 ? byId[m.pid] : null
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
})

const dirty = computed(() => JSON.stringify(checkedIds.value.slice().sort()) !== JSON.stringify(savedIds.value.slice().sort()))

/** 本页菜单（用于防自锁判断）：按 url 匹配 /permission */
const PERM_PAGE_URL = '/permission/index'
const permMenu = computed(() => menus.value.find(m => (m.url || '') === PERM_PAGE_URL) || null)
const selfLockRisk = computed(() => {
  const menu = permMenu.value
  if (!menu || !currentRole.value) return false
  if (myRoleIds.value.indexOf(currentRole.value.id) === -1) return false
  return checkedIds.value.indexOf(menu.id) === -1
})

function childrenOf(group) {
  return (group.children || []).map(c => c.id)
}
function isGroupAllChecked(group) {
  const ids = childrenOf(group)
  return ids.length > 0 && ids.every(id => checkedIds.value.indexOf(id) > -1)
}
function isGroupPartial(group) {
  const ids = childrenOf(group)
  const hit = ids.filter(id => checkedIds.value.indexOf(id) > -1).length
  return hit > 0 && hit < ids.length
}
function checkedChildren(group) {
  return childrenOf(group).filter(id => checkedIds.value.indexOf(id) > -1).length
}
/** 勾选目录：连同其父级目录一起勾上（否则菜单拿不到可见性） */
function toggleGroup(group, checked) {
  const ids = childrenOf(group)
  const set = new Set(checkedIds.value)
  if (checked) {
    set.add(group.id)
    ids.forEach(id => set.add(id))
  } else {
    set.delete(group.id)
    ids.forEach(id => set.delete(id))
  }
  checkedIds.value = [...set]
}

function pickRole(role) {
  currentRoleId.value = role.id
  const ids = (role.menuIds || []).slice()
  checkedIds.value = ids
  savedIds.value = ids.slice()
}

function selectAll() {
  checkedIds.value = menus.value.map(m => m.id)
}
function clearAll() {
  checkedIds.value = []
}

async function loadAll() {
  loading.value = true
  try {
    const [menuList, roleList] = await Promise.all([permissionMenus(), permissionRoles()])
    menus.value = (menuList || []).filter(m => m.status == null || m.status != 0)
    roles.value = roleList || []
    if (!currentRoleId.value && roles.value.length) pickRole(roles.value[0])
    else if (currentRoleId.value) {
      const same = roles.value.find(r => r.id === currentRoleId.value)
      if (same) pickRole(same)
    }
  } catch (e) {
    dsToast.error(e.message || '权限数据加载失败')
  } finally {
    loading.value = false
  }
}

/** 当前登录用户的角色（用于「我的角色」标记与防自锁提示），失败不影响主流程 */
async function loadMyRoles() {
  try {
    const me = await adminProfile()
    // /api/admin/me 返回 { user: {...}, scope, admin }（兼容直接返回用户对象的情况）
    const userId = (me && me.user && me.user.id) || (me && me.id) || null
    if (userId) {
      const list = await userRoles(userId)
      const rows = Array.isArray(list) ? list : (list && list.rows) || []
      myRoleIds.value = rows.map(r => (r.id != null ? r.id : r.roleId)).filter(v => v != null)
    }
  } catch (e) {
    myRoleIds.value = []
  }
}

async function save() {
  if (!currentRole.value) return
  const roleId = currentRole.value.id
  const menuIds = checkedIds.value.slice()
  if (selfLockRisk.value) {
    const ok = await confirmDialog({
      title: '确认移除本页菜单权限？',
      content: '保存后「' + (currentRole.value.name || currentRole.value.code) + '」将看不到「菜单权限设置」。',
      detail: '如果这属于你当前登录的角色，保存后需要让其它管理员帮你恢复。',
      danger: true,
      confirmText: '仍然保存',
      cancelText: '取消'
    })
    if (!ok) return
  }
  saving.value = true
  try {
    const res = await permissionSave({ roleId, menuIds })
    const count = (res && res.count != null) ? res.count : menuIds.length
    dsToast.success('已保存：' + count + ' 个菜单可见')
    if (res && res.warning) dsToast.warning(res.warning)
    savedIds.value = menuIds.slice()
    await loadAll()
  } catch (e) {
    dsToast.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadAll(), loadMyRoles()])
})
</script>

<style scoped>
/* .card-toolbar 仅作老回归脚本（tools/ui-check.mjs）的选择器标记，
   主题里的 24px 内边距在这里归零，布局一律以 ad-card__head 为准 */
.ad-card__head.card-toolbar { padding: 12px 16px; }

.perm-roles { padding: 8px; }
.perm-role {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  width: 100%; margin-bottom: 6px; padding: 9px 11px;
  border: 1px solid var(--ad-border); border-radius: var(--ad-radius-sm); background: #fff;
  text-align: left; cursor: pointer; transition: border-color .15s, background .15s;
}
.perm-role:last-child { margin-bottom: 0; }
.perm-role:hover { border-color: #c7d2fe; background: #f8faff; }
.perm-role.is-active { border-color: var(--ad-primary); background: #f1f5ff; }
.perm-role__name { font-weight: 600; color: var(--ad-text); font-size: 13.5px; }
.perm-role__name .ad-badge { margin-left: 6px; font-weight: 400; }
.perm-role__meta { color: var(--ad-muted); font-size: 12px; white-space: nowrap; }

.perm-warn { margin-bottom: 14px; font-size: 13px; }

.perm-group { border: 1px solid #eef1f6; border-radius: var(--ad-radius); margin-bottom: 12px; overflow: hidden; }
.perm-group:last-child { margin-bottom: 0; }
.perm-group__head {
  display: flex; align-items: center; gap: 8px; margin: 0; padding: 10px 12px;
  background: #f8fafc; cursor: pointer; font-weight: 600; color: var(--ad-text);
}
.perm-group__head input { margin: 0; }
.perm-group__name { flex: 1 1 auto; }
.perm-group__count { color: var(--ad-muted); font-size: 12px; font-weight: 400; }
.perm-group__body { display: flex; flex-wrap: wrap; gap: 6px 18px; padding: 12px; }
.perm-item { display: inline-flex; align-items: center; gap: 6px; margin: 0; font-weight: 400; font-size: 13px; color: #334155; }
.perm-item input { margin: 0; }
.perm-url { background: #f1f5f9; color: #7c8698; font-size: 11px; padding: 1px 5px; border-radius: 4px; }
</style>
