<template>
  <!--
    个人中心 —— 原 Thymeleaf 模板 frontend/templates/index/center.html 的 1:1 移植。
    根节点用 <FrontLayout>（原页面的 loader / topbar / footer / cd_top 四个 th:replace 片段），
    cd_top 默认启用（center.html 启用了它）。DOM 标签、class、内联 style 与原模板逐字一致。
  -->
  <FrontLayout>
    <!-- ===== 背景封面（原 center.html 的 #content-holder / semplice-cover 区块，原样保留） ===== -->
    <div id="content-holder" data-active-post="4262">
      <div id="content-4262" class="content-container active-content  hide-on-init">
        <div class="transition-wrap">
          <div class="sections">

            <section id="cover-4262" class="semplice-cover" data-height="fullscreen"
                     data-column-mode-sm="single" data-column-mode-xs="single" data-valign="center"
                     data-cover="visible" data-cover-effect="zoom">
              <div class="semplice-cover-inner" data-effect-settings='[]'>

                <div class="cover-image-wrapper fp-bg" data-src="static/image/cc_bg.jpg" data-width="2560"
                     data-height="1600" data-size="cover">
                  <div class="cover-image"></div>
                </div>

              </div>
            </section>

          </div>
        </div>
      </div>
    </div>

    <!-- ===== 个人资料（原 loginUser，现来自 GET /api/user/center 的 user） ===== -->
    <div class="login-wrapper-l">
      <div class="header"><img class="my_center" style="text-align: center;margin: 20px auto 0;" :src="avatar" alt="" loading="lazy"></div>
      <div class="form-warpper-l">
        <p class="input-item">name : {{ user.username }}</p>
        <p class="input-item">tel: {{ user.tel }}</p>
        <p class="input-item">email :{{ user.email }}</p>
        <p class="input-item">time : {{ formatYmd(user.createTime) }}</p>
        <div class="btn">等风也等你</div>

      </div>
      <div class="msg">
        <!--        Do you already have an account ? <a href="/"> Login</a>-->
        Do you want to browse my website ?

      </div><a href="https://errr.me" style="margin-left: 40%;margin-top: -30%" target="_blank"> www.errr.me</a>
    </div>

    <!-- MAIN WRAPPER
    ============================================= -->
    <div id="main-wrapper" class="clearfix">

      <div id="main" class="site-main clearfix">

        <!-- CONTENT START-->
        <div id="content" class="clearfix">

          <!-- BLOG START-->
          <div class="blog right-sidebar wrapper clearfix">
            <div class="container">
              <div class="row">


                <!-- BLOG LOOP START
        ============================================= -->
                <div class="col-md-9 col-md-offset-1 offsetmargin">

                  <div class="blog-section content-section">

                    <article v-for="like in likes" :key="like.id"
                             class="blog-item wow fadeIn clearfix post type-post status-publish format-standard has-post-thumbnail hentry category-gushi">

                      <div class="post-thumb graypicture">
                        <a :href="'/culture/' + like.id">
                          <img width="509" height="379"
                               :src="coverUrl(like.fmUrl)"
                               class="attachment-couper-post-thumb-loop size-couper-post-thumb-loop wp-post-image"
                               alt=""
                               sizes="(max-width: 509px) 100vw, 509px" loading="lazy"> </a>
                      </div><!-- thumbnail-->


                      <div class="post-content-wrap">
                        <a :href="'/culture/' + like.id">
                          <div class="date">
                            <p>{{ formatYmd(like.createTime) }}</p>
                          </div>
                        </a>

                        <div class="post-content">
                          <a :href="'/culture/' + like.id">
                            <h3 class="post-title">{{ like.cultureName }}</h3>
                          </a>
                          <div class="post-meta">
                            <a :href="'/culture/' + like.id" class="comments">
                              <i class="icon-chat-block"></i>{{ like.view }}浏览
                            </a>
                          </div>
                          <div class="float-content">
                            <p class="post-text">
                              <a href="javascript:void(0)" @click.prevent="onCancelLike(like)">取消收藏</a> </p>
                            <div class="post-bottom">
                              <a :href="'/culture/' + like.id" class="button-normal">Read More <i
                                      class="fa fa-angle-right"></i></a>

                            </div>
                          </div>
                        </div>
                      </div>
                    </article>
                  </div>

                </div>
                <!-- BLOG LOOP END -->

              </div>
            </div>
          </div>
          <!-- BLOOG END -->

        </div>
        <!-- CONTENT END -->

      </div>
      <!-- site-main -->

    </div>
  </FrontLayout>
</template>

<script setup>
/**
 * 个人中心（个人资料 + 我的收藏）
 *
 * 服务端渲染 → REST：
 *   loginUser            ← GET /api/user/center（@/api/front.js::getCenter）→ { user, likes }
 *   likes（List<Culture>）← 同上；coverUrl(like.fmUrl) 对应原 th:src="@{'/showFmImg/'+...}"
 *   /culture/cancel/{id}  ← POST /api/culture/cancel/{id}（@/api/front.js::cancelLike）
 *
 * 原页面依赖的脚本按原顺序在挂载后加载：
 *   原 head：jquery-3.4.1（front.html 已全局加载，等价于旧模板里的 jquery-1.11.1）→ jquery-confirm
 *   原底部：shared.scripts.min.js → frontend.scripts.min.js → 内联 semplice 配置 → frontend.min.js
 * Semplice 的 init() 依赖 DOM 与全局 semplice 配置，所以必须先 nextTick、再按序加载。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import FrontLayout from '@/layouts/FrontLayout.vue'
import { cancelLike, getCenter } from '@/api/front'
import { useFrontUserStore } from '@/stores/frontUser'
import { avatarUrl, coverUrl } from '@/utils/format'
import { loadScript, loadScripts, loadStyle, whenJQuery } from '@/utils/loadScript'
import { useSeo } from '@/utils/seo'
import { toast as dsToast, confirmDialog } from '@/utils/notify'

/**
 * SEO：个人中心是「需登录的私有页面」，没有任何收录价值 ——
 * 用 noindex 明确告诉搜索引擎不要索引（robots.txt 里的 Disallow: /center 是双保险，
 * 因为 robots.txt 只拦抓取、不保证已收录的 URL 被移除，noindex 才能真正阻止索引）。
 */
useSeo({
  title: '个人中心 · 遇你',
  description: '遇你 · 传统文化 —— 个人中心：查看个人资料与我的收藏。',
  noindex: true
})

const store = useFrontUserStore()

/** 登录用户（原 loginUser） */
const user = ref({})
/** 我的收藏（原 likes） */
const likes = ref([])

/** 头像：原模板用静态 icon.jpg；用户有 headImg 时用 avatarUrl 取真实头像 */
const avatar = computed(() =>
  user.value && user.value.headImg ? avatarUrl(user.value.headImg) : '/index/images/icon.jpg'
)

/** 等价于 Thymeleaf ${#dates.format(x, 'yyyy-M-d')}：月、日不补零 */
function formatYmd(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/* ---------------------------------------------------------------
 * 原 center.html 底部内联脚本 var semplice = {...}
 * frontend.min.js（Semplice）启动时会立即读取这些配置，必须在它之前写入 window。
 * 未搬 password_form / gallery：本页没有密码表单与画廊标记，与页面行为无关。
 * ------------------------------------------------------------- */
const SEMPLICE_CONFIG = {
  default_api_url: 'https://vanschneider.com/wp-json',
  semplice_api_url: 'https://vanschneider.com/wp-json/semplice/v1/frontend',
  template_dir: 'https://vanschneider.com/wp-content/themes/semplice5',
  category_base: '/category/',
  tag_base: '/tag/',
  nonce: 'e113267a4f',
  frontend_mode: 'static',
  static_transitions: 'enabled',
  site_name: 'House of van Schneider',
  base_url: 'https://vanschneider.com',
  frontpage_id: '6154',
  blog_home: 'https://vanschneider.com',
  blog_navbar: '',
  sr_status: 'enabled',
  blog_sr_status: 'enabled',
  is_preview: '',
  portfolio_order: [7263, 7285, 7283, 7281, 7279, 7275, 7277, 7273, 7271, 7268, 7265, 5471, 5442]
}

/* ---------------------------------------------------------------
 * 原 <body> 的 class / data-* —— Semplice 的 CSS 依赖 is-frontend / static-mode
 * （如 .is-frontend #content-holder .transition-wrap 的绝对定位），
 * 这里在挂载时补上、卸载时精确还原。
 * ------------------------------------------------------------- */
const BODY_CLASSES = [
  'page-template-default', 'page', 'page-id-4262', 'is-frontend',
  'static-mode', 'static-transitions', 'mejs-semplice-ui'
]
const BODY_ATTRS = { 'data-post-type': 'page', 'data-post-id': '4262' }
let addedClasses = []
let addedAttrs = []
let disposed = false
/** jquery-confirm 弹窗实例（卸载时销毁） */
let confirmBox = null

function applyBodyClasses() {
  const body = document.body
  addedClasses = BODY_CLASSES.filter(c => !body.classList.contains(c))
  addedClasses.forEach(c => body.classList.add(c))
  addedAttrs = Object.keys(BODY_ATTRS).filter(k => !body.hasAttribute(k))
  addedAttrs.forEach(k => body.setAttribute(k, BODY_ATTRS[k]))
}

function restoreBodyClasses() {
  addedClasses.forEach(c => document.body.classList.remove(c))
  addedAttrs.forEach(k => document.body.removeAttribute(k))
  addedClasses = []
  addedAttrs = []
}

/* ---------------------------------------------------------------
 * jquery-confirm（原页面 head 里的 jconfirm，与原站交互一致）
 * ------------------------------------------------------------- */
function notify(title, content, type = 'green') {
  const fn = type === 'red' ? dsToast.error : type === 'orange' ? dsToast.warning : dsToast.success
  const shortTitle = title && !/温馨提示|^提示$/.test(title) ? title : ''
  fn(content, { detail: shortTitle })
}

/** 取消收藏 */
async function doCancel(like) {
  try {
    await cancelLike(like.id)
    // 取消成功后本地移除，列表与后端保持一致
    likes.value = likes.value.filter(item => item.id !== like.id)
    notify('温馨提示', '已取消收藏', 'green')
  } catch (e) {
    notify('温馨提示', (e && e.message) || '取消失败', 'red')
  }
}

/** 原 /culture/cancel/{id} 是整页链接，这里改为确认框 + REST 调用 */
function onCancelLike(like) {
  confirmDialog({
    title: '取消收藏',
    content: '确定要取消收藏《' + (like.cultureName || '') + '》吗？',
    detail: '取消后可随时在文化详情页重新收藏。',
    type: 'warning',
    confirmText: '取消收藏',
    cancelText: '再想想',
    onConfirm: () => doCancel(like)
  })
}

/* ---------------------------------------------------------------
 * 数据：个人资料 + 我的收藏
 * ------------------------------------------------------------- */
async function loadCenter() {
  try {
    const data = await getCenter()
    user.value = (data && data.user) || {}
    likes.value = (data && data.likes) || []
    // 同步给导航栏显示（原页面用 session.loginUserName）
    if (data && data.user) store.profile = data.user
  } catch (e) {
    notify('温馨提示', (e && e.message) || '加载失败', 'red')
  }
}

onMounted(async () => {
  // 原 head 内联 init()：未登录跳登录页。路由 meta.requiresLogin 已拦截，这里兜底。
  // 注意：/auth/login 属于认证入口（auth.html），不在前台路由表里，必须整页跳转。
  if (!store.isLogin) {
    window.location.replace('/auth/login?redirect=/center')
    return
  }

  await nextTick()

  applyBodyClasses()

  // 原 center.html <head> 的样式表（bootstrap.min.css 已由 front.html 全局加载，不重复）
  loadStyle('/index/css/plugin.css')
  loadStyle('/index/css/responsive1.css')
  loadStyle('/index/css/style-5.0.11.css')
  loadStyle('/index/css/font1.css')
  loadStyle('/index/css/frontend.min.css')
  loadStyle('/static/admin/js/jconfirm/jquery-confirm.min.css')

  loadCenter()

  try {
    // jQuery 由 front.html 全局加载（原 center.html 用的是 jquery-3.4.1）
    await whenJQuery()
    // 原页面脚本顺序：jquery-confirm → shared.scripts.min.js → frontend.scripts.min.js
    await loadScripts([
      '/static/admin/js/jconfirm/jquery-confirm.min.js',
      '/index/js/shared.scripts.min.js',
      '/index/js/frontend.scripts.min.js'
    ])
    if (disposed) return
    // 原底部内联配置必须在 frontend.min.js 之前就绪
    window.semplice = SEMPLICE_CONFIG
    await loadScript('/index/js/frontend.min.js')
    if (disposed) return
    // Semplice init() 会用 ScrollReveal 移除 .hide-on-init 显示封面；脚本异常时兜底
    const content = document.getElementById('content-4262')
    if (content) content.classList.remove('hide-on-init')
  } catch (e) {
    console.warn('[center] 原页面脚本加载失败：', e)
  }
})

onBeforeUnmount(() => {
  disposed = true
  if (confirmBox && typeof confirmBox.destroy === 'function') {
    try { confirmBox.destroy() } catch (e) { /* 已关闭 */ }
  }
  confirmBox = null
  // 移除 Semplice 在 static 模式下注册的全局 <a> 点击拦截（会整页跳转），避免影响其它路由
  const $ = window.jQuery
  if ($) $(document).off('click', 'a')
  restoreBodyClasses()
})
</script>

<!-- 原 center.html <head> 内的内联样式块：原样保留（非 scoped，选择器与原文件一致） -->
<style type="text/css">
    body::-webkit-scrollbar {
        display: none;
    }

    #content-4262 .semplice-cover .show-more svg,
    #content-4262 .semplice-cover .show-more img {
        width: 2.9444rem;
    }

    .cover-transparent {
        background: rgba(0, 0, 0, 0) !important;
    }

    #content-4262 .sections {
        margin-top: 0px !important;
    }

    #content-4262 #cover-4262 .cover-image {
        background-image: url(/index/images/cc_bg.jpg);
        background-size: cover;
        background-position: top left;
        background-attachment: scroll;
    }

    * {
        padding: 0;
        margin: 0;
        font-family: 'Open Sans Light';
        letter-spacing: .05em;
    }

    .login-wrapper-l {
        background-color: #fff;
        width: 350px;
        height: 500px;
        border-radius: 15px;
        margin-top: 400px;
        position: relative;
        left: 50%;
        transform: translate(-50%, -50%);
        opacity: 0.5;
    }

    .login-wrapper-l .header {
        text-align: center;
        line-height: 120px;
        margin-bottom: 30px;
    }

    .login-wrapper-l .header .my_center {
        margin-top: 30px;
        width: 90px;
        border-radius: 45px;
    }

    .login-wrapper-l .form-warpper-l .input-item {
        margin: 0 auto;
        display: block;
        width: 80%;
        margin-bottom: 10px;
        border: 0;
        padding: 10px;
        /*border-bottom: 1px solid rgb(128, 125, 125);*/
        font-size: 15px;
        outline: none;
    }

    .login-wrapper-l .form-warpper-l .input-item::placeholder {
        /* text-transform: uppercase; */
        text-transform: lowercase;
    }

    .login-wrapper-l .form-warpper-l .btn {
        text-align: center;
        padding: 10px;
        width: 80%;
        margin: 20px 0 0 37px;
        background-image: linear-gradient(to right, #a6c1ee, #fbc2eb);
        color: #fff;
        font-size: 13px;
    }

    .login-wrapper-l .msg {
        font-size: 13px;
        text-align: center;
        line-height: 80px;
        margin-top: -5px;
    }


    /*body {*/
    /*    height: 300px;*/
    /*}*/
</style>
