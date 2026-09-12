<template>
  <AuthShell title="落款" slogan="人间情书，为每一份心动署名" page="forgot">
    <!-- 第一步：输入注册邮箱（原 forgot.html #email-form） -->
    <form class="auth-form" id="email-form" novalidate :hidden="emailFormHidden" @submit.prevent="handleSendCode">
      <p class="auth-hint">输入注册邮箱，我们将发送验证码帮你重置密码</p>
      <div class="auth-field">
        <input class="auth-input" id="email" name="email" type="email" placeholder="邮箱地址"
          aria-label="邮箱地址" autocomplete="email" required ref="emailInput" v-model="email">
        <p class="auth-error" id="email-error" role="alert" :hidden="!emailError" v-text="emailError"></p>
      </div>
      <button class="auth-button" type="submit">发送验证码</button>
    </form>

    <!-- 第二步：验证码 + 新密码（原 forgot.html #reset-form） -->
    <form class="auth-form" id="reset-form" novalidate :hidden="resetFormHidden" @submit.prevent="handleReset">
      <p class="auth-hint" id="send-hint" v-text="sendHint"></p>
      <div class="auth-field auth-field--inset">
        <input class="auth-input auth-code" id="code" name="code" type="text" inputmode="numeric"
          maxlength="6" pattern="[0-9]{6}" placeholder="6位验证码" aria-label="验证码"
          autocomplete="one-time-code" required ref="codeInput" v-model="code">
        <p class="auth-error" id="code-error" role="alert" :hidden="!codeError" v-text="codeError"></p>
        <button class="auth-inside" type="button" id="resend-btn" :disabled="resendDisabled"
          @click="handleResend">{{ resendText }}</button>
      </div>
      <div class="auth-field">
        <input class="auth-input" id="new-password" name="new-password" type="password"
          placeholder="新密码" aria-label="新密码" autocomplete="new-password" required
          ref="passwordInput" v-model="password">
        <p class="auth-error" id="password-error" role="alert" :hidden="!passwordError" v-text="passwordError"></p>
      </div>
      <button class="auth-button" type="submit">重置密码</button>
    </form>

    <!-- 成功态（原 forgot.html #auth-success） -->
    <div class="auth-success" id="auth-success" :hidden="successHidden">
      <svg class="auth-check" viewBox="0 0 52 52" aria-hidden="true">
        <circle cx="26" cy="26" r="24"></circle>
        <path d="M14 27l8 8 16-16"></path>
      </svg>
      <p class="auth-success-title">密码已重置</p>
      <p class="auth-success-sub" id="success-sub">请使用新密码登录</p>
      <p class="auth-success-note" id="success-note" v-text="successNote"></p>
    </div>
  </AuthShell>
</template>

<script setup>
/**
 * 找回密码页（原 static/auth/forgot.html + js/forgot-auth.js 的 1:1 移植）
 *
 * 两段式：邮箱 -> 验证码 + 新密码
 *  - POST /auth/send-code  { email, scene: 'forgot' }
 *  - POST /auth/reset      { email, code, newPassword }
 * 校验位置、错误文案、60 秒重发倒计时、5 秒成功跳转均与原 js 保持一致。
 */
import { nextTick, onBeforeUnmount, ref } from 'vue'
import AuthShell from '@/components/auth/AuthShell.vue'
import { resetPassword, sendCode } from '@/api/auth'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/
const RESEND_SECONDS = 60
const REDIRECT_MS = 5000

/* 模板引用（对应原 js 的 form.elements / getElementById） */
const emailInput = ref(null)
const codeInput = ref(null)
const passwordInput = ref(null)

/* 表单状态 */
const email = ref('')
const code = ref('')
const password = ref('')

/* 错误提示（对应 setError：有文案即显示，空即 hidden） */
const emailError = ref('')
const codeError = ref('')
const passwordError = ref('')
const sendHint = ref('')
const successNote = ref('')

/* 三个区块的显隐（对应原 js 的 el.hidden） */
const emailFormHidden = ref(false)
const resetFormHidden = ref(true)
const successHidden = ref(true)

/* 重发按钮（对应 resendBtn.disabled / textContent） */
const resendText = ref('重新发送')
const resendDisabled = ref(false)

let lastEmail = ''
let resendTimer = null
let redirectTimer = null

/** 当前时间 HH:MM（用于「验证码已发送至 xxx（HH:MM）」） */
function nowTime() {
  const d = new Date()
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
}

/** 60 秒重发倒计时：文案「已发送{n}S」，归零后恢复「重新发送」（与原 js 的 setTimeout 链一致） */
function startResendCountdown() {
  clearTimeout(resendTimer)
  resendDisabled.value = true
  let left = RESEND_SECONDS
  const tick = () => {
    resendText.value = '已发送' + left + 'S'
    left -= 1
    if (left < 0) {
      resendDisabled.value = false
      resendText.value = '重新发送'
    } else {
      resendTimer = setTimeout(tick, 1000)
    }
  }
  tick()
}

/** 发送验证码（场景 forgot：仅限已注册邮箱） */
async function requestSendCode(target) {
  const data = await sendCode({ email: target, scene: 'forgot' })
  lastEmail = target
  // 本地联调：后端返回验证码时自动填入（生产环境后端不返回，需查收邮件）
  if (data && data.code) {
    code.value = data.code
    sendHint.value = '验证码已发送（本地联调已自动填入）至 ' + target
  } else {
    sendHint.value = '验证码已发送至 ' + target + '（' + nowTime() + '）'
  }
  startResendCountdown()
}

/** 第一步提交 */
async function handleSendCode() {
  const value = email.value.trim()
  emailError.value = ''
  if (!EMAIL_RE.test(value)) {
    emailError.value = '请输入有效的邮箱地址'
    emailInput.value && emailInput.value.focus()
    return
  }

  try {
    await requestSendCode(value)
  } catch (err) {
    emailError.value = (err && err.message) || ''
    emailInput.value && emailInput.value.focus()
    return
  }

  emailFormHidden.value = true
  resetFormHidden.value = false
  // 与原 js 一致：切换表单时清空验证码与密码（联调自动填入的验证码同样在此被清空）
  code.value = ''
  password.value = ''
  codeError.value = ''
  passwordError.value = ''
  await nextTick()
  codeInput.value && codeInput.value.focus()
}

/** 第二步提交：重置密码 */
async function handleReset() {
  const value = code.value.replace(/\s/g, '')
  const pwd = password.value

  codeError.value = ''
  passwordError.value = ''

  if (!/^\d{6}$/.test(value)) {
    codeError.value = '请输入 6 位数字验证码'
    codeInput.value && codeInput.value.focus()
    return
  }
  if (!pwd) {
    passwordError.value = '请输入新密码'
    passwordInput.value && passwordInput.value.focus()
    return
  }

  try {
    await resetPassword({ email: lastEmail, code: value, newPassword: pwd })
  } catch (err) {
    codeError.value = (err && err.message) || ''
    return
  }

  resetFormHidden.value = true
  successHidden.value = false
  startRedirectCountdown()
}

/** 重置成功 5 秒后跳转登录页（与原 js 一致：整页跳转，顺带重放 orb 背景动画） */
function startRedirectCountdown() {
  let left = REDIRECT_MS / 1000
  const tick = () => {
    if (left <= 0) {
      window.location.assign('/auth/login')
      return
    }
    successNote.value = left + ' 秒后自动跳转登录页…'
    left -= 1
    redirectTimer = setTimeout(tick, 1000)
  }
  tick()
}

/** 重新发送验证码 */
async function handleResend() {
  try {
    await requestSendCode(lastEmail)
  } catch (err) {
    codeError.value = (err && err.message) || ''
    return
  }
  code.value = ''
  password.value = ''
  codeError.value = ''
  passwordError.value = ''
  codeInput.value && codeInput.value.focus()
}

onBeforeUnmount(() => {
  clearTimeout(resendTimer)
  clearTimeout(redirectTimer)
})
</script>
