<template>
  <div class="container-fluid ad-page">
    <!--
      邮件设置：概览 → 通用设置 → 发件账号 → 统计 / 测试 / 日志。
      数据来源与字段绑定与原实现一致（见 script 中的 load* 注释），只调整信息层级与排版；
      排版统一到 src/styles/admin-ui.css 的 ad-* 规范，并保留 #cfg-* / #acc-body / #stat-body /
      #log-body / #test-to / .r-username / .r-password / .r-limit / .r-enabled 等既有 id 与 class。
    -->
    <AdminPageHeader
      icon="mdi mdi-email-outline"
      title="邮件设置"
      desc="用于注册验证码、找回密码等系统邮件的发送：账号按顺序选用「当日未超限」的一个，全部超限时前台会提示稍后再试。"
    >
      <template #actions>
        <span class="label" :class="cfg.host ? 'label-success' : 'label-warning'">
          {{ cfg.host ? 'SMTP 已配置' : 'SMTP 未配置' }}
        </span>
        <span class="ad-badge ad-badge--primary">可用账号 {{ enabledCount }} / {{ accountRows.length }}</span>
        <button class="btn btn-default btn-sm" @click="loadAll">
          <i class="mdi mdi-refresh"></i> 刷新
        </button>
      </template>
    </AdminPageHeader>

    <div class="ad-tiles">
      <AdminStatTile label="启用账号" :value="enabledCount" :suffix="'/ ' + accountRows.length"
                     icon="mdi mdi-account-check" tone="success" hint="发送时只从启用账号中挑选" />
      <AdminStatTile label="今日已发" :value="sentTotal" :suffix="'/ ' + limitTotal"
                     icon="mdi mdi-send" hint="今日已发送 / 全部上限" />
      <AdminStatTile label="今日成功" :value="todaySuccess" icon="mdi mdi-check-circle-outline" tone="success" />
      <AdminStatTile label="今日失败" :value="todayFail" icon="mdi mdi-alert-circle-outline"
                     :tone="todayFail > 0 ? 'danger' : ''" :hint="todayFail > 0 ? '请查看下方发送日志' : '今日没有失败记录'" />
    </div>

    <div class="row">
      <div class="col-lg-7">
        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-settings"></i> 通用设置（SMTP / 邮件模板）</h5>
            <div class="ad-card__actions">
              <span v-if="configDirty" class="ad-dirty">有未保存的修改</span>
            </div>
          </div>
          <div class="ad-card__body">
            <p class="ad-section-title">发信服务器</p>
            <div class="ad-form-grid">
              <div class="form-group">
                <label for="cfg-host">SMTP 服务器：<span class="text-danger">*</span></label>
                <input id="cfg-host" class="form-control" placeholder="smtp.larksuite.com" v-model="cfg.host" />
              </div>
              <div class="form-group">
                <label for="cfg-port">端口：</label>
                <input id="cfg-port" class="form-control" placeholder="587" v-model="cfg.port" />
                <p class="ad-help">587 = STARTTLS（推荐），465 = SSL</p>
              </div>
              <div class="form-group">
                <label for="cfg-fromName">发件人名称：</label>
                <input id="cfg-fromName" class="form-control" placeholder="遇你" v-model="cfg.fromName" />
                <p class="ad-help">显示在收件人看到的发件人一栏。</p>
              </div>
            </div>

            <p class="ad-section-title">邮件模板</p>
            <div class="form-group">
              <label for="cfg-subject">邮件主题：</label>
              <input id="cfg-subject" class="form-control" v-model="cfg.subjectTemplate" />
              <p class="ad-help">占位符：<code>{code}</code> 验证码、<code>{minutes}</code> 有效分钟</p>
            </div>
            <div class="form-group">
              <label for="cfg-body">邮件正文：</label>
              <textarea id="cfg-body" class="form-control" rows="5" v-model="cfg.bodyTemplate"></textarea>
              <p class="ad-help">示例：您的验证码是 {code}，{minutes} 分钟内有效。</p>
            </div>
          </div>
          <div class="ad-card__foot">
            <div class="ad-toolbar">
              <button class="btn btn-primary btn-sm" :disabled="savingConfig" @click="saveConfig">
                <i class="mdi mdi-content-save"></i> 保存通用设置
              </button>
              <button v-if="configDirty" class="btn btn-default btn-sm" @click="loadConfig">放弃修改</button>
              <span class="ad-toolbar__spacer"></span>
              <span class="ad-help">修改端口或模板后需要重新保存才会生效。</span>
            </div>
          </div>
        </div>

        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-email-variant"></i> 发件账号</h5>
            <div class="ad-card__actions">
              <span v-if="accountsDirty" class="ad-dirty">有未保存的修改</span>
              <button class="btn btn-default btn-sm" @click="addRow"><i class="mdi mdi-plus"></i> 添加账号</button>
              <button class="btn btn-primary btn-sm" :disabled="savingAccounts" @click="saveAccounts">
                <i class="mdi mdi-content-save"></i> 保存账号
              </button>
            </div>
          </div>
          <div class="ad-card__body ad-card__body--flush">
            <div class="ad-table-wrap">
              <table class="ad-table">
                <thead>
                  <tr>
                    <th>发件邮箱</th>
                    <th style="width:130px;">密码</th>
                    <th style="width:100px;">每日上限</th>
                    <th style="width:150px;">今日已发</th>
                    <th style="width:70px;">启用</th>
                    <th style="width:80px;">操作</th>
                  </tr>
                </thead>
                <tbody id="acc-body">
                  <tr v-if="accLoading">
                    <td colspan="6"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
                  </tr>
                  <tr v-else-if="!accountRows.length">
                    <td colspan="6">
                      <AdminEmpty icon="mdi mdi-email-outline"
                                  text="还没有发件账号，点上方「添加账号」开始配置" />
                    </td>
                  </tr>
                  <tr v-for="(a, index) in visibleAccountRows" :key="a.key" :data-id="a.id || ''">
                    <td><input class="form-control input-sm r-username" placeholder="发件邮箱" v-model="a.username" /></td>
                    <td><input class="form-control input-sm r-password" type="password" :placeholder="a.id ? '留空不改' : '必填'" v-model="a.password" /></td>
                    <td><input class="form-control input-sm r-limit" type="number" v-model="a.dailyLimit" /></td>
                    <td>
                      <div class="progress mail-progress">
                        <div class="progress-bar" :class="quotaClass(a)" :style="{ width: quotaPct(a) + '%' }"></div>
                      </div>
                      <span class="ad-help">{{ a.sentToday || 0 }} / {{ a.dailyLimit || 0 }}</span>
                    </td>
                    <td class="text-center">
                      <input type="checkbox" class="r-enabled" :checked="a.enabled == 1" @change="a.enabled = $event.target.checked ? 1 : 0" />
                    </td>
                    <td class="text-center">
                      <button class="btn btn-danger btn-xs" @click="deleteRow(a, index)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="ad-help mail-legend">发送时按顺序挑选「当日未超限」的账号；全部超限时前台会提示稍后再试。</p>
          </div>
        </div>
      </div>

      <div class="col-lg-5">
        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-chart-line"></i> 今日发送统计</h5>
          </div>
          <div class="ad-card__body">
            <div id="stat-box" class="alert alert-info" :style="stat ? '' : 'display:none;'" v-html="statHtml"></div>
            <div class="ad-table-wrap" :style="stat ? '' : 'display:none;'">
              <table class="ad-table" id="stat-table">
                <thead>
                  <tr>
                    <th>账号</th>
                    <th style="width:140px;">今日已发/上限</th>
                    <th style="width:80px;">状态</th>
                  </tr>
                </thead>
                <tbody id="stat-body">
                  <tr v-for="(a, index) in visibleStatAccounts" :key="index">
                    <td>{{ a.username }}</td>
                    <td>
                      <div class="progress mail-progress">
                        <div class="progress-bar" :class="quotaClass(a)" :style="{ width: quotaPct(a) + '%' }"></div>
                      </div>
                      <span class="ad-help">{{ a.sentToday }} / {{ a.dailyLimit }}</span>
                    </td>
                    <td><span :class="statLabelClass(a)">{{ statLabelText(a) }}</span></td>
                  </tr>
                  <tr v-if="stat && !statAccounts.length">
                    <td colspan="3"><AdminEmpty icon="mdi mdi-account-off" text="暂无发件账号" /></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-send"></i> 测试发送</h5>
          </div>
          <div class="ad-card__body">
            <div class="input-group">
              <input id="test-to" class="form-control" placeholder="输入收件邮箱测试发送" v-model="testTo" @keyup.enter="sendTest" />
              <span class="input-group-btn">
                <button class="btn btn-success" :disabled="sendingTest || !enabledCount" @click="sendTest">
                  <i class="mdi mdi-send"></i> 发送测试
                </button>
              </span>
            </div>
            <p class="ad-help">
              <template v-if="!enabledCount">当前没有启用中的发件账号，测试发送不可用。</template>
              <template v-else>会使用「{{ firstEnabledName }}」发送一封测试邮件。</template>
            </p>
          </div>
        </div>

        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-history"></i> 最近发送日志</h5>
            <div class="ad-card__actions">
              <label class="mail-switch">
                <input type="checkbox" v-model="onlyFailed" /> 只看失败
              </label>
            </div>
          </div>
          <div class="ad-card__body ad-card__body--flush">
            <div class="ad-table-wrap">
              <table class="ad-table">
                <thead>
                  <tr>
                    <th style="width:150px;">时间</th>
                    <th>收件人</th>
                    <th style="width:80px;">状态</th>
                    <th>错误</th>
                  </tr>
                </thead>
                <tbody id="log-body">
                  <tr v-if="logLoading">
                    <td colspan="4"><AdminEmpty icon="mdi mdi-loading mdi-spin" text="加载中…" /></td>
                  </tr>
                  <tr v-else-if="!logs.length">
                    <td colspan="4"><AdminEmpty icon="mdi mdi-history" text="暂无日志" /></td>
                  </tr>
                  <tr v-else-if="!visibleLogs.length">
                    <td colspan="4"><AdminEmpty icon="mdi mdi-emoticon-happy" text="没有失败的发送记录 🎉" /></td>
                  </tr>
                  <tr v-for="(l, index) in visibleLogs" :key="l.id || index">
                    <td class="mail-time">{{ l.createdAt || '' }}</td>
                    <td>{{ l.toEmail }}</td>
                    <td>
                      <span v-if="l.status == 1" class="label label-success">成功</span>
                      <span v-else class="label label-danger">失败</span>
                    </td>
                    <td class="mail-err">
                      <span :title="l.errorMsg || ''">{{ (l.errorMsg || '').slice(0, 60) }}</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminStatTile from '@/components/admin/AdminStatTile.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import {
  mailAccountDelete,
  mailAccounts,
  mailAccountsSave,
  mailConfig,
  mailConfigSave,
  mailLogs,
  mailStats,
  mailTest
} from '@/api/admin'
import { toast as dsToast, confirmDelete } from '@/utils/notify'

/* ---------------------------------------------------------------
 * 状态（对应原页 loadConfig / loadAccounts / loadStats / loadLogs 填充的 DOM）
 * ------------------------------------------------------------- */
/** 通用设置表单（原 #cfg-host / #cfg-port / #cfg-fromName / #cfg-subject / #cfg-body） */
const cfg = reactive({ host: '', port: '', fromName: '', subjectTemplate: '', bodyTemplate: '' })

/** 发件账号行（原 #acc-body 里的 tr，含 .r-username / .r-password / .r-limit / .r-enabled） */
const accountRows = ref([])
/** 账号表格初始文案「加载中…」的显隐（原页由 jQuery .html() 直接切换） */
const accLoading = ref(true)

/** 今日发送统计（原 #stat-box / #stat-table） */
const stat = ref(null)
/**
 * 最近发送日志（原 #log-body）。
 * 时间列与原页保持一致，直接展示后端返回的 createdAt 原值（MailLog.createdAt 未加
 * @JsonFormat，Spring Boot 默认序列化为 ISO-8601 字符串），不做本地格式化。
 */
const logs = ref([])
const logLoading = ref(true)

/** 测试发送收件邮箱（原 #test-to） */
const testTo = ref('')

/* ---------------------------------------------------------------
 * 排版优化新增的状态与计算属性（不改变任何原有数据来源）
 * ------------------------------------------------------------- */
const onlyFailed = ref(false)
const savingConfig = ref(false)
const savingAccounts = ref(false)
const sendingTest = ref(false)
/** 配置快照：用于「有未保存的修改」提示 */
const configSnapshot = ref('')
/** 账号快照：同上 */
const accountsSnapshot = ref('[]')

const enabledCount = computed(() => accountRows.value.filter(a => a.enabled == 1).length)
const firstEnabledName = computed(() => {
  const hit = accountRows.value.find(a => a.enabled == 1)
  return hit ? (hit.username || '已配置账号') : ''
})
const sentTotal = computed(() => {
  const v = stat.value
  if (v && v.sentToday != null) return v.sentToday
  return accountRows.value.reduce((sum, a) => sum + (a.sentToday || 0), 0)
})
const limitTotal = computed(() => {
  const v = stat.value
  if (v && v.dailyLimit != null) return v.dailyLimit
  return accountRows.value.filter(a => a.enabled == 1).reduce((sum, a) => sum + (a.dailyLimit || 0), 0)
})
const todaySuccess = computed(() => (stat.value && stat.value.todaySuccess) || 0)
const todayFail = computed(() => (stat.value && stat.value.todayFail) || 0)
const configDirty = computed(() => JSON.stringify({ ...cfg }) !== configSnapshot.value)
const accountsDirty = computed(() =>
  JSON.stringify(accountRows.value.map(a => [a.id, a.username, a.password, a.dailyLimit, a.enabled])) !== accountsSnapshot.value)

/** 账号配额进度：>=100% 红、>=80% 黄、停用灰、其余绿 */
function quotaPct(a) {
  const limit = Number(a.dailyLimit) || 0
  const sent = Number(a.sentToday) || 0
  if (!limit) return 0
  return Math.min(100, Math.round(sent * 100 / limit))
}
function quotaClass(a) {
  const pct = quotaPct(a)
  if (a.enabled != 1) return 'progress-bar-default'
  if (pct >= 100) return 'progress-bar-danger'
  if (pct >= 80) return 'progress-bar-warning'
  return 'progress-bar-success'
}

/* ---------------------------------------------------------------
 * 运行时辅助
 * ------------------------------------------------------------- */
/** 组件是否已卸载：异步回调里避免继续改状态 / 碰 DOM */
let disposed = false
/** 当前打开的 jquery-confirm 弹窗（卸载时关闭） */
let confirmBox = null
/** 新增行的自增标记（v-for 的稳定 key） */
let rowSeq = 0
/**
 * 定时器统一回收口。
 * 原 mail.html 没有 setTimeout/setInterval，这里保留空集合只为在卸载钩子里
 * 有一条明确的清理路径（后续若加轮询刷新，push 进来的 id 会被自动清掉）。
 */
const timers = []

function jq() {
  return window.jQuery
}

/** 原页的 $.confirm({ title:'提示', content, type })：成功 green，失败 red */
function notify(content, type = 'green') {
  // 统一走顶部轻提示（jquery-confirm 已由 utils/notify 接管）
  const fn = type === 'red' ? dsToast.error : type === 'orange' ? dsToast.warning : dsToast.success
  fn(content)
}

function notifyError(e) {
  notify((e && e.message) || '请求失败', 'red')
}

/* ---------------------------------------------------------------
 * 通用设置
 * ------------------------------------------------------------- */
async function loadConfig() {
  try {
    const d = (await mailConfig()) || {}
    // 未配置时后端返回 { configured:false }，缺失字段按空值处理
    cfg.host = d.host || ''
    cfg.port = d.port == null ? '' : d.port
    cfg.fromName = d.fromName || ''
    cfg.subjectTemplate = d.subjectTemplate || ''
    cfg.bodyTemplate = d.bodyTemplate || ''
    configSnapshot.value = JSON.stringify({ ...cfg })
  } catch (e) {
    notifyError(e)
  }
}

async function saveConfig() {
  // 请求体结构照抄原 saveConfig()
  const data = {
    host: String(cfg.host == null ? '' : cfg.host).trim(),
    port: parseInt(cfg.port) || 587,
    fromName: String(cfg.fromName == null ? '' : cfg.fromName).trim(),
    subjectTemplate: cfg.subjectTemplate,
    bodyTemplate: cfg.bodyTemplate
  }
  savingConfig.value = true
  try {
    await mailConfigSave(data)
    // 后端 ApiResult.ok("保存成功", null) 解包后只剩 data(null)，文案取原页兜底值
    notify('保存成功', 'green')
    configSnapshot.value = JSON.stringify({ ...cfg })
  } catch (e) {
    notifyError(e)
  } finally {
    savingConfig.value = false
  }
}

/* ---------------------------------------------------------------
 * 发件账号（原 renderRow / addRow / saveAccounts / 行内删除）
 * ------------------------------------------------------------- */
async function loadAccounts() {
  accLoading.value = true
  try {
    const list = (await mailAccounts()) || []
    accountRows.value = list.map(a => ({
      key: 'a' + a.id + '-' + (++rowSeq),
      id: a.id == null ? null : a.id,
      username: a.username || '',
      password: '', // 原页密码框始终为空，留空=不修改
      dailyLimit: a.dailyLimit == null ? 450 : a.dailyLimit,
      sentToday: a.sentToday || 0,
      enabled: a.enabled == 1 ? 1 : 0
    }))
  } catch (e) {
    // 原页 loadAccounts 出错直接 return（不弹窗），这里落回空列表
    console.warn('[mail] 账号列表加载失败：' + ((e && e.message) || e))
    accountRows.value = []
  }
  accLoading.value = false
  accountsSnapshot.value = JSON.stringify(accountRows.value.map(a => [a.id, a.username, a.password, a.dailyLimit, a.enabled]))
}

/** 原 addRow()：renderRow({}) —— 上限默认 450、今日已发 0、未勾选启用、密码占位「必填」 */
function addRow() {
  accountRows.value.push({
    key: 'n' + (++rowSeq),
    id: null,
    username: '',
    password: '',
    dailyLimit: 450,
    sentToday: 0,
    enabled: 0
  })
}

/** 行内删除：未保存的新行直接移除；已存在的行先 confirm 再 DELETE */
function deleteRow(a, index) {
  if (!a.id) {
    accountRows.value.splice(index, 1)
    return
  }
  confirmDelete({
    name: a.username || ('#' + a.id),
    extra: '删除后该发信账号不再可用，已发送记录保留，操作不可恢复。',
    onConfirm: () => mailAccountDelete(a.id)
      .then(() => {
        if (disposed) return
        accountRows.value.splice(index, 1)
        loadStats()
        dsToast.success('账号已删除')
      })
      .catch(e => {
        if (disposed) return
        notifyError(e)
      })
  })
}

async function saveAccounts() {
  const rows = []
  accountRows.value.forEach(a => {
    const username = String(a.username == null ? '' : a.username).trim()
    if (!username) return // 原页跳过空邮箱行
    rows.push({
      id: a.id || null,
      username: username,
      password: a.password || '',
      dailyLimit: parseInt(a.dailyLimit) || 450,
      enabled: a.enabled == 1
    })
  })
  savingAccounts.value = true
  try {
    await mailAccountsSave({ accounts: rows })
    notify('保存成功', 'green')
    await loadAccounts()
  } catch (e) {
    notifyError(e)
  } finally {
    savingAccounts.value = false
  }
}

/* ---------------------------------------------------------------
 * 统计 / 测试 / 日志
 * ------------------------------------------------------------- */
/** 原 loadStats() 遍历的 d.accounts（为空时渲染「无账号」行） */
const statAccounts = computed(() => (stat.value && stat.value.accounts) || [])

/* 表格行用计算属性过滤（避免在 <tbody> 中用 <template v-else> 生成 Fragment 导致补丁错位） */
const visibleAccountRows = computed(() => (accLoading.value || !accountRows.value.length ? [] : accountRows.value))
const visibleStatAccounts = computed(() => (stat.value ? statAccounts.value : []))
const visibleLogs = computed(() => {
  const list = logLoading.value || !logs.value.length ? [] : logs.value
  return onlyFailed.value ? list.filter(l => l.status != 1) : list
})

/** 统计文案（等价原页面 $('#stat-box').html(...)，避免 Fragment 补丁问题） */
const statHtml = computed(() => {
  const v = stat.value
  if (!v) return ''
  return `今日已发送 <b>${v.sentToday ?? 0}</b> / <b>${v.dailyLimit ?? 0}</b> 封　|　成功 ${v.todaySuccess ?? 0} 封　|　失败 ${v.todayFail ?? 0} 封`
})

/** 状态标签：停用（default）/ 已满（warning）/ 可用（success） */
function statLabelClass(a) {
  if (a.enabled != 1) return 'label label-default'
  return a.sentToday >= a.dailyLimit ? 'label label-warning' : 'label label-success'
}
function statLabelText(a) {
  if (a.enabled != 1) return '停用'
  return a.sentToday >= a.dailyLimit ? '已满' : '可用'
}

async function loadStats() {
  try {
    stat.value = await mailStats()
  } catch (e) {
    // 原页 loadStats 出错直接 return（统计区保持隐藏）
    console.warn('[mail] 统计加载失败：' + ((e && e.message) || e))
  }
}

async function loadLogs() {
  logLoading.value = true
  try {
    logs.value = (await mailLogs()) || []
  } catch (e) {
    console.warn('[mail] 日志加载失败：' + ((e && e.message) || e))
    logs.value = []
  }
  logLoading.value = false
}

async function sendTest() {
  const to = String(testTo.value == null ? '' : testTo.value).trim()
  if (!to) {
    notify('请输入收件邮箱', 'red')
    return
  }
  sendingTest.value = true
  try {
    await mailTest({ to: to })
    notify('已发送', 'green')
    await Promise.all([loadStats(), loadLogs()])
  } catch (e) {
    notifyError(e)
  } finally {
    sendingTest.value = false
  }
}

/* ---------------------------------------------------------------
 * 生命周期
 * ------------------------------------------------------------- */
/** 原页 $(function(){ loadAll(); }) */
function loadAll() {
  loadConfig()
  loadAccounts()
  loadStats()
  loadLogs()
}

/** bootstrap-table：本页是原生表格（与原模板一致），仅在意外初始化过时做防御式销毁 */
function destroyBootstrapTable() {
  // 注意：jq() 是「零参数」辅助函数（返回 window.jQuery），
  // 不能写成 jq('#acc-body')：打包时 Terser 会把无用的实参优化掉，
  // 变成 window.jQuery.closest(...) → TypeError，进而让 onBeforeUnmount 抛错、
  // 页面卸载中断、之后所有后台菜单点击失效。
  const $ = jq()
  if (!$ || !$.fn || !$.fn.bootstrapTable) return
  try {
    const $table = $('#acc-body').closest('table')
    if ($table.length && $table.data('bootstrap.table')) $table.bootstrapTable('destroy')
  } catch (e) {
    console.warn('[MailSettings] 表格销毁失败：', e.message)
  }
}

function closeConfirm() {
  if (confirmBox && typeof confirmBox.close === 'function') confirmBox.close()
  confirmBox = null
}

onMounted(async () => {
  // 先渲染出与原模板一致的 DOM，再取数（沿用本站老插件的通用约定）
  await nextTick()
  loadAll()
})

onBeforeUnmount(() => {
  // 清理阶段整体兜底：任何清理异常都不能中断 SPA 路由切换
  // （曾因某个页面卸载清理抛错，导致之后所有后台菜单点击都失效）
  try {
    disposed = true
    closeConfirm()
    destroyBootstrapTable()
    // 全局事件（本页未注册，保留命名空间解绑以防后续扩展）
    const $ = jq()
    if ($) $(window).off('.mailSettings')
    timers.forEach(id => clearTimeout(id))
    timers.length = 0

  } catch (err) {
    console.warn('[cleanup]', err && err.message)
  }
})
</script>

<style scoped>
/* 仅作用于本组件，不修改任何全局样式；页面骨架/卡片/表格/空状态一律用 src/styles/admin-ui.css 的 ad-* */

/* 配额进度条（首页概览块同款细条），颜色由 quotaClass() 决定 */
.mail-progress { height: 6px; margin-bottom: 4px; border-radius: 3px; background: #eef1f6; box-shadow: none; }
.mail-progress .progress-bar { border-radius: 3px; }
.mail-progress .progress-bar-default { background-color: #b6c0cd; }

/* 日志时间列 */
.mail-time { color: var(--ad-text-sub); font-size: 12px; white-space: nowrap; }

/* 日志错误列：长错误信息换行，避免把表格撑破 */
.mail-err { max-width: 160px; word-break: break-all; }

/* 「只看失败」开关 */
.mail-switch { margin: 0; font-weight: 400; font-size: 12.5px; color: var(--ad-text-sub); }
.mail-switch input { margin-right: 4px; vertical-align: middle; }

/* 账号表格为 flush 卡片体，尾部说明需要自己留内边距 */
.mail-legend { padding: 0 16px 14px; }
</style>
