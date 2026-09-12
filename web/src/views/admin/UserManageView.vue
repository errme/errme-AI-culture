<template>
  <!--
    用户管理（移植自 frontend/templates/admin/user.html）

    只渲染原模板 <main class="coder-layout-content"> 内部内容，
    侧边栏 / 顶栏由 AdminLayout.vue 提供。

    ★ 表格替换（本页已不再依赖 bootstrap-table）★
      · 旧实现：$('#userTable').bootstrapTable({ ajax, columns, pagination ... }) ——
        表头 / 表体 / 分页条 / 列筛选 / 刷新按钮全部由 jQuery 插件运行时拼 HTML 字符串生成，
        行内操作列还用 onclick="edit(id)" 拼串注入（依赖 window.edit / window.del），
        「批量删除 / 设定角色」靠 $('#userTable').bootstrapTable('getSelections') 反查勾选行。
      · 新实现：Vue 自己渲染 <thead> + <tr v-for> + 自建分页（表体不出现 <template v-else>，
        空状态用元素级 v-if，避免 Vue 生成 Fragment 造成补丁错位）；
        勾选列改为 v-model="selectedIds" + 表头全选 toggleAll()，批量操作直接读 selectedIds。

    对外可见的一切保持不变：
      · 容器 id/class：#toolbar / #userTable / #q_username / #search / #export /
        .ad-table-wrap / .ad-card* / .toolbar-btn-action 全部保留；
      · 弹窗 id 与表单字段：#userAddModal / #userEditModal / #addUserRoleModal /
        #userAddForm / #userEditForm / #addUserRoleForm / #addRole_userid / #addRole_username /
        #e_id / #e_username / #e_email / #e_tel / #file-pic 以及全部 name= 属性原样不动；
      · data-method 属性仍在（按钮同时用 @click 直连同一组 doMethod，SPA 下不再靠全局分发）；
      · 增删改查走同一套 web/src/api/admin.js 接口，payload 结构未变；
      · 成功/失败提示仍走 dsToast / confirmDelete / notify（以及 $.confirm 兼容层）。
    仍保留 jQuery 的部分：bootstrap 3 弹窗（modal）、bootstrapValidator 表单校验、
    fileinput 头像上传、$.confirm 兼容层 —— 这些都不属于「表格插件」。
  -->
  <div class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-account-multiple-outline"
      title="用户管理"
      desc="维护前台注册用户：新增 / 修改资料、分配角色、批量删除与导出。删除账号不会删除其历史评论，但评论将不再显示昵称。"
    >
      <template #actions>
        <!-- 说明：#toolbar 作为老后台「工具条容器」的标记保留（bootstrap-table 的 toolbar 选项并未启用） -->
        <div id="toolbar" class="ad-toolbar">
          <label class="ad-label" for="q_username">名称：</label>
          <input id="q_username" name="username" type="text" class="form-control input-sm ad-input"
                 placeholder="输入名称" v-model="query.username" @keyup.enter="doMethod.search()">
          <button id="search" type="button" class="btn btn-primary btn-sm" data-toggle="modal"
                  data-method="search" @click="doMethod.search()">
            <i class="mdi mdi-magnify"></i> 搜索
          </button>
          <button id="export" type="button" class="btn btn-default btn-sm" data-toggle="modal"
                  data-method="exportData" @click="doMethod.exportData()">
            <i class="mdi mdi-download"></i> 导出
          </button>
        </div>
      </template>
    </AdminPageHeader>

    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-format-list-bulleted"></i> 用户列表</h5>
        <div class="ad-card__actions toolbar-btn-action">
          <span class="ad-hint">共 {{ total }} 条 · 第 {{ page }} / {{ maxPage }} 页 · 已选 {{ selectedIds.length }} 条</span>
          <button type="button" class="btn btn-default btn-sm" :disabled="loading" @click="reload()">
            <i class="mdi mdi-refresh" :class="{ 'mdi-spin': loading }"></i> 刷新
          </button>
          <button type="button" class="btn btn-primary btn-sm" data-toggle="modal" data-method="add"
                  @click="doMethod.add()">
            <i class="mdi mdi-plus"></i> 新增用户
          </button>
          <a class="btn btn-default btn-sm" href="#!" data-method="addUserRole"
             @click.prevent="doMethod.addUserRole()">
            <i class="mdi mdi-account-key"></i> 设定角色
          </a>
          <button type="button" class="btn btn-danger btn-sm" data-toggle="modal" data-method="delBatch"
                  @click="doMethod.delBatch()">
            <i class="mdi mdi-delete"></i> 批量删除
          </button>
        </div>
      </div>
      <div class="ad-card__body ad-card__body--flush">
        <div class="ad-table-wrap">
          <!--
            保留 Bootstrap 3 的表格类（table / table-bordered / table-hover）保证视觉与老后台一致，
            同时叠加全局 ad-table 规范；外层仍是 .ad-table-wrap。
            v-for 直接挂在 <tr> 上（不用 <template v-else>，见文件头说明）。
          -->
          <table id="userTable" class="table table-bordered table-hover ad-table">
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
                <th style="width: 110px;" class="ad-sort" :title="sortTitle('id')" @click="toggleSort('id')">
                  用户编号 <i :class="sortIcon('id')"></i>
                </th>
                <th class="ad-sort" :title="sortTitle('username')" @click="toggleSort('username')">
                  用户名 <i :class="sortIcon('username')"></i>
                </th>
                <th class="ad-sort" :title="sortTitle('email')" @click="toggleSort('email')">
                  邮件 <i :class="sortIcon('email')"></i>
                </th>
                <th style="width: 70px;" class="text-center">性别</th>
                <th style="width: 130px;">电话</th>
                <th style="width: 80px;" class="text-center">头像</th>
                <th style="width: 170px;">所属角色</th>
                <th style="width: 120px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading">
                <td colspan="9" class="text-center text-muted">加载中…</td>
              </tr>
              <tr v-else-if="!displayRows.length">
                <td colspan="9">
                  <AdminEmpty
                    icon="mdi mdi-account-off"
                    :text="query.username
                      ? '没有匹配「' + query.username + '」的用户'
                      : '暂无用户，点右上角「新增用户」创建第一个账号'" />
                </td>
              </tr>
              <tr v-for="row in displayRows" :key="row.id">
                <td class="text-center"><input type="checkbox" :value="row.id" v-model="selectedIds" /></td>
                <td class="ad-num">{{ row.id }}</td>
                <td>{{ row.username }}</td>
                <td>{{ row.email }}</td>
                <td class="text-center">{{ sexText(row.sex) }}</td>
                <td class="ad-num">{{ row.tel }}</td>
                <td class="text-center">
                  <img v-if="avatarUrl(row.headImg)" :src="avatarUrl(row.headImg)" alt="头像"
                       style="height:35px;width:35px;border-radius:50%;line-height:50px!important;">
                  <span v-else class="text-muted">—</span>
                </td>
                <!-- 所属角色：原 rolesFormatter 拼的 label 串，这里改成 Vue 渲染（空 = 未分配权限） -->
                <td>
                  <span v-if="!(row.roles && row.roles.length)" class="label label-danger">未分配权限</span>
                  <span v-for="role in (row.roles || [])" :key="role.id" class="label label-warning ad-role-tag">{{ role.name }}</span>
                </td>
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
      <!-- 分页（自建）：每页条数沿用原 pageList [10,15,20] + 上一页/下一页 + 第 x/y 页 -->
      <div class="ad-card__foot">
        <div class="ad-toolbar">
          <span class="ad-hint">共 {{ total }} 条记录{{ sortKey ? '（排序仅作用于当前页）' : '' }}</span>
          <select v-model.number="pageSize" class="form-control input-sm ad-select" @change="changePageSize">
            <option :value="10">每页 10 条</option>
            <option :value="15">每页 15 条</option>
            <option :value="20">每页 20 条</option>
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
    <div class="modal fade" id="userAddModal" tabindex="-1" role="dialog" aria-labelledby="userAddModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="userAddModalLabel">新增用户</h4>
          </div>
          <form id="userAddForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">
              <p class="ad-section-title">账号信息</p>
              <div class="form-group">
                <label for="recipient-name" class="control-label">用户名：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="username" id="recipient-name"
                       placeholder="3-20 个字符" v-model="addForm.username">
              </div>
              <div class="form-group">
                <label for="recipient-email" class="control-label">邮箱：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="email" id="recipient-email"
                       placeholder="name@example.com" v-model="addForm.email">
              </div>
              <div class="form-group">
                <label for="recipient-tel" class="control-label">电话号码：<span class="text-danger">*</span></label>
                <input type="text" class="form-control" name="tel" id="recipient-tel"
                       placeholder="11 位手机号" v-model="addForm.tel">
              </div>

              <p class="ad-section-title">登录密码</p>
              <div class="form-group">
                <label for="recipient-password" class="control-label">密码：<span class="text-danger">*</span></label>
                <input type="password" class="form-control" name="password" id="recipient-password"
                       v-model="addForm.password">
              </div>
              <div class="form-group">
                <label for="recipient-confirmPassword" class="control-label">确认密码：<span class="text-danger">*</span></label>
                <input type="password" class="form-control" name="confirmPassword" id="recipient-confirmPassword"
                       v-model="addForm.confirmPassword">
                <p class="ad-help">两次输入需一致，且不能与用户名相同。</p>
              </div>

              <p class="ad-section-title">其他资料</p>
              <div class="form-group">
                <label class="control-label">性别：</label>
                <div class="clearfix">
                  <label class="coder-radio radio-inline radio-primary">
                    <input type="radio" name="sex" :value="0" v-model="addForm.sex"><span>女</span>
                  </label>
                  <label class="coder-radio radio-inline radio-primary">
                    <input type="radio" name="sex" :value="1" checked v-model="addForm.sex"><span>男</span>
                  </label>
                </div>
              </div>
              <!-- 头像上传 -->
              <div class="form-group">
                <label class="control-label">头像：</label>
                <input id="file-pic" name="file" type="file" multiple />
                <p class="ad-help">支持 jpg、jpeg、png、gif、txt、docx、zip、xlsx 格式，大小不限；保存用户后再上传会关联到该账号。</p>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
              <button type="button" data-method="save" class="btn btn-primary" @click="doMethod.save()">保存用户</button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 新增表单结束 -->
    <!-- 修改表单start -->
    <div class="modal fade" id="userEditModal" tabindex="-1" role="dialog" aria-labelledby="userEditModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="userEditModalLabel">编辑用户</h4>
          </div>
          <form id="userEditForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">
              <input type="hidden" id="e_id" class="form-control" name="id" v-model="editForm.id">
              <div class="form-group">
                <label for="recipient-name" class="control-label">用户名：<span class="text-danger">*</span></label>
                <input type="text" id="e_username" class="form-control" name="username"
                       v-model="editForm.username">
              </div>
              <div class="form-group">
                <label for="recipient-email" class="control-label">邮箱：<span class="text-danger">*</span></label>
                <input type="text" id="e_email" class="form-control" name="email" v-model="editForm.email">
              </div>
              <div class="form-group">
                <label for="recipient-tel" class="control-label">电话号码：<span class="text-danger">*</span></label>
                <input type="text" id="e_tel" class="form-control" name="tel" v-model="editForm.tel">
              </div>
              <div class="form-group">
                <label class="control-label">性别：</label>
                <div class="clearfix">
                  <label class="coder-radio radio-inline radio-primary">
                    <input type="radio" name="sex" :value="0" v-model="editForm.sex"><span>女</span>
                  </label>
                  <label class="coder-radio radio-inline radio-primary">
                    <input type="radio" name="sex" :value="1" checked v-model="editForm.sex"><span>男</span>
                  </label>
                </div>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
              <button type="button" data-method="editSave" class="btn btn-primary" @click="doMethod.editSave()">
                保存修改
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 修改表单end -->

    <!-- 设定角色start -->
    <div class="modal fade" id="addUserRoleModal" tabindex="-1" role="dialog" aria-labelledby="addUserRoleModalLabel">
      <div class="modal-dialog" role="document">
        <div class="modal-content">
          <div class="modal-header">
            <button type="button" class="close" data-dismiss="modal" aria-label="Close">
              <span aria-hidden="true">&times;</span></button>
            <h4 class="modal-title" id="addUserRoleModalLabel">添加用户角色</h4>
          </div>
          <form id="addUserRoleForm" method="post" enctype="multipart/form-data">
            <div class="modal-body">
              <input type="hidden" id="addRole_userid" class="form-control" name="id" v-model="roleForm.userId">
              <div class="form-group">
                <label for="recipient-name" class="control-label">用户名：</label>
                <input type="text" id="addRole_username" class="form-control" name="username"
                       v-model="roleForm.username">
              </div>
              <div class="form-group">
                <p class="ad-section-title">选择角色</p>
                <div class="ad-role-picker">
                  <label v-for="role in roleList" :key="role.id" class="ad-role-picker__item">
                    <input name="roles[]" type="checkbox" class="checkbox-child" :id="'rid_' + role.id"
                           :value="String(role.id)" v-model="selectedRoleIds">
                    <span>{{ role.name }}</span>
                  </label>
                  <p v-if="!roleList.length" class="ad-help">暂无可分配的角色。</p>
                </div>
                <p class="ad-help">勾选后该用户即可看到对应角色的后台菜单；不勾选表示不分配任何角色。</p>
              </div>
            </div>
            <div class="modal-footer">
              <button type="button" class="btn btn-default" data-dismiss="modal">取消</button>
              <button type="button" data-method="addUserRoleSave" class="btn btn-primary"
                      @click="doMethod.addUserRoleSave()">保存角色
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
    <!-- 设定角色end -->
  </div>
</template>

<script setup>
/**
 * 用户管理 —— frontend/templates/admin/user.html 的 1:1 移植
 *
 * ✦ 表格替换（本页已彻底移除 bootstrap-table）✦
 *   · 旧实现：$('#userTable').bootstrapTable({ ajax, columns, pagination ... })
 *       - DOM（表头 / 表体 / 分页条 / 列筛选 / 刷新按钮）全由插件运行时拼字符串生成；
 *       - 操作列 formatter 拼 onclick="edit(id)" / onclick="del(id)"，依赖 window.edit / window.del；
 *       - 勾选行靠 $('#userTable').bootstrapTable('getSelections') 反查（批量删除 / 设定角色）；
 *       - 编辑回填靠 bootstrapTable('getRowByUniqueId', id)。
 *   · 新实现：Vue 渲染 <thead>/<tr v-for> + 计算属性筛选 + 自建分页；行内按钮 @click；
 *       勾选列 v-model="selectedIds"（表头全选 toggleAll），批量操作直接读 selectedIds；
 *       编辑回填改从本组件的 rows 里按 id 查（findRow）。
 *   · 已删除：bootstrapTable(...) 初始化、destroy、optFormatter、headImgFormatter、
 *       rolesFormatter、getRowByUniqueId、getSelections、刷新用的 bootstrapTable('refresh')、
 *       以及 bootstrap-table 的 icons/showColumns/showRefresh/pageList 等纯插件选项。
 *
 * 接口（web/src/api/admin.js）与 payload 完全没变：
 *   userList({ page, pageSize, username, email }) -> { total, rows }  （后端 order by id desc）
 *   userSave(User)                     新增 / 修改（带 id 即编辑）
 *   userDelete(id)                     删除
 *   userRestore({ id }) / ({ ids })    撤销恢复（单个 / 批量）
 *   adminRoles() / userRoles(userId) / userRoleSave({ userId, roleIds })
 *
 * 仍然使用 jQuery 的地方（有意保留，都不属于「表格插件」）：
 *   · bootstrap 3 弹窗 #userAddModal / #userEditModal / #addUserRoleModal；
 *   · bootstrapValidator 表单校验（#userAddForm / #userEditForm）；
 *   · fileinput 头像上传（#file-pic）；
 *   · $.confirm 兼容层（保存成功后的温馨提示）。
 *
 * 与原模板实现上的必要差异（均因「Thymeleaf 服务端渲染 → REST + SPA」）：
 * 1) 表格 url:'/user/listpage' → userList()；分页参数由 bootstrap-table 的 offset/limit 改回
 *    后端 UserQuery 的 page/pageSize（后端按 page/pageSize 取数，语义不变）。
 * 2) 列表 /user/listpage → userList()；删除 /user/deleteUser → userDelete(id)；
 *    批量删除后端无 REST 端点，改为逐个 userDelete（与原实现一致），撤销走 userRestore({ ids })。
 * 3) 新增/编辑 → userSave()；角色列表 adminRoles()、已有角色 userRoles()、
 *    保存 userRoleSave({ userId, roleIds })（roleIds 统一为字符串数组）。
 * 4) 头像 <img src> → avatarUrl(row.headImg)（其内部即 /showimage/ 前缀）。
 * 5) 排序：原表格 sortable:true 但在服务端分页模式下后端 UserQuery 没有排序字段，
 *    点表头实际不改变任何顺序；这里保持「默认沿用后端顺序（id 倒序）」，点表头才做前端排序，
 *    且仅作用于当前页（表头 title 已注明）。
 * 6) 原模板 add 分支里的 alert("init ok") 与 $("#userAddForm").serializeObject() 调试/旧依赖已去掉；
 *    新增成功后（未选头像时）关闭弹窗并刷新。
 * 7) 导出按钮保留原行为 location.href = '/user/downloadExcel'（后台登录已建立 Session）。
 * 8) 表格内联 onclick 依赖全局 edit()/del()（与原模板一致），挂载时挂到 window，卸载时移除。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { adminRoles, userDelete, userList, userRestore, userRoleSave, userRoles, userSave } from '@/api/admin'
import { avatarUrl } from '@/utils/format'
import { toast as dsToast, confirmDelete } from '@/utils/notify'

/* jQuery 由 admin.html 全局加载；这里在使用时取当前实例，避免加载顺序问题 */
const jq = () => window.jQuery

/* 查询条件（原模板只有「名称」搜索框；接口的 email 参数位保留） */
const query = ref({ username: '' })

/* 新增 / 编辑表单（对应原模板 #userAddForm / #userEditForm 的字段） */
const addForm = ref({ username: '', email: '', tel: '', password: '', confirmPassword: '', sex: 1 })
const editForm = ref({ id: '', username: '', email: '', tel: '', sex: 1 })

/* 设定角色弹窗（对应原模板 #addUserRoleForm + Thymeleaf ${roles} 渲染的复选框） */
const roleList = ref([])
const roleForm = ref({ userId: '', username: '' })
const selectedRoleIds = ref([])

/* 新增成功后返回的用户 id（原模板的模块级变量 userId，供 fileinput 上传携带） */
let userId = null
/* 卸载标记：避免异步回调在组件卸载后继续操作 DOM */
let disposed = false
/* jconfirm / $.confirm 兼容层弹窗实例，卸载时统一关闭 */
const dialogs = new Set()

/* ==================== 列表状态（Vue 表格数据源） ==================== */

/** 当前页数据（顺序 = 后端返回顺序：id 倒序） */
const rows = ref([])
/** 总条数（后端返回） */
const total = ref(0)
/** 当前页码（从 1 开始） */
const page = ref(1)
/** 每页条数（原 bootstrap-table pageList: [10, 15, 20]） */
const pageSize = ref(10)
/** 加载中（首屏即为 true，避免闪一下空状态） */
const loading = ref(true)
/** 勾选列选中的行 id（替代旧的 getSelections） */
const selectedIds = ref([])
/** 排序字段：'' = 沿用后端默认顺序（id 倒序），'id' / 'username' / 'email' = 前端排序 */
const sortKey = ref('')
/** 排序方向：'asc' | 'desc' */
const sortDir = ref('desc')

const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

/** 表头文案（排序 tip 用） */
const SORT_LABEL = { id: '用户编号', username: '用户名', email: '邮件' }

/** id 数值比较（缺失按 0 处理，避免 NaN 让 sort 结果不稳定） */
function numId(v) {
  const n = Number(v)
  return isFinite(n) ? n : 0
}

/**
 * 显示行 = 当前页数据 + 可选的前端排序。
 * 注意：排序只作用于「当前页」——旧实现是服务端分页（sidePagination: 'server'），
 * 后端 UserQuery 没有排序字段，点表头同样只影响当页（实际连当页都不变），因此行为等价。
 */
const displayRows = computed(() => {
  const list = rows.value
  if (!sortKey.value) return list
  const dir = sortDir.value === 'asc' ? 1 : -1
  const key = sortKey.value
  return list.slice().sort((a, b) => {
    let ret
    if (key === 'id') ret = numId(a.id) - numId(b.id)
    // 用户名 / 邮件按中文本地化比较；完全相同时退回 id 倒序，保证顺序稳定
    else ret = String(a[key] || '').localeCompare(String(b[key] || ''), 'zh-Hans-CN')
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
  const label = SORT_LABEL[key] || '该列'
  const tip = '（排序仅作用于当前页）'
  return sortKey.value !== key
    ? `点击按${label}排序${tip}`
    : `再点一次切换为${sortDir.value === 'asc' ? '倒序' : '正序'}${tip}`
}

/** 全选 / 取消全选（与回收站页同一套写法） */
function toggleAll(e) {
  selectedIds.value = e.target.checked ? displayRows.value.map(r => r.id) : []
}

/**
 * 性别列文案（原 formatter `row.sex == "1" ? "男" : "女"`）。
 * 注意：后端 User.sex 是 Boolean（tinyint 经 MyBatis getBoolean 映射），
 * 所以 JSON 里是 true / false；用 Number(sex) === 1 才能与原写法 `== "1"` 等价
 * （true → 1 → 男，false → 0 → 女；若后端某天回 "1"/"0" 字符串也同样成立）。
 */
function sexText(sex) {
  return Number(sex) === 1 ? '男' : '女'
}

/* ==================== 列表加载 ==================== */

/**
 * 拉取当前页（替代旧 bootstrap-table 的 ajax 选项）。
 * 失败时清空列表并提示（旧实现的 ajax error 分支只显示空表、不告诉用户原因）。
 */
async function load() {
  loading.value = true
  let data = null
  try {
    data = await userList({
      page: page.value,
      pageSize: pageSize.value,
      username: query.value.username || undefined,
      email: undefined                    // 原模板无邮箱搜索框，保留接口参数位
    })
  } catch (err) {
    if (disposed) return
    rows.value = []
    total.value = 0
    selectedIds.value = []
    loading.value = false
    dsToast.error((err && err.message) || '用户列表加载失败')
    return
  }
  if (disposed) return

  rows.value = (data && Array.isArray(data.rows)) ? data.rows : []
  total.value = Number((data && data.total) || 0)
  // 勾选只保留仍在当前页的记录，避免勾选态被带到已经翻走 / 已删除的数据上
  const alive = new Set(rows.value.map(r => String(r.id)))
  selectedIds.value = selectedIds.value.filter(id => alive.has(String(id)))

  // 删掉末页最后一条后自动回到有效页，不停在空白页
  const target = Math.min(page.value, maxPage.value)
  if (!rows.value.length && target < page.value) {
    page.value = target
    return load()
  }
  loading.value = false
}

/**
 * 刷新列表：原 $("#userTable").bootstrapTable('refresh') 的等价实现。
 * 返回 load() 的 Promise，调用方（删除保存后的提示链路）需要等刷新真正完成再弹成功提示，
 * 否则会出现「提示已删除、列表却还是旧数据」的错觉。
 */
function refreshList() {
  if (disposed) return Promise.resolve()
  return load()
}

/** 回到第一页并刷新（也是搜索 / 点「刷新」的入口） */
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

/**
 * 当前勾选的行（替代旧的 $("#userTable").bootstrapTable("getSelections")）。
 * 用 String(id) 比对，兼容后端 id 为数字、checkbox 值为字符串的场景；
 * 顺序 = 当前显示顺序（displayRows），与旧插件返回的顺序语义一致。
 */
function selectedRows() {
  const ids = new Set(selectedIds.value.map(String))
  return displayRows.value.filter(r => ids.has(String(r.id)))
}

/* ==================== 提示 / 校验（沿用原实现） ==================== */

/** 提示（原模板 $.confirm 的快捷分支；文案与类型保持不变） */
function notify(content, type = 'green') {
  const fn = type === 'red' ? dsToast.error : type === 'orange' ? dsToast.warning : dsToast.success
  fn(content)
}

/** jconfirm / $.confirm 兼容层弹窗（原写法保留；统一登记以便卸载时关闭） */
function confirmBox(options) {
  const $ = jq()
  if (!$ || typeof $.confirm !== 'function') {
    window.alert(options.content)
    return null
  }
  const box = $.confirm(options)
  dialogs.add(box)
  return box
}

/** bootstrapValidator 校验（沿用后台其它页面的调用方式；插件未就绪时放行，避免页面不可用） */
function validateForm(selector) {
  const $ = jq()
  const validator = $(selector).data('bootstrapValidator')
  if (!validator) return true
  validator.validate()
  return validator.isValid()
}

/* ==================== 行操作（表格内按钮直接调用，同名保留） ==================== */

/** 编辑：打开 #userEditModal 并回填行数据（行数据改从 Vue 的 rows 里取，替代 getRowByUniqueId） */
function edit(id) {
  const $ = jq()
  const row = findRow(id)
  if (!row) {
    dsToast.error('未找到该用户，请刷新后重试')
    return
  }
  $('#userEditModal').modal({
    show: true,
    backdrop: 'static'
  });
  //重置表单校验状态（原模板是 form.reset() 后逐项回填，这里回填 Vue 表单状态）
  const validator = $('#userEditForm').data('bootstrapValidator');
  if (validator) validator.resetForm();

  editForm.value = {
    id: row.id,
    username: row.username,
    email: row.email,
    tel: row.tel,
    sex: row.sex ? 1 : 0
  }
}

/** 删除：先二次确认（带用户名，说明不可恢复），确认后删除并给出「撤销」提示 */
function del(id) {
  const row = findRow(id)
  const label = (row && (row.username || row.email)) || '该用户'
  confirmDelete({
    name: label,
    extra: '账号与其资料会被删除，历史评论会保留但不再显示昵称；操作不可恢复。',
    onConfirm: async () => {
      // 失败时先给出明确提示，再把异常抛给 settleDialog，让弹窗按「失败」收尾
      // （原实现用 .catch 吞掉异常，弹窗会误判为成功）。
      try {
        await userDelete(id)
      } catch (err) {
        if (!disposed) notify((err && err.message) || '删除失败，请稍后重试', 'red')
        throw err
      }
      if (disposed) return
      // 关键：先等列表刷新完成，再提示成功并刷新列表
      await refreshList()
      if (disposed) return
      dsToast.withUndo(`已删除用户「${label}」`, async () => {
        await userRestore({ id })
        if (disposed) return
        await refreshList()
        dsToast.success(`已恢复用户「${label}」`)
      })
    }
  })
}

/* ---------------------------------------------------------------
 * 原模板的 doMethod（按钮原本通过 data-method 分发；SPA 下改为 @click 调用同一组方法，
 * data-method 属性仍原样保留）
 * ------------------------------------------------------------- */
const doMethod = {
  exportData: function () {
    // 后台登录已建立 Session，原 Thymeleaf 导出端点可直接使用
    window.location.href = '/user/downloadExcel'
  },
  //条件用户角色弹框
  addUserRole: async function () {
    const $ = jq()
    // 勾选行：原 $("#userTable").bootstrapTable("getSelections")
    const addRoleRows = selectedRows();
    if (addRoleRows.length <= 0) {
      dsToast.warning('请选中一行进行操作')
      return;
    }
    //编辑id
    var roleUserId = addRoleRows[0].id;
    var username = addRoleRows[0].username;
    roleForm.value = { userId: String(roleUserId), username: username }
    //清空所有的
    selectedRoleIds.value = []
    //设置权限（REST：userRoles(userId) 取该用户已有角色）
    try {
      const roles = await userRoles(roleUserId)
      selectedRoleIds.value = (roles || []).map(r => String(r.id))
    } catch (e) {
      // 接口异常时退回表格行自带的角色数据
      selectedRoleIds.value = (addRoleRows[0].roles || []).map(r => String(r.id))
    }
    if (disposed) return
    //添加用户角色
    $('#addUserRoleModal').modal({
      show: true,
      backdrop: 'static'
    })
  },
  addUserRoleSave: function () {
    //保存用户的角色 { userId: "18", roleIds: ["1","2"] }
    const $ = jq()
    const paramObj = { userId: roleForm.value.userId, roleIds: selectedRoleIds.value }
    userRoleSave(paramObj).then(() => {
      if (disposed) return
      confirmBox({
        title: '温馨提示',
        content: '保存成功',
        type: 'green',
        buttons: {
          omg: {
            text: '谢谢',
            btnClass: 'btn-green'
          }
        }
      })
      // 注意：$.confirm 兼容层把「只有一个按钮」的调用折叠成顶部轻提示，
      // 按钮 action 不会执行 —— 所以关闭弹窗 / 刷新列表必须放在这里（原实现的 bug 修复）。
      $('#addUserRoleModal').modal('hide');
      refreshList();
    }).catch(err => {
      if (disposed) return
      notify((err && err.message) || '保存失败', 'red')
    })
  },

  delBatch: function () {
    // 勾选行：原 $("#userTable").bootstrapTable("getSelections")
    const delRows = selectedRows();
    if (delRows.length <= 0) {
      dsToast.warning('请先选择要删除的用户')
      return
    }
    const ids = delRows.map(row => row.id)
    confirmDelete({
      target: '选中的 ' + delRows.length + ' 个用户',
      extra: '账号与资料会被删除，其历史评论会保留但不再显示昵称；操作不可恢复。',
      onConfirm: async () => {
        try {
          await Promise.all(ids.map(id => userDelete(id)))
        } catch (err) {
          // 批量删除可能「部分成功」：无论成败都先刷新列表，
          // 让界面反映数据库的真实状态，而不是停留在删除前的旧数据上。
          if (!disposed) {
            await refreshList()
            notify((err && err.message) || '删除失败，请稍后重试', 'red')
          }
          throw err
        }
        if (disposed) return
        await refreshList()
        if (disposed) return
        dsToast.withUndo(`已删除 ${ids.length} 个用户`, async () => {
          await userRestore({ ids })
          if (disposed) return
          await refreshList()
          dsToast.success(`已恢复 ${ids.length} 个用户`)
        })
      }
    })
  },
  add: function () {
    jq()('#userAddModal').modal({
      show: true,
      backdrop: 'static'
    });

  },
  search: function () {
    // 搜索条件由 load() 读取 query.username，这里只回到第一页刷新
    reload()
  },
  save: function () {
    //提交表单
    const $ = jq()
    if (!validateForm('#userAddForm')) return;
    userSave({
      username: addForm.value.username,
      email: addForm.value.email,
      tel: addForm.value.tel,
      password: addForm.value.password,
      sex: addForm.value.sex
    }).then(async () => {
      if (disposed) return
      //不上传图片时，不触发bootstrap 上传插件的初始化方法。仅将表单里面的（除图片以外的）内容提交，
      if ($("#file-pic").val() != "") {
        // 原模板 addUser 会返回新用户 id 供上传插件使用；REST 版按用户名回查一次
        userId = await resolveUserId(addForm.value.username)
        if (disposed) return
        $('#file-pic').fileinput('upload'); //触发插件开始上传。
      } else {
        $('#userAddModal').modal('hide');
        refreshList();
      }
    }).catch(err => {
      if (disposed) return
      notify((err && err.message) || '保存失败', 'red')
    })
  },
  editSave: function () {
    //提交表单
    if (!validateForm('#userEditForm')) return;
    // 字段与原模板 #userEditForm 一致（id/username/email/tel/sex）。
    // 注意：后端 editSaveUser 的 SQL 为 set username,email,sex,phone,nickname —— 没有传 nickname，
    //      会把它置空；这是原模板就有的行为（表单里没有 nickname 字段），此处保持 1:1 未做修改。
    userSave({
      id: editForm.value.id,
      username: editForm.value.username,
      email: editForm.value.email,
      tel: editForm.value.tel,
      sex: editForm.value.sex
    }).then(() => {
      if (disposed) return
      notify('修改成功')
      $('#userEditModal').modal('hide');
      refreshList();
    }).catch(err => {
      if (disposed) return
      notify((err && err.message) || '修改失败', 'red')
    })
  }
}

/** 新增后按用户名回查用户 id（/file/uploadFile 需要 id 才能更新头像） */
async function resolveUserId(username) {
  try {
    const res = await userList({ page: 1, pageSize: 10, username })
    const row = (res.rows || []).find(r => r.username === username)
    return row ? row.id : null
  } catch (e) {
    return null
  }
}

/* ---------------------------------------------------------------
 * 角色列表（原模板由 Thymeleaf ${roles} 渲染）
 * ------------------------------------------------------------- */
async function loadRoles() {
  try {
    roleList.value = (await adminRoles()) || []
  } catch (e) {
    roleList.value = []
  }
}

/* ---------------------------------------------------------------
 * 文件上传插件（逐字照抄原模板配置）
 * ------------------------------------------------------------- */
function initFileInput() {
  const $ = jq()
  $('#file-pic').fileinput({
    //初始化上传文件框
    language: "zh",//配置语言
    showUpload: false, //显示整体上传的按钮
    showRemove: true,//显示整体删除的按钮
    uploadAsync: true,//默认异步上传
    uploadLabel: "上传",//设置整体上传按钮的汉字
    removeLabel: "移除",//设置整体删除按钮的汉字
    uploadClass: "btn btn-primary",//设置上传按钮样式
    showCaption: true,//是否显示标题
    dropZoneEnabled: false,//是否显示拖拽区域
    uploadUrl: '/file/uploadFile',//这个是配置上传调取的后台地址，本项目是SSM搭建的
    maxFileSize: 9999,//文件大小限制
    maxFileCount: 9999,//允许最大上传数，可以多个，
    enctype: 'multipart/form-data',
    allowedFileExtensions: ["jpg", "png", "gif", "docx", "zip", "xlsx", "txt"],/*上传文件格式限制*/
    msgFilesTooMany: "选择上传的文件数量({n}) 超过允许的最大数值{m}！",
    showBrowse: true,
    browseOnZoneClick: true,
    slugCallback: function (filename) {
      return filename.replace('(', '_').replace(']', '_');
    },
    uploadExtraData: function (previewId, index) {   //额外参数的关键点
      //{ id: userId }
      return { id: userId };
    }
  });

  $('#file-pic').on("fileuploaded", function (event, data, previewId, index) {
    if (disposed) return
    var response = data.response;
    if (response.isSuccess) {
      notify('保存成功')
      $('#userAddModal').modal('hide');
      refreshList();
    } else {
      notify('操作失败', 'red')
    }
  });

  // 上传失败（原模板只处理了成功分支，失败时没有任何反馈）
  $('#file-pic').on("fileuploaderror", function () {
    if (disposed) return
    notify('操作失败', 'red')
  });
}

/* ---------------------------------------------------------------
 * bootstrapValidator 校验规则（逐字照抄原模板）
 * ------------------------------------------------------------- */
function initValidator() {
  const $ = jq()
  $('#userAddForm,#userEditForm').bootstrapValidator({
    live: 'enabled',//字段值有变化就触发验证 disabled,submitted 当点击提交时验证并展示错误信息
    message: '信息不合法',
    feedbackIcons: {
      valid: 'glyphicon glyphicon-ok',
      invalid: 'glyphicon glyphicon-remove',
      validating: 'glyphicon glyphicon-refresh'
    },
    fields: {
      username: {
        message: '用户名不合法',
        validators: {
          notEmpty: {
            message: '用户名必须填写,不能为空'
          },
          stringLength: {
            min: 3,
            max: 20,
            message: '长度必须是3到20个字符'
          }
        }
      },
      tel: {
        message: '电话不合法',
        validators: {
          notEmpty: {
            message: '电话号码,不能为空'
          },
          regexp: {
            regexp: /^(13[0-9]|14[01456879]|15[0-35-9]|16[2567]|17[0-8]|18[0-9]|19[0-35-9])\d{8}$/,
            message: '手机号格式错误'
          }
        }
      },
      email: {
        validators: {
          notEmpty: {
            message: '邮箱不能空'
          },
          emailAddress: {
            message: '输入邮箱不合格'
          },
          regexp: {
            regexp: /^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\.[a-zA-Z0-9_-]+)+$/,
            message: '邮箱格式错误'
          }
        }
      },
      password: {
        validators: {
          notEmpty: {
            message: '密码不能为空'
          }
        }
      },
      confirmPassword: {
        validators: {
          notEmpty: {
            message: '确认密码不能为空'
          },
          identical: {
            field: 'password',
            message: '两次密码不一样哟...'
          },
          different: {
            field: 'username',
            message: '密码不能和用户名相同'
          }
        }
      }
    }
  });
}

onMounted(async () => {
  await nextTick()
  if (disposed) return
  // 表格行内按钮已改用 @click，但仍按原样挂全局 edit()/del()（外部脚本按 id 调用时行为不变）
  window.edit = edit
  window.del = del
  initFileInput()
  initValidator()
  loadRoles()
  load()   // 表格数据由 Vue 组件自己拉取（旧实现是 bootstrap-table 的 ajax 选项）
})

onBeforeUnmount(() => {
  // 清理阶段整体兜底：任何清理异常都不能中断 SPA 路由切换
  // （曾因某个页面卸载清理抛错，导致之后所有后台菜单点击都失效）
  try {
    disposed = true
    const $ = jq()

    // 全局函数（避免污染后台其余路由）
    if (window.edit === edit) delete window.edit
    if (window.del === del) delete window.del

    if ($) {
      // 移除本组件在表单 / 上传控件上注册的事件
      try { $('#userAddForm,#userEditForm').off() } catch (e) { /* 忽略 */ }
      try { $('#file-pic').off() } catch (e) { /* 忽略 */ }
      // 销毁校验器与上传插件
      try {
        if ($('#userAddForm').data('bootstrapValidator')) $('#userAddForm').bootstrapValidator('destroy')
        if ($('#userEditForm').data('bootstrapValidator')) $('#userEditForm').bootstrapValidator('destroy')
      } catch (e) { /* 未初始化 */ }
      try { $('#file-pic').fileinput('destroy') } catch (e) { /* 未初始化 */ }
      // 自建 modal：bootstrap 会把遮罩与 body 上的类留在组件之外，必须手动清理
      try {
        $('#userAddModal,#userEditModal,#addUserRoleModal').modal('hide')
        $('.modal-backdrop').remove()
        $('body').removeClass('modal-open').css('padding-right', '')
      } catch (e) { /* 忽略 */ }
    }

    // $.confirm 兼容层弹窗残留
    dialogs.forEach(box => {
      try {
        if (!box) return
        if (typeof box.close === 'function') box.close()
        if (box.$el) box.$el.remove()
        if (box.$jconfirmBg) box.$jconfirmBg.remove()
      } catch (e) { /* 已关闭 */ }
    })
    dialogs.clear()

    // 说明：原本这里还有 bootstrapTable('destroy') 与 $('#userTable').off()；
    // 表格已改由 Vue 渲染，相关 DOM 随组件卸载自动回收，不再需要销毁表格插件实例
    // （也不再依赖 $.fn.bootstrapTable）。

    // 说明：本组件没有注册 document/window 级全局事件（Vue @click 随组件自动解绑），
    //       故不做全局 off，避免误伤其它页面/插件的监听器；原页面也没有定时器需要清理。

  } catch (err) {
    console.warn('[cleanup]', err && err.message)
  }
})
</script>

<style scoped>
/* 原 user.html 页内的 .my-container / .myLabel-content / .myText-content / .myBtn-content
   样式块已随工具栏改版移除（这些类在后台已无使用方），本页样式改为 scoped。 */

/* 搜索区：标签与输入框同一行，窄屏可换行（.ad-toolbar 已支持 flex-wrap） */
.ad-label { margin: 0; font-weight: 400; color: var(--ad-text-sub); font-size: 12.5px; white-space: nowrap; }
.ad-input { width: 200px; }
.ad-header__actions .ad-input { width: 180px; }

/* 每页条数下拉宽度 + 分页条紧贴卡片脚 */
.ad-select { width: 130px; }
.ad-pager { margin: 0; }

/* 可排序表头（点表头切换排序，仅作用于当前页） */
.ad-sort { cursor: pointer; user-select: none; white-space: nowrap; }
.ad-sort:hover { color: var(--ad-primary); }
.ad-sort i { margin-left: 4px; color: var(--ad-muted); }

/* 行内操作按钮间距（旧 bootstrap-table 的两个按钮挨在一起，这里保持同样紧凑） */
.ad-actions .btn + .btn { margin-left: 4px; }

/* 所属角色：多个 label 之间保留原 formatter 拼串时的间距 */
.ad-role-tag { margin-right: 4px; }

/* 角色勾选：替代原来被拉成整行的 col-xs-12 复选框栅格 */
.ad-role-picker { display: flex; flex-wrap: wrap; gap: 6px 18px; }
.ad-role-picker__item { display: inline-flex; align-items: center; gap: 6px; margin: 0; font-weight: 400; font-size: 13px; }
.ad-role-picker__item input { margin: 0; }
</style>
