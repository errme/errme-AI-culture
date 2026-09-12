<template>
  <AuthShell page="signup" title="落款" slogan="人间情书，为每一份心动署名">
    <form class="auth-form" id="email-form" novalidate :hidden="step !== 'email'" @submit.prevent="onSubmitEmail">
      <div class="auth-field">
        <input class="auth-input" id="email" name="email" type="email" placeholder="邮箱地址"
          aria-label="邮箱地址" autocomplete="email" required ref="emailInput" v-model="email">
        <p class="auth-error" id="email-error" role="alert" :hidden="!emailError">{{ emailError }}</p>
      </div>
      <div class="auth-row">
        <button class="auth-button" type="submit">获取验证码</button>
        <button class="auth-button auth-button--ghost" type="button" id="go-login" @click="goLogin">登录</button>
      </div>
    </form>

    <form class="auth-form" id="code-form" novalidate :hidden="step !== 'code'" @submit.prevent="onSubmitCode">
      <p class="auth-hint" id="send-hint">{{ sendHint }}</p>
      <div class="auth-field auth-field--inset">
        <input class="auth-input auth-code" id="code" name="code" type="text" inputmode="numeric"
          maxlength="6" pattern="[0-9]{6}" placeholder="6位验证码" aria-label="验证码"
          autocomplete="one-time-code" required ref="codeInput" v-model="code">
        <p class="auth-error" id="code-error" role="alert" :hidden="!codeError">{{ codeError }}</p>
        <button class="auth-inside" type="button" id="resend-btn" :disabled="resendDisabled" @click="onResend">{{ resendText }}</button>
      </div>
      <div class="auth-field">
        <input class="auth-input" id="password" name="password" type="password" placeholder="密码"
          aria-label="设置密码" autocomplete="请输入密码" required ref="passwordInput" v-model="password">
        <p class="auth-error" id="password-error" role="alert" :hidden="!passwordError">{{ passwordError }}</p>
      </div>
      <button class="auth-button" type="submit">验证并注册</button>
    </form>

    <div class="auth-success" id="auth-success" :hidden="step !== 'success'">
      <svg class="auth-check" viewBox="0 0 52 52" aria-hidden="true">
        <circle cx="26" cy="26" r="24"></circle>
        <path d="M14 27l8 8 16-16"></path>
      </svg>
      <p class="auth-success-title">邮箱验证成功</p>
      <p class="auth-success-sub" id="success-sub">{{ successSub }}</p>
      <p class="auth-success-note" id="success-note">{{ successNote }}</p>
    </div>
  </AuthShell>
</template>

<script setup>
/**
 * 注册页：邮箱 -> 验证码 + 设置密码
 * 迁移自 static/auth/register.html + static/auth/js/signup-auth.js，
 * DOM/class、校验、60 秒重发倒计时、成功态与 5 秒自动跳转行为保持一致。
 *
 * 接口：sendCode({ email, scene: 'register' }) -> POST /api/auth/send-code
 *      register({ email, code, password })   -> POST /api/auth/register
 */
import { nextTick, onUnmounted, ref } from 'vue'
import AuthShell from '@/components/auth/AuthShell.vue'
import { register, sendCode } from '@/api/auth'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/
const RESEND_SECONDS = 60
const REDIRECT_MS = 5000

/** 'email' -> 'code' -> 'success' 三段式（对应原 HTML 三个区块的 hidden 切换） */
const step = ref('email')

const email = ref('')
const code = ref('')
const password = ref('')

const emailError = ref('')
const codeError = ref('')
const passwordError = ref('')

const sendHint = ref('')
const resendText = ref('重新发送')
const resendDisabled = ref(false)

const successSub = ref('')
const successNote = ref('')

const emailInput = ref(null)
const codeInput = ref(null)
const passwordInput = ref(null)

let lastEmail = ''
let resendTimer = null
let redirectTimer = null

function nowTime() {
  const d = new Date()
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0')
}

/** 60 秒重发倒计时 */
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

/** 注册成功 5 秒后自动跳转登录页 */
function startRedirectCountdown() {
  let left = REDIRECT_MS / 1000
  const tick = () => {
    if (left <= 0) { window.location.href = '/auth/login'; return }
    successNote.value = left + ' 秒后自动跳转登录页…'
    left -= 1
    redirectTimer = setTimeout(tick, 1000)
  }
  tick()
}

/**
 * 错误文案：与原 signup-auth.js 一致（直接展示后端 message）；
 * 网络不可用时 http 层会透出 axios 的 "Network Error"，
 * 这里回落到原 apiPost 在 fetch 失败时抛出的「无法连接服务器，请确认后端已启动」。
 */
function errorMessage(err) {
  const message = err && err.message
  return !message || message === 'Network Error' ? '无法连接服务器，请确认后端已启动' : message
}

/** 发送验证码（场景 register） */
async function requestSendCode(target) {
  const data = await sendCode({ email: target, scene: 'register' })
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

/** 第一步：邮箱 -> 获取验证码 */
async function onSubmitEmail() {
  const value = email.value.trim()
  emailError.value = ''
  if (!EMAIL_RE.test(value)) {
    emailError.value = '请输入有效的邮箱地址'
    emailInput.value && emailInput.value.focus()
    return
  }

  try {
    await requestSendCode(value)
    step.value = 'code'
    code.value = ''
    password.value = ''
    codeError.value = ''
    passwordError.value = ''
    await nextTick()
    codeInput.value && codeInput.value.focus()
  } catch (err) {
    emailError.value = errorMessage(err)
    emailInput.value && emailInput.value.focus()
  }
}

/** 第二步：验证码 + 密码 -> 注册 */
async function onSubmitCode() {
  const codeValue = code.value.replace(/\s/g, '')
  const passwordValue = password.value

  codeError.value = ''
  passwordError.value = ''

  if (!/^\d{6}$/.test(codeValue)) { codeError.value = '请输入 6 位数字验证码'; codeInput.value && codeInput.value.focus(); return }
  if (!passwordValue) { passwordError.value = '请输入密码'; passwordInput.value && passwordInput.value.focus(); return }

  try {
    await register({ email: lastEmail, code: codeValue, password: passwordValue })
    step.value = 'success'
    successSub.value = '注册成功，欢迎来到落款'
    startRedirectCountdown()
  } catch (err) {
    codeError.value = errorMessage(err)
  }
}

/** 重新发送验证码 */
async function onResend() {
  try {
    await requestSendCode(lastEmail)
    code.value = ''
    password.value = ''
    codeError.value = ''
    passwordError.value = ''
    await nextTick()
    codeInput.value && codeInput.value.focus()
  } catch (err) {
    codeError.value = errorMessage(err)
  }
}

/** 去登录 -> 跳转独立登录页（与原 js 一致，整页跳转以重放 orb 背景动画） */
function goLogin() {
  window.location.href = '/auth/login'
}

onUnmounted(() => {
  clearTimeout(resendTimer)
  clearTimeout(redirectTimer)
})
</script>
