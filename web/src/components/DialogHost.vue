<template>
  <!--
    二次确认弹窗（重设计版）
    · 破坏性操作用红色主按钮 + 危险图标，并把「不可恢复」写成独立提示行
    · 危险弹窗默认聚焦「取消」，防误按回车；Esc 取消、Enter 确认
    · 主按钮支持 busy（onConfirm 返回 Promise 时）
  -->
  <Teleport to="body">
    <Transition name="ds-dialog">
      <div v-if="d" class="ds-dialog" :class="{ 'is-danger': d.danger }" @click.self="onOverlay">
        <div class="ds-dialog__card" role="dialog" aria-modal="true" :aria-label="d.title">
          <div class="ds-dialog__head">
            <span class="ds-dialog__icon" aria-hidden="true" v-html="ICON"></span>
            <h3 class="ds-dialog__title">{{ d.title }}</h3>
          </div>
          <p v-if="d.content" class="ds-dialog__content">{{ d.content }}</p>
          <p v-if="d.detail" class="ds-dialog__detail">
            <span class="ds-dialog__dot" aria-hidden="true"></span>{{ d.detail }}
          </p>
          <input
            v-if="d.requireInput"
            ref="inputEl"
            v-model="d.inputValue"
            class="ds-dialog__input"
            type="text"
            :placeholder="d.inputPlaceholder"
            @keydown.enter.prevent="ok"
          >
          <div class="ds-dialog__foot">
            <button v-if="d.cancelText" ref="cancelEl" type="button" class="ds-btn ds-btn--ghost" :disabled="d.busy" @click="cancel">
              {{ d.cancelText }}
            </button>
            <button ref="confirmEl" type="button" class="ds-btn" :class="d.danger ? 'ds-btn--danger' : 'ds-btn--primary'" :disabled="d.busy" @click="ok">
              <span v-if="d.busy" class="ds-btn__spin" aria-hidden="true"></span>{{ d.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { notifyState as state, settleDialog, cancelDialog } from '@/utils/notify'

const d = computed(() => state.dialog)
const inputEl = ref(null)
const confirmEl = ref(null)
const cancelEl = ref(null)

const ICON = computed(() => (d.value && d.value.danger
  ? '<svg viewBox="0 0 24 24" width="22" height="22"><path d="M12 3.6l8.6 15.2H3.4L12 3.6z" fill="#fee2e2" stroke="#ef4444" stroke-width="1.6" stroke-linejoin="round"/><path d="M12 9.2v4.4M12 16.3v.1" fill="none" stroke="#ef4444" stroke-width="1.8" stroke-linecap="round"/></svg>'
  : '<svg viewBox="0 0 24 24" width="22" height="22"><circle cx="12" cy="12" r="9" fill="#e0e7ff" stroke="#6366f1" stroke-width="1.6"/><path d="M12 8.2v4.6M12 15.6v.1" fill="none" stroke="#6366f1" stroke-width="1.8" stroke-linecap="round"/></svg>'))

/** 危险操作默认聚焦「取消」，避免回车误删；其它情况聚焦主按钮 */
watch(d, async cur => {
  if (!cur) return
  await nextTick()
  if (cur.requireInput && inputEl.value) inputEl.value.focus()
  else if (cur.danger && cancelEl.value) cancelEl.value.focus()
  else if (confirmEl.value) confirmEl.value.focus()
})

function ok() { if (!d.value || d.value.busy) return; settleDialog(true) }
function cancel() { cancelDialog() }
function onOverlay() { if (d.value && d.value.danger) return; /* 危险操作必须显式点取消 */ cancelDialog() }

function onKey(e) {
  if (!d.value) return
  if (e.key === 'Escape') { e.preventDefault(); cancel() }
  else if (e.key === 'Enter' && !d.value.busy) {
    const tag = (e.target && e.target.tagName) || ''
    if (tag === 'TEXTAREA') return
    e.preventDefault(); ok()
  }
}
onMounted(() => document.addEventListener('keydown', onKey, true))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey, true))
</script>

<style scoped>
.ds-dialog {
  position: fixed;
  inset: 0;
  z-index: 4000;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 12vh 16px 24px;
  background: rgba(15, 23, 42, 0.45);
  backdrop-filter: blur(2px);
  overflow: auto;
}
.ds-dialog__card {
  width: 100%;
  max-width: 440px;
  background: #fff;
  border-radius: 12px;
  padding: 20px 22px 16px;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.28);
  color: #0f172a;
}
.ds-dialog__head { display: flex; align-items: center; gap: 10px; }
.ds-dialog__icon { line-height: 0; flex: 0 0 auto; }
.ds-dialog__title { margin: 0; font-size: 16px; font-weight: 600; }
.ds-dialog__content { margin: 12px 0 0; font-size: 14px; color: #334155; line-height: 1.6; word-break: break-word; }
.ds-dialog__detail {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 10px 0 0;
  padding: 9px 12px;
  border-radius: 8px;
  background: #f1f5f9;
  color: #475569;
  font-size: 13px;
  line-height: 1.55;
}
.is-danger .ds-dialog__detail { background: #fef2f2; color: #b91c1c; }
.ds-dialog__dot { flex: 0 0 auto; width: 5px; height: 5px; margin-top: 7px; border-radius: 50%; background: currentColor; }
.ds-dialog__input {
  width: 100%;
  margin-top: 12px;
  padding: 8px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
}
.ds-dialog__input:focus { border-color: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.15); }
.ds-dialog__foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.ds-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 78px;
  justify-content: center;
  padding: 8px 16px;
  border: 1px solid transparent;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.ds-btn:disabled { opacity: 0.7; cursor: default; }
.ds-btn--ghost { background: #fff; border-color: #cbd5e1; color: #334155; }
.ds-btn--ghost:hover:not(:disabled) { background: #f8fafc; }
.ds-btn--primary { background: #4f46e5; color: #fff; }
.ds-btn--primary:hover:not(:disabled) { background: #4338ca; }
.ds-btn--danger { background: #dc2626; color: #fff; }
.ds-btn--danger:hover:not(:disabled) { background: #b91c1c; }
.ds-btn__spin {
  width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.5);
  border-top-color: #fff;
  animation: ds-spin 0.7s linear infinite;
}
@keyframes ds-spin { to { transform: rotate(360deg); } }

.ds-dialog-enter-active, .ds-dialog-leave-active { transition: opacity 0.18s ease; }
.ds-dialog-enter-active .ds-dialog__card, .ds-dialog-leave-active .ds-dialog__card { transition: transform 0.18s ease, opacity 0.18s ease; }
.ds-dialog-enter-from, .ds-dialog-leave-to { opacity: 0; }
.ds-dialog-enter-from .ds-dialog__card, .ds-dialog-leave-to .ds-dialog__card { transform: translateY(-10px) scale(0.98); opacity: 0; }
</style>
