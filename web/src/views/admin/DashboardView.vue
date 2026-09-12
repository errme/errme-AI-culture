<template>
  <div class="container-fluid ad-page">
    <AdminPageHeader
      icon="mdi mdi-view-dashboard-outline"
      title="后台首页"
      desc="站点概览与待办入口：内容规模、互动数据、待审评论与回收站，以及最近发布的内容。"
    >
      <template #actions>
        <button class="btn btn-default btn-sm" :disabled="loading" @click="loadAll">
          <i class="mdi mdi-refresh"></i> 刷新
        </button>
        <router-link class="btn btn-primary btn-sm" to="/admin/culture">
          <i class="mdi mdi-plus"></i> 进入内容管理
        </router-link>
      </template>
    </AdminPageHeader>

    <!-- 核心指标 -->
    <div class="ad-tiles">
      <AdminStatTile label="总浏览量" :value="stats.totalView" icon="mdi mdi-eye-outline" />
      <AdminStatTile label="文化数量" :value="stats.totalCulture" icon="mdi mdi-image-multiple-outline" tone="success" />
      <AdminStatTile label="注册人数" :value="stats.totalUser" icon="mdi mdi-account-multiple-outline" tone="primary" />
      <AdminStatTile label="收藏量" :value="stats.totalLike" icon="mdi mdi-heart-outline" tone="warning" />
    </div>

    <!-- 待办 -->
    <div class="ad-tiles">
      <div class="ad-tile" style="cursor:pointer;" @click="$router.push('/admin/comment')">
        <div class="ad-tile__icon" :class="pendingComments > 0 ? 'ad-tile__icon--warning' : 'ad-tile__icon--success'">
          <i class="mdi mdi-comment-check-outline"></i>
        </div>
        <div>
          <span class="ad-tile__label">待审评论</span>
          <span class="ad-tile__value">{{ pendingComments }}</span>
          <div class="ad-tile__hint">{{ pendingComments > 0 ? '点击前往审核' : '暂无待处理' }}</div>
        </div>
      </div>
      <div class="ad-tile" style="cursor:pointer;" @click="$router.push('/admin/recycle')">
        <div class="ad-tile__icon" :class="recycleTotal > 0 ? 'ad-tile__icon--danger' : ''">
          <i class="mdi mdi-delete-restore"></i>
        </div>
        <div>
          <span class="ad-tile__label">回收站</span>
          <span class="ad-tile__value">{{ recycleTotal }}</span>
          <div class="ad-tile__hint">已删除内容，可恢复或彻底删除</div>
        </div>
      </div>
      <div class="ad-tile">
        <div class="ad-tile__icon"><i class="mdi mdi-email-fast-outline"></i></div>
        <div>
          <span class="ad-tile__label">今日邮件</span>
          <span class="ad-tile__value">{{ mail.sentToday }}<small> / {{ mail.dailyLimit }}</small></span>
          <div class="ad-tile__hint">成功 {{ mail.todaySuccess }} · 失败 {{ mail.todayFail }}</div>
        </div>
      </div>
      <div class="ad-tile">
        <div class="ad-tile__icon ad-tile__icon--success"><i class="mdi mdi-account-key-outline"></i></div>
        <div>
          <span class="ad-tile__label">我的角色</span>
          <span class="ad-tile__value" style="font-size:16px;">{{ myRoleText }}</span>
          <div class="ad-tile__hint"><router-link to="/admin/me">查看资料</router-link></div>
        </div>
      </div>
    </div>

    <div class="row">
      <div class="col-lg-8">
        <div class="ad-card">
          <div class="ad-card__head">
            <h5 class="ad-card__title"><i class="mdi mdi-clock-outline"></i> 最近发布</h5>
            <div class="ad-card__actions">
              <router-link class="btn btn-default btn-sm" to="/admin/culture">查看全部</router-link>
            </div>
          </div>
          <div class="ad-card__body ad-card__body--flush">
            <div class="ad-table-wrap">
              <table class="ad-table">
                <thead>
                  <tr>
                    <th>名称</th>
                    <th style="width:130px;">分类</th>
                    <th style="width:90px;">浏览</th>
                    <th style="width:150px;">创建时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="loading">
                    <td colspan="4" class="text-center text-muted" style="padding:18px 0;">加载中…</td>
                  </tr>
                  <tr v-else-if="!recent.length">
                    <td colspan="4">
                      <AdminEmpty icon="mdi mdi-image-off-outline" text="还没有内容，去内容管理发布第一条吧">
                        <template #action>
                          <router-link class="btn btn-primary btn-sm" to="/admin/culture">前往内容管理</router-link>
                        </template>
                      </AdminEmpty>
                    </td>
                  </tr>
                  <tr v-for="row in recent" :key="row.id">
                    <td>
                      <router-link :to="{ path: '/admin/culture', query: { t: Date.now() } }" class="ad-link">
                        {{ row.cultureName }}
                      </router-link>
                    </td>
                    <td>{{ (row.category && row.category.categoryName) || '—' }}</td>
                    <td class="ad-num">{{ row.view || 0 }}</td>
                    <td class="ad-num">{{ formatTime(row.createTime) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>

      <div class="col-lg-4">
        <div class="ad-card">
          <div class="ad-card__head"><h5 class="ad-card__title"><i class="mdi mdi-lightning-bolt-outline"></i> 快捷操作</h5></div>
          <div class="ad-card__body">
            <div class="ad-quick">
              <router-link to="/admin/culture" class="ad-quick__item"><i class="mdi mdi-plus-box-outline"></i> 发布内容</router-link>
              <router-link to="/admin/comment" class="ad-quick__item"><i class="mdi mdi-comment-check-outline"></i> 评论审核</router-link>
              <router-link to="/admin/tag" class="ad-quick__item"><i class="mdi mdi-tag-multiple-outline"></i> 标签管理</router-link>
              <router-link to="/admin/recycle" class="ad-quick__item"><i class="mdi mdi-delete-restore"></i> 回收站</router-link>
              <router-link to="/admin/user" class="ad-quick__item"><i class="mdi mdi-account-cog-outline"></i> 用户与角色</router-link>
              <router-link to="/admin/mail" class="ad-quick__item"><i class="mdi mdi-email-cog-outline"></i> 邮件设置</router-link>
            </div>
          </div>
        </div>

        <div class="ad-card">
          <div class="ad-card__head"><h5 class="ad-card__title"><i class="mdi mdi-chart-donut"></i> 内容结构</h5></div>
          <div class="ad-card__body">
            <div v-for="bar in structure" :key="bar.label" class="ad-bar">
              <div class="ad-bar__head"><span>{{ bar.label }}</span><b>{{ bar.value }}</b></div>
              <div class="ad-bar__track"><div class="ad-bar__fill" :class="bar.tone" :style="{ width: bar.pct + '%' }"></div></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 后台首页（重做）
 *
 * 与老版的区别：老版是一块 830px 高的静态块 + 4 张 PNG 占位（原模板的图表脚本其实是空的，
 * 没有任何 canvas 实例），信息量几乎为零。新版改成「指标 + 待办 + 最近发布 + 快捷操作」，
 * 并且**不再加载 Chart.js**（首页因此少下 ~568KB）。
 *
 * 数据源（全部已有接口，缺一个都不影响其它卡片）：
 *   GET /api/admin/stats               → totalView / totalCulture / totalUser / totalLike
 *   GET /api/admin/comment/pending     → 待审评论数
 *   GET /api/admin/recycle/counts      → 回收站各类型数量（接口新增中，取不到就隐藏为 0）
 *   GET /api/admin/mail/stats          → 今日发送统计
 *   GET /api/admin/culture/list?page=1&pageSize=5 → 最近发布
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AdminPageHeader from '@/components/admin/AdminPageHeader.vue'
import AdminStatTile from '@/components/admin/AdminStatTile.vue'
import AdminEmpty from '@/components/admin/AdminEmpty.vue'
import { adminProfile, adminStats, commentPendingCount, cultureList, mailStats } from '@/api/admin'
import { recycleCounts } from '@/api/admin'

const stats = reactive({ totalView: 0, totalCulture: 0, totalUser: 0, totalLike: 0 })
const recent = ref([])
const pendingComments = ref(0)
const recycleTotal = ref(0)
const mail = reactive({ sentToday: 0, dailyLimit: 0, todaySuccess: 0, todayFail: 0 })
const myRoleText = ref('—')
const loading = ref(true)

let disposed = false
onBeforeUnmount(() => { disposed = true })

function toNum(v) { return v == null ? 0 : Number(v) || 0 }

/** yyyy-MM-dd HH:mm（后端 createTime 可能是 ISO 或 'yyyy-MM-dd HH:mm:ss'） */
function formatTime(value) {
  if (!value) return '—'
  const d = new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value).slice(0, 16).replace('T', ' ')
  const p = n => (n < 10 ? '0' + n : String(n))
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 内容结构条形图（用已有数字算占比，无需额外接口） */
const structure = computed(() => {
  const items = [
    { label: '浏览量', value: stats.totalView, tone: 'is-primary' },
    { label: '文化数量', value: stats.totalCulture, tone: 'is-success' },
    { label: '注册人数', value: stats.totalUser, tone: 'is-warning' },
    { label: '收藏量', value: stats.totalLike, tone: 'is-danger' }
  ]
  const max = Math.max(1, ...items.map(i => i.value))
  return items.map(i => ({ ...i, pct: Math.max(3, Math.round(i.value * 100 / max)) }))
})

async function loadAll() {
  loading.value = true
  // 各卡片互不阻塞：任何一项失败都不影响其它项
  const tasks = [
    adminStats().then(d => {
      if (disposed || !d) return
      stats.totalView = toNum(d.totalView)
      stats.totalCulture = toNum(d.totalCulture)
      stats.totalUser = toNum(d.totalUser)
      stats.totalLike = toNum(d.totalLike)
    }),
    commentPendingCount().then(d => { if (!disposed && d) pendingComments.value = toNum(d.pending) }),
    cultureList({ page: 1, pageSize: 5 }).then(d => { if (!disposed && d) recent.value = (d.rows || []) }),
    mailStats().then(d => {
      if (disposed || !d) return
      mail.sentToday = toNum(d.sentToday)
      mail.dailyLimit = toNum(d.dailyLimit)
      mail.todaySuccess = toNum(d.todaySuccess)
      mail.todayFail = toNum(d.todayFail)
    }),
    recycleCounts().then(d => {
      if (disposed || !d) return
      recycleTotal.value = Object.keys(d).reduce((sum, k) => sum + toNum(d[k]), 0)
    }),
    adminProfile().then(d => {
      if (disposed || !d) return
      const user = d.user || d
      myRoleText.value = user.nickname || user.username || '—'
    })
  ]
  await Promise.all(tasks.map(p => p.catch(() => null)))
  if (!disposed) loading.value = false
}

onMounted(async () => {
  await nextTick()
  await loadAll()
})
</script>

<style scoped>
.ad-link { color: var(--ad-primary); }
.ad-link:hover { text-decoration: underline; }

.ad-quick { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.ad-quick__item {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px; border: 1px solid var(--ad-border); border-radius: var(--ad-radius-sm);
  color: var(--ad-text); font-size: 13px; background: #fff;
}
.ad-quick__item:hover { border-color: var(--ad-primary); color: var(--ad-primary); background: #fafbff; }
.ad-quick__item i { font-size: 17px; color: var(--ad-primary); }

.ad-bar + .ad-bar { margin-top: 12px; }
.ad-bar__head { display: flex; justify-content: space-between; font-size: 12.5px; color: var(--ad-text-sub); margin-bottom: 5px; }
.ad-bar__track { height: 6px; border-radius: 3px; background: #eef1f6; overflow: hidden; }
.ad-bar__fill { height: 100%; border-radius: 3px; background: var(--ad-primary); }
.ad-bar__fill.is-success { background: var(--ad-success); }
.ad-bar__fill.is-warning { background: var(--ad-warning); }
.ad-bar__fill.is-danger { background: var(--ad-danger); }
</style>
