<template>
  <section id="comment-section" class="comment-section">
    <h3 class="comment-section__title">
      留言 <small v-if="total">（{{ total }}）</small>
    </h3>

    <!-- 评论列表 -->
    <div v-if="loading" class="comment-section__empty">评论加载中…</div>
    <template v-else>
      <div v-if="!comments.length" class="comment-section__empty">
        还没有留言，来说点什么吧。
      </div>
      <ul v-else class="comment-list">
        <li v-for="item in comments" :key="item.id" class="comment-item">
          <div class="comment-item__head">
            <span class="comment-item__name">{{ item.nickname || '匿名' }}</span>
            <span class="comment-item__time">{{ formatTime(item.createTime) }}</span>
          </div>
          <!-- 内容已在服务端做 HTML 白名单清洗，这里安全输出 -->
          <div class="comment-item__body" v-html="item.content"></div>
          <div class="comment-item__actions">
            <a href="javascript:void(0)" @click="replyTo(item)">回复</a>
          </div>
          <ul v-if="item.children && item.children.length" class="comment-children">
            <li v-for="child in item.children" :key="child.id" class="comment-item comment-item--child">
              <div class="comment-item__head">
                <span class="comment-item__name">{{ child.nickname || '匿名' }}</span>
                <span class="comment-item__time">{{ formatTime(child.createTime) }}</span>
              </div>
              <div class="comment-item__body" v-html="child.content"></div>
            </li>
          </ul>
        </li>
      </ul>
    </template>

    <!-- 未登录：提示登录后再留言（不放表单，避免白填） -->
    <div v-if="!user.isLogin" class="comment-login">
      <p class="comment-login__text">登录后才能留言</p>
      <button class="comment-form__submit" @click="goLogin">去登录</button>
    </div>

    <!-- 已登录：昵称/邮箱取当前账号，只需填内容 -->
    <div v-else class="comment-form card-form">
      <h4 class="comment-form__title">
        {{ parent ? ('回复 ' + (parent.nickname || '匿名')) : '发表留言' }}
        <a v-if="parent" href="javascript:void(0)" class="comment-form__cancel" @click="parent = null">取消回复</a>
      </h4>

      <p class="comment-form__identity">
        当前身份：<b>{{ displayName }}</b>
        <span class="comment-form__hint">（署名取自登录账号，无需填写）</span>
      </p>

      <textarea v-model="content" class="comment-form__textarea" rows="4" maxlength="1000"
                placeholder="说点什么…（支持换行，最多 1000 字）"></textarea>

      <div class="comment-form__footer">
        <span class="comment-form__tip">{{ tip }}</span>
        <button class="comment-form__submit" :disabled="submitting" @click="submit">
          {{ submitting ? '提交中…' : '提交留言' }}
        </button>
      </div>
      <p class="comment-form__note">留言提交后需管理员审核通过才会展示。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getComments, submitComment } from '@/api/front'
import { useFrontUserStore } from '@/stores/frontUser'

/**
 * 文化详情页留言区
 * - 列表只展示「审核通过」的评论（服务端已做 HTML 白名单清洗）
 * - 提交后进入待审状态，前端不做乐观插入
 * 样式用组件内 scoped CSS，不改动站点既有样式。
 */
const props = defineProps({
  cultureId: { type: [String, Number], required: true }
})

const user = useFrontUserStore()

/** 当前登录用户的显示名：优先昵称，其次用户名 */
const displayName = computed(() => {
  const p = user.profile || {}
  return p.nickname || p.username || user.account || '当前用户'
})

const comments = ref([])
const total = ref(0)
const loading = ref(true)
const submitting = ref(false)
const content = ref('')
const parent = ref(null)
const tip = ref('')

function formatTime(v) {
  if (!v) return ''
  const d = new Date(String(v).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(v)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function replyTo(item) {
  parent.value = item
  const el = document.querySelector('.comment-form__textarea')
  if (el) el.focus()
}

async function load() {
  loading.value = true
  try {
    const data = await getComments(props.cultureId)
    comments.value = data.comments || []
    total.value = data.total || 0
  } catch (e) {
    comments.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function goLogin() {
  const back = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.assign('/auth/login?redirect=' + back)
}

async function submit() {
  tip.value = ''
  if (!user.isLogin) { goLogin(); return }
  if (!content.value.trim()) { tip.value = '留言内容不能为空'; return }
  submitting.value = true
  try {
    // 只提交内容：署名与邮箱由服务端按令牌里的用户资料写入
    await submitComment({
      cultureId: Number(props.cultureId),
      parentId: parent.value ? parent.value.id : 0,
      content: content.value
    })
    content.value = ''
    parent.value = null
    tip.value = '留言已提交，审核通过后展示，谢谢！'
  } catch (e) {
    tip.value = e.message || '提交失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (user.isLogin && !user.profile) user.fetchProfile()
  load()
})
</script>

<style scoped>
.comment-section { margin: 40px 0 20px; }
.comment-login {
  margin-top: 22px;
  padding: 22px 16px;
  text-align: center;
  background: #fbf9f6;
  border: 1px dashed #e6dccd;
  border-radius: 6px;
}
.comment-login__text { margin: 0 0 12px; color: #a99a86; font-size: 14px; }
.comment-form__identity { margin: 0 0 10px; color: #6b5b48; font-size: 13px; }
.comment-form__identity b { color: #b08968; }
.comment-form__hint { color: #b9ab99; font-size: 12px; }
.comment-section__title {
  font-size: 18px;
  color: #4a3f35;
  letter-spacing: 2px;
  border-left: 3px solid #d9a06a;
  padding-left: 10px;
  margin-bottom: 18px;
}
.comment-section__empty { color: #a99a86; font-size: 13px; padding: 12px 0; }
.comment-list { list-style: none; margin: 0; padding: 0; }
.comment-item { padding: 12px 0; border-bottom: 1px dashed #eee5da; }
.comment-item--child { margin-left: 24px; border-bottom: 0; }
.comment-item__head { display: flex; align-items: center; gap: 10px; }
.comment-item__name { color: #6b5b48; font-size: 13px; font-weight: 600; }
.comment-item__time { color: #b9ab99; font-size: 12px; }
.comment-item__body { margin: 6px 0; color: #4a3f35; font-size: 14px; line-height: 1.8; word-break: break-word; }
.comment-item__actions a { color: #b08968; font-size: 12px; }
.comment-children { list-style: none; margin: 6px 0 0; padding: 0; }
.comment-form { margin-top: 22px; padding: 16px; background: #fbf9f6; border: 1px solid #eee5da; border-radius: 6px; }
.comment-form__title { margin: 0 0 10px; font-size: 15px; color: #4a3f35; }
.comment-form__cancel { float: right; font-size: 12px; color: #b08968; }
.comment-form__row { display: flex; gap: 10px; margin-bottom: 10px; }
.comment-form__input, .comment-form__textarea {
  width: 100%;
  padding: 8px 10px;
  font-size: 13px;
  color: #4a3f35;
  background: #fff;
  border: 1px solid #e6dccd;
  border-radius: 4px;
  outline: none;
}
.comment-form__textarea { resize: vertical; line-height: 1.7; }
.comment-form__input:focus, .comment-form__textarea:focus { border-color: #d9a06a; }
.comment-form__footer { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }
.comment-form__tip { color: #c0392b; font-size: 12px; }
.comment-form__submit {
  padding: 8px 22px;
  font-size: 13px;
  color: #fff;
  background: #b08968;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}
.comment-form__submit:disabled { opacity: .6; cursor: not-allowed; }
.comment-form__note { margin: 8px 0 0; color: #b9ab99; font-size: 12px; }
</style>
