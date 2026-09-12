<template>
  <AuthShell title="落款" slogan="人间情书，为每一份心动署名" page="signin">
    <form class="auth-form" id="login-form" novalidate @submit.prevent="onSubmit">
      <div class="auth-field">
        <input class="auth-input" id="email" name="email" type="email" placeholder="邮箱地址"
          aria-label="邮箱地址" autocomplete="email" required ref="emailInput" v-model="account">
        <p class="auth-error" id="email-error" role="alert" :hidden="!emailError">{{ emailError }}</p>
      </div>
      <div class="auth-field auth-field--inset">
        <input class="auth-input" id="password" name="password" type="password" placeholder="密码"
          aria-label="密码" autocomplete="current-password" required ref="passwordInput" v-model="password">
        <p class="auth-error" id="password-error" role="alert" :hidden="!passwordError">{{ passwordError }}</p>
        <router-link class="auth-inside" to="/auth/forgot">忘记密码</router-link>
      </div>
      <div class="auth-row">
        <button class="auth-button" type="submit" :disabled="submitting">{{ submitting ? '登录中…' : '登录' }}</button>
        <button class="auth-button auth-button--ghost" type="button" id="go-register" @click="goRegister">注册</button>
      </div>
    </form>
    <p style="margin:14px 0 0;font-size:12px;line-height:1.6;color:#8a8f99;text-align:center;">
      前台用户登录（Token 与后台独立）<br>
      管理员请走 <a href="/admin/login" style="color:#4a90d9;text-decoration:none;">后台登录入口</a>
    </p>
  </AuthShell>
</template>

<script setup>
/**
 * 前台登录页（用户端）：邮箱或用户名 + 密码
 * 迁移自 static/auth/login.html + static/auth/js/login-auth.js，DOM/class/校验/提示行为保持一致。
 *
 * 接口：frontLogin({ account, password }) -> POST /api/auth/login
 * 前台专用 Token（localStorage.culture_front_token）由 @/api/auth.js 内部保存，
 * 前台登录不会获得后台权限；后台请走 /admin/login。
 */
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import AuthShell from '@/components/auth/AuthShell.vue'
import { frontLogin } from '@/api/auth'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/

const route = useRoute()

const account = ref('')
const password = ref('')
const emailError = ref('')
const passwordError = ref('')
const submitting = ref(false)

const emailInput = ref(null)
const passwordInput = ref(null)

/** 登录成功后的落地页：支持 ?redirect=/culture 之类回跳，默认首页 */
function afterLoginTarget() {
  const raw = route.query.redirect
  const redirect = Array.isArray(raw) ? raw[0] : raw
  if (!redirect) return '/'
  // 仅允许站内相对路径，避免开放重定向
  if (redirect.startsWith('/') && !redirect.startsWith('//')) return redirect
  return '/'
}

/**
 * 错误文案：与原 login-auth.js 一致 —— 优先展示后端 message，
 * 拿不到 message 或网络不可用（http 层会透出 axios 的 "Network Error"）时，
 * 回落到原页面的「无法连接服务器，请确认后端已启动」。
 */
function errorMessage(err) {
  const message = err && err.message
  return !message || message === 'Network Error' ? '无法连接服务器，请确认后端已启动' : message
}

/** 去注册 -> 跳转独立注册页（与原 js 一致，整页跳转以重放 orb 背景动画） */
function goRegister() {
  window.location.href = '/auth/register'
}

async function onSubmit() {
  const value = account.value.trim()
  const pwd = password.value

  emailError.value = ''
  passwordError.value = ''

  // 支持邮箱或用户名：含 @ 按邮箱校验，否则按用户名
  const isEmail = value.includes('@')
  if (!value) { emailError.value = '请输入邮箱或用户名'; emailInput.value && emailInput.value.focus(); return }
  if (isEmail && !EMAIL_RE.test(value)) { emailError.value = '请输入有效的邮箱地址'; emailInput.value && emailInput.value.focus(); return }
  if (!pwd) { passwordError.value = '请输入密码'; passwordInput.value && passwordInput.value.focus(); return }

  submitting.value = true

  try {
    // 前台 Token 单独存放，绝不与后台 Token 混用（由 frontLogin 内部完成）
    await frontLogin({ account: value, password: pwd })
    window.location.href = afterLoginTarget()
  } catch (err) {
    submitting.value = false
    // 账号不存在 -> 提示在账号框下方；其余错误（密码错误等）提示在密码框下方
    if (err.message === '该账号尚未注册，请先注册') {
      emailError.value = err.message
      emailInput.value && emailInput.value.focus()
    } else {
      passwordError.value = errorMessage(err)
      passwordInput.value && passwordInput.value.focus()
    }
  }
}
</script>
