<template>
  <AuthShell title="后台登录" slogan="仅限管理员账号，令牌与前台登录相互独立" page="admin-signin">
    <template #badge><span class="admin-badge">管理后台</span></template>

    <!-- 原 admin-login.html #admin-login-form -->
    <form class="auth-form" id="admin-login-form" novalidate @submit.prevent="handleSubmit">
      <div class="auth-field">
        <input class="auth-input" id="account" name="account" type="text" placeholder="管理员账号 / 邮箱"
          aria-label="管理员账号或邮箱" autocomplete="username" required
          ref="accountInput" v-model="account">
        <p class="auth-error" id="account-error" role="alert" :hidden="!accountError" v-text="accountError"></p>
      </div>
      <div class="auth-field auth-field--inset">
        <input class="auth-input" id="password" name="password" type="password" placeholder="密码"
          aria-label="密码" autocomplete="current-password" required
          ref="passwordInput" v-model="password">
        <p class="auth-error" id="password-error" role="alert" :hidden="!passwordError" v-text="passwordError"></p>
      </div>
      <div class="auth-row">
        <button class="auth-button" type="submit" :disabled="submitting">{{ submitText }}</button>
        <button class="auth-button auth-button--ghost" type="button" id="go-front-login"
          @click="goFrontLogin">前台登录</button>
      </div>
    </form>
    <p class="admin-note">
      登录成功后跳转 <a href="/admin">/admin</a> 管理后台；<br>
      前台用户请使用 <a href="/auth/login">前台登录入口</a>。
    </p>
  </AuthShell>
</template>

<script setup>
/**
 * 后台登录页（原 static/auth/admin-login.html + js/admin-login.js 的 1:1 移植）
 *
 * 接口：POST /auth/admin/login（@/api/auth.js 的 adminLogin）
 *  - 必须是「管理员」角色，否则后端直接拒绝；
 *  - 返回后台专用 Token（另一套密钥签发），由 adminLogin 存入 localStorage.culture_admin_token；
 *  - 登录成功后跳转 /admin。
 * 说明：.admin-badge / .admin-note 的样式在原 web/admin-login.html 的 <style> 中已原样保留，此处不再重复。
 */
import { ref } from 'vue'
import AuthShell from '@/components/auth/AuthShell.vue'
import { adminLogin } from '@/api/auth'

const accountInput = ref(null)
const passwordInput = ref(null)

const account = ref('')
const password = ref('')
const accountError = ref('')
const passwordError = ref('')

const submitting = ref(false)
const submitText = ref('进入后台')

async function handleSubmit() {
  const value = account.value.trim()
  const pwd = password.value

  accountError.value = ''
  passwordError.value = ''

  if (!value) {
    accountError.value = '请输入管理员账号或邮箱'
    accountInput.value && accountInput.value.focus()
    return
  }
  if (!pwd) {
    passwordError.value = '请输入密码'
    passwordInput.value && passwordInput.value.focus()
    return
  }

  submitting.value = true
  submitText.value = '登录中…'

  try {
    await adminLogin({ account: value, password: pwd })
    // 后台 Token 由 adminLogin 单独存放（culture_admin_token），不与前台 Token 混用
    window.location.assign('/admin')
  } catch (err) {
    submitting.value = false
    submitText.value = '进入后台'
    const msg = (err && err.message) || '无法连接服务器，请确认后端已启动'
    // 含「密码」二字时提示在密码框下方，其余（账号不存在等）提示在账号框下方
    if (msg.indexOf('密码') > -1) {
      passwordError.value = msg
      passwordInput.value && passwordInput.value.focus()
    } else {
      accountError.value = msg
      accountInput.value && accountInput.value.focus()
    }
  }
}

/** 「前台登录」按钮 -> 前台登录页（独立入口） */
function goFrontLogin() {
  window.location.assign('/auth/login')
}
</script>
