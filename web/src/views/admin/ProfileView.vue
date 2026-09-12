<template>
  <!--
    个人信息 —— 原 Thymeleaf 模板 frontend/templates/admin/me.html 的 1:1 移植。
    只渲染原 <main class="coder-layout-content"> 内部内容（侧边栏与顶栏由 AdminLayout.vue 提供）。
    登录用户原为 Session 的 loginUser（UserController#info → model.loginUser），现改为
    GET /api/admin/me（@/api/admin.js::adminProfile）→ { user, admin }，用 data.user 渲染。
    【头像】原 th:src="@{'/showimage/'+${loginUser.headImg}}" → @/utils/format.js::avatarUrl(user.headImg)。

    本次仅调整排版：#stuEditForm / #e_id / name="username|email|tel" 以及所有 :value 绑定保持原样，
    原来的「伪装成弹窗」结构（.modal-dialog + float:left + width:100% + 黑底）改为标准页头 + 卡片 + 只读表单。
  -->
  <div class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-account-circle"
      title="个人信息"
      desc="当前登录账号的基本资料（只读）。如需修改用户名、邮箱或手机号，请到「用户管理」中操作。"
    />

    <div class="ad-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title"><i class="mdi mdi-account-card-details"></i> 账号资料</h5>
      </div>
      <div class="ad-card__body">
        <form id="stuEditForm" method="post" enctype="multipart/form-data">
          <input type="hidden" id="e_id" class="form-control" name="id">

          <div class="row">
            <div class="col-sm-3 col-xs-12">
              <p class="ad-section-title">头像</p>
              <div class="ad-avatar">
                <img height="160" width="160" :src="avatar" alt="用户头像">
              </div>
              <p class="ad-help">头像可在「用户管理」中重新上传。</p>
            </div>

            <div class="col-sm-9 col-xs-12">
              <p class="ad-section-title">基本信息</p>
              <div class="ad-form-grid">
                <div class="form-group">
                  <label class="control-label">用户名：</label>
                  <input type="text" readonly :value="user.username" class="form-control" name="username">
                </div>
                <div class="form-group">
                  <label class="control-label">邮箱：</label>
                  <input type="text" readonly :value="user.email" class="form-control" name="email">
                </div>
                <div class="form-group">
                  <label class="control-label">电话号码：</label>
                  <input type="text" readonly :value="user.tel" class="form-control" name="tel">
                </div>
                <div class="form-group">
                  <label class="control-label">性别：</label>
                  <p class="ad-readonly">{{ user.sex ? '男' : '女' }}</p>
                </div>
              </div>
              <p class="ad-help">以上资料由管理员维护，页面本身不提供修改入口。</p>
            </div>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 个人信息（移植自 frontend/templates/admin/me.html）
 *
 * 数据：GET /api/admin/me（@/api/admin.js::adminProfile）→ { user, admin }
 *   user.username / user.email / user.tel / user.sex / user.headImg（avatarUrl）
 * 原模板是纯只读展示表单：没有任何 fileinput 上传控件、没有任何提交/删除/退出登录按钮，
 * 因此这里不注册上传与 AJAX（原页脚引入的 jquery.serialize-object.min.js 在本页无调用点，未加载）。
 * 页面本身无定时器、无全局事件绑定，卸载时无需额外清理。
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import { adminProfile } from '@/api/admin'
import { avatarUrl } from '@/utils/format'

/** 原 Session 中的 loginUser */
const user = ref({})

/** 头像：原 /showimage/{headImg}，由 avatarUrl 生成同一地址 */
const avatar = computed(() => avatarUrl(user.value && user.value.headImg))

onMounted(async () => {
  await nextTick()
  try {
    const data = await adminProfile()
    user.value = (data && data.user) || {}
  } catch (e) {
    // 401/403 已由 adminHttp 拦截器统一处理（清 Token 并跳登录页），其余仅提示
    console.warn('[ProfileView] 个人信息加载失败：', e && e.message)
  }
})
</script>

<style scoped>
/* 头像：原模板的 200×200 内联尺寸收敛到卡片内，统一圆角 */
.ad-avatar {
  width: 160px;
  height: 160px;
  border: 1px solid var(--ad-border);
  border-radius: var(--ad-radius);
  background: #f8fafc;
  overflow: hidden;
}
.ad-avatar img { display: block; }

/* 只读值（性别等非输入项）与表单控件视觉对齐 */
.ad-readonly {
  margin: 0;
  padding: 6px 12px;
  border: 1px dashed var(--ad-border-strong);
  border-radius: 4px;
  background: #fafbfc;
  color: var(--ad-text-sub);
  font-size: 13px;
}
</style>
