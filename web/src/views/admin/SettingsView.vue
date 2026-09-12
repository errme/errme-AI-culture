<template>
  <div class="ad-page">
    <AdminPageHeader
      title="系统设置"
      description="站点信息、验证码策略、登录有效期、缓存、上传限制、接口限流。保存后立即生效，无需重启。"
    >
      <template #actions>
        <button type="button" class="btn btn-default" :disabled="loading" @click="load">
          <i class="mdi mdi-refresh"></i> 重新载入
        </button>
        <button type="button" class="btn btn-primary" :disabled="saving || !items.length" @click="saveAll">
          <i class="mdi mdi-content-save"></i> {{ saving ? '保存中…' : '保存全部修改' }}
        </button>
      </template>
    </AdminPageHeader>

    <AdminEmpty v-if="!loading && !items.length" text="没有可配置项。请先执行 docs/sql/13_sys_config.sql 建表并初始化。" />

    <!-- 按分组渲染：分组名与顺序都来自后端（GET /api/admin/config/list），前端不维护对照表 -->
    <div v-for="group in groupOrder" :key="group" class="ad-card ad-settings-card">
      <div class="ad-card__head">
        <h5 class="ad-card__title">{{ groupLabel(group) }}</h5>
        <span class="ad-hint">{{ (groups[group] || []).length }} 项</span>
      </div>
      <div class="ad-card__body">
        <div v-for="row in groups[group]" :key="row.configKey" class="ad-field">
          <label class="ad-field__label">
            {{ row.label }}
            <span v-if="row.restartRequired === 1" class="label label-warning ad-field__flag">需重启</span>
            <span v-if="isDirty(row.configKey)" class="label label-info ad-field__flag">已修改</span>
          </label>

          <!-- 布尔型 -> 开关；整型 -> number；其余 -> 文本 -->
          <div class="ad-field__control">
            <select v-if="row.valueType === 'bool'" v-model="form[row.configKey]" class="form-control input-sm">
              <option value="true">开启</option>
              <option value="false">关闭</option>
            </select>
            <input
              v-else-if="row.valueType === 'int'"
              v-model="form[row.configKey]"
              type="number"
              class="form-control input-sm"
              :min="row.minValue != null ? row.minValue : undefined"
              :max="row.maxValue != null ? row.maxValue : undefined"
            >
            <input v-else v-model="form[row.configKey]" type="text" class="form-control input-sm">
          </div>

          <p class="ad-field__desc">
            {{ row.description }}
            <span v-if="row.valueType === 'int' && (row.minValue != null || row.maxValue != null)" class="ad-field__range">
              （范围 {{ row.minValue != null ? row.minValue : '-∞' }} ~ {{ row.maxValue != null ? row.maxValue : '+∞' }}）
            </span>
            <span class="ad-field__key">key: {{ row.configKey }}</span>
            <button type="button" class="ad-link-btn" @click="resetOne(row)">恢复默认</button>
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 后台「系统设置」页。
 *
 * 数据完全来自后端 GET /api/admin/config/list：
 *   · 有哪些配置项、属于哪一组、什么类型、允许范围、说明文案 —— 全部由数据库（sys_config）决定；
 *   · 前端**不维护**任何「key → 分组/标签」的对照表，新增配置项只需改 SQL，页面自动出现。
 *
 * 保存走 POST /api/admin/config/save，后端先整体校验再落库（任一不合法则整批拒绝），
 * 通过后立即刷新内存缓存，因此改完即时生效、无需重启。
 */
import { computed, onMounted, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { configList, configSave, configReset } from '@/api/admin'
import { toast as dsToast, confirmDialog } from '@/utils/notify'

const loading = ref(false)
const saving = ref(false)
const items = ref([])
const groups = ref({})
const groupLabels = ref({})

/** 表单当前值（key → value 字符串） */
const form = ref({})

/** 分组顺序：保持后端返回的顺序 */
const groupOrder = computed(() => Object.keys(groups.value))

function groupLabel(g) {
  return groupLabels.value[g] || g
}

function isDirty(key) {
  const row = items.value.find(i => i.configKey === key)
  if (!row) return false
  return String(form.value[key] ?? '') !== String(row.configValue ?? '')
}

/** 有改动的项（保存时只提交这些，避免把未改动的值也写一遍） */
function changedEntries() {
  const out = {}
  for (const row of items.value) {
    const key = row.configKey
    const now = String(form.value[key] ?? '')
    if (now !== String(row.configValue ?? '')) out[key] = now
  }
  return out
}

async function load() {
  loading.value = true
  try {
    const data = await configList()
    items.value = (data && data.items) || []
    groups.value = (data && data.groups) || {}
    groupLabels.value = (data && data.groupLabels) || {}
    const f = {}
    for (const row of items.value) f[row.configKey] = row.configValue
    form.value = f
  } catch (e) {
    items.value = []
    groups.value = {}
    dsToast.error((e && e.message) || '系统设置加载失败')
  } finally {
    loading.value = false
  }
}

async function saveAll() {
  const entries = changedEntries()
  if (!Object.keys(entries).length) {
    dsToast.info('没有需要保存的修改')
    return
  }
  saving.value = true
  try {
    await configSave(entries)
    dsToast.success(`已保存 ${Object.keys(entries).length} 项配置，立即生效`)
    await load()
  } catch (e) {
    // 后端的校验失败信息（含具体哪一项不合法）直接展示
    dsToast.error((e && e.message) || '保存失败')
  } finally {
    saving.value = false
  }
}

function resetOne(row) {
  confirmDialog({
    title: '恢复默认值',
    content: `确定把「${row.label}」恢复为出厂默认值吗？`,
    detail: row.defaultValue != null ? `默认值：${row.defaultValue}` : '该项没有出厂默认值',
    confirmText: '恢复',
    onConfirm: async () => {
      await configReset(row.configKey)
      dsToast.success(`「${row.label}」已恢复默认`)
      await load()
    }
  })
}

onMounted(load)
</script>

<style scoped>
.ad-settings-card { margin-bottom: 16px; }
.ad-field {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 6px 16px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--ad-border);
}
.ad-field:last-child { border-bottom: 0; }
.ad-field__label {
  margin: 0;
  font-weight: 500;
  color: var(--ad-text);
  line-height: 30px;
}
.ad-field__flag { margin-left: 6px; font-weight: 400; }
.ad-field__control { min-width: 0; }
.ad-field__desc {
  grid-column: 2;
  margin: 0;
  font-size: 12.5px;
  color: var(--ad-muted);
  line-height: 1.6;
}
.ad-field__range { color: var(--ad-text-sub); }
.ad-field__key {
  margin-left: 8px;
  font-family: Consolas, Monaco, monospace;
  font-size: 11.5px;
  color: #9aa4b2;
}
.ad-link-btn {
  margin-left: 8px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ad-primary);
  font-size: 12.5px;
  cursor: pointer;
}
.ad-link-btn:hover { text-decoration: underline; }

@media (max-width: 768px) {
  .ad-field { grid-template-columns: 1fr; }
  .ad-field__desc { grid-column: 1; }
}
</style>
