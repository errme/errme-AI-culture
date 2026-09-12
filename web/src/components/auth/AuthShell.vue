<template>
  <main>
    <div class="onboard waitlist intro-reveal">
      <div class="branding">
        <div class="icon"></div>
      </div>

      <div class="orb">
        <div id="intro-canvas" class="intro-canvas"></div>
        <div class="intro-overlay basic"></div>
        <div class="intro-overlay blurred"></div>
      </div>

      <div class="guts">
        <div class="rhs">
          <div class="wrapper">
            <div class="main">
              <img class="logo" src="/static/auth/images/branding-icon.svg" decoding="async" alt="落款">

              <slot name="badge"></slot>
              <h2>{{ title }}</h2>
              <p class="auth-slogan">{{ slogan }}</p>

              <div class="email-auth" :data-page="page">
                <slot></slot>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
/**
 * 认证页外壳（登录/注册/找回密码/后台登录共用）
 * 与原 static/auth/*.html 的 DOM 结构、class、层级完全一致，
 * 仅把「表单区」做成插槽，保证样式零改动。
 */
defineProps({
  title: { type: String, default: '落款' },
  slogan: { type: String, default: '人间情书，为每一份心动署名' },
  page: { type: String, default: 'signin' }
})
</script>
