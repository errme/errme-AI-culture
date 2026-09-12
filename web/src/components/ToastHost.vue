<template>
  <!--
    顶部居中轻提示（类似 platform.deepseek.com）：
    固定在最上方、居中堆叠、可自动消失、鼠标悬停暂停计时、不阻塞任何操作。
  -->
  <Teleport to="body">
    <div class="ds-toasts" role="status" aria-live="polite">
      <TransitionGroup name="ds-toast">
        <div
          v-for="t in state.toasts"
          :key="t.id"
          class="ds-toast"
          :class="'is-' + t.type"
          @mouseenter="pauseToast(t.id)"
          @mouseleave="resumeToast(t.id)"
        >
          <span class="ds-toast__icon" aria-hidden="true" v-html="ICONS[t.type]"></span>
          <div class="ds-toast__body">
            <p class="ds-toast__msg">{{ t.message }}</p>
            <p v-if="t.detail" class="ds-toast__detail">{{ t.detail }}</p>
          </div>
          <button
            v-if="t.action"
            class="ds-toast__action"
            type="button"
            @click="runToastAction(t.id)"
          >{{ t.action.text }}</button>
          <button class="ds-toast__close" type="button" aria-label="关闭" @click="state.dismiss(t.id)">✕</button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup>
import { notifyState as state, pauseToast, resumeToast, runToastAction } from '@/utils/notify'

const ICONS = {
  success: '<svg viewBox="0 0 20 20" width="18" height="18"><circle cx="10" cy="10" r="9" fill="#22c55e"/><path d="M6 10.4l2.6 2.6L14 7.6" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  error: '<svg viewBox="0 0 20 20" width="18" height="18"><circle cx="10" cy="10" r="9" fill="#ef4444"/><path d="M10 5.6v5.2M10 14.1v.1" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/></svg>',
  warning: '<svg viewBox="0 0 20 20" width="18" height="18"><circle cx="10" cy="10" r="9" fill="#f59e0b"/><path d="M10 5.6v5.2M10 14.1v.1" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/></svg>',
  info: '<svg viewBox="0 0 20 20" width="18" height="18"><circle cx="10" cy="10" r="9" fill="#3b82f6"/><path d="M10 9v5M10 5.9v.1" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/></svg>'
}
</script>

<style scoped>
.ds-toasts {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 4000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  pointer-events: none;
}
.ds-toast {
  pointer-events: auto;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  min-width: 240px;
  max-width: min(560px, calc(100vw - 32px));
  padding: 11px 12px 11px 14px;
  border-radius: 10px;
  background: rgba(17, 24, 39, 0.95);
  color: #f8fafc;
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.28), 0 1px 0 rgba(255, 255, 255, 0.04) inset;
  font-size: 14px;
  line-height: 1.5;
  backdrop-filter: blur(6px);
}
.ds-toast__icon { flex: 0 0 auto; margin-top: 1px; line-height: 0; }
.ds-toast__body { flex: 1 1 auto; min-width: 0; }
.ds-toast__msg { margin: 0; word-break: break-word; }
.ds-toast__detail { margin: 3px 0 0; color: rgba(226, 232, 240, 0.75); font-size: 12.5px; word-break: break-word; }
.ds-toast__action {
  flex: 0 0 auto;
  align-self: center;
  margin-left: 4px;
  padding: 3px 10px;
  border: 1px solid rgba(148, 163, 184, 0.45);
  border-radius: 6px;
  background: rgba(148, 163, 184, 0.14);
  color: #e2e8f0;
  font-size: 12.5px;
  line-height: 1.4;
  cursor: pointer;
  white-space: nowrap;
}
.ds-toast__action:hover { background: rgba(148, 163, 184, 0.26); color: #fff; }
.ds-toast__close {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: rgba(226, 232, 240, 0.7);
  font-size: 12px;
  line-height: 1;
  padding: 3px 2px 3px 6px;
  cursor: pointer;
}
.ds-toast__close:hover { color: #fff; }

.ds-toast-enter-active, .ds-toast-leave-active { transition: opacity 0.22s ease, transform 0.22s ease; }
.ds-toast-enter-from { opacity: 0; transform: translateY(-14px) scale(0.98); }
.ds-toast-leave-to { opacity: 0; transform: translateY(-10px) scale(0.98); }
.ds-toast-move { transition: transform 0.22s ease; }
</style>
