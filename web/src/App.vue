<template>
  <router-view />
  <!-- 全局顶部轻提示 + 二次确认弹窗（4 个入口共用；见 utils/notify.js） -->
  <ToastHost />
  <DialogHost />
</template>

<script setup>
// 所有入口共用的根组件：承载路由视图 + 全局消息提示/确认弹窗
import ToastHost from '@/components/ToastHost.vue'
import DialogHost from '@/components/DialogHost.vue'
import { installLegacyNotifyShim } from '@/utils/notify'
import { installErrorReporter } from '@/utils/errorReport'

// 接管老代码里的 $.confirm（单按钮→顶部提示，双按钮→新确认弹窗）。
// 暴露到 window：老页面动态加载自带 jQuery 的脚本后，loadScript 会再调一次重新接管。
window.__dshInstallNotifyShim = installLegacyNotifyShim
installLegacyNotifyShim()
// 未捕获异常/未处理 Promise 拒绝上报到后端日志（每会话最多 5 条）
installErrorReporter()
</script>
