<template>
  <div>
    <!-- ===== loader（原 common.html :: loader 片段，原样复刻） ===== -->
    <div id="loader"></div>

    <!-- ===== 导航栏（原 common.html :: topbar 片段） ===== -->
    <div class="topbar">
      <div class="headMenu">
        <a class="site_one_logo posttitle" href="/"></a>
        <a class="site_one_logo_scroll posttitle" href="/"></a>
        <ul class="topmenu">
          <li><a href="/culture" class="posttitle">倾一世</a></li>
          <li><a href="/sentence" class="posttitle">琴弦上</a></li>
          <!-- 全站搜索入口：沿用既有导航项 class，视觉与原导航保持一致 -->
          <li><a href="/search" class="posttitle">寻一寻</a></li>
          <li><a href="/about" class="hidden-xs posttitle">关于我</a></li>
          <li v-if="!user.isLogin">
            <a href="/auth/login" class="posttitle">登录</a>
          </li>
          <li v-else class="posttitle">
            <a href="/center" class="posttitle">{{ user.profile && user.profile.username ? user.profile.username : user.account }}</a>
            <a class="logout_ico posttitle"
               style="padding-left: 30px;padding-top: -5px;text-decoration: none"
               href="javascript:void(0)" @click.prevent="logout"></a>
          </li>
        </ul>
      </div>
    </div>

    <!-- ===== 页面内容 ===== -->
    <slot></slot>

    <!-- ===== 页脚（原 common.html :: footer 片段） ===== -->
    <footer id="footer">
      <div class="footer_con">
        <p>Copyright&nbsp;&copy;&nbsp;2021&nbsp;•&nbsp;Theme&nbsp;by&nbsp;:
          <a class="posttitle" href="http://errr.me" target="_self">Pangkun</a>&nbsp;•&nbsp;Run&nbsp;Time&nbsp;:
          <a class="posttitle" href="/admin/login" target="_self"><span id="span_dt_dt"></span></a><span
            class="my-face">(●'◡'●)ﾉ♥</span>
        </p>
        <ul>
          <li>
            <a href="https://github.com/errme" target="_blank">
              <img src="/index/images/github.png" alt="">
            </a>
          </li>
        </ul>
      </div>
    </footer>

    <!-- ===== 返回顶部（原 common.html :: cd_top 片段，culture/detail 页原本未启用） ===== -->
    <template v-if="cdTop">
      <a href="#0" class="cd-top" @click.prevent="scrollToTop">Top</a>
    </template>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import { useFrontUserStore } from '@/stores/frontUser'
import { loadScript, loadScripts, whenJQuery, loadStyle } from '@/utils/loadScript'

const props = defineProps({
  /** 是否启用返回顶部（原 culture.html / detail.html 注释掉了该片段） */
  cdTop: { type: Boolean, default: true }
})

const user = useFrontUserStore()
let timer = null
let scrollHandler = null
let cdTopHandler = null

/** 返回顶部（等价原 main.js 的 700ms 平滑滚动） */
function scrollToTop() {
  const start = window.pageYOffset || document.documentElement.scrollTop
  const duration = 700
  const t0 = performance.now()
  const step = now => {
    const p = Math.min((now - t0) / duration, 1)
    // easeInOutQuad，贴近 jQuery animate 的观感
    const eased = p < 0.5 ? 2 * p * p : -1 + (4 - 2 * p) * p
    window.scrollTo(0, Math.round(start * (1 - eased)))
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

async function logout() {
  await user.logout()
  window.location.href = '/'
}

onMounted(async () => {
  // loader 动画（diaspora.js 会移除 #loader）
  loadStyle('/index/css/loader.css')
  loadScript('/index/js/diaspora.js').catch(() => {})

  // 导航栏滚动吸顶（原 common.html 内联脚本，逻辑一致）
  try {
    const $ = await whenJQuery()
    let lastScrollTop = 0
    scrollHandler = () => {
      const top = $(window).scrollTop()
      if (top > 0) {
        if (!$('.topbar').hasClass('topbarFixed')) {
          $('.topbar').addClass('topbarFixed').hide()
        }
      } else {
        $('.topbar').removeClass('topbarFixed')
      }
      if (top <= lastScrollTop) {
        $('.topbarFixed').show()
      } else {
        $('.topbarFixed').hide()
      }
      lastScrollTop = top
    }
    $(window).on('scroll', scrollHandler)
  } catch (e) { /* jQuery 未就绪时忽略滚动效果 */ }

  // 返回顶部
  if (props.cdTop) {
    loadStyle('/index/css/cd-top.css')
    // 原实现是 jquery.cdtop.min.js（内部打包了 jQuery 1.7.2）+ main.js，
    // 前者会把 window.jQuery 顶成 1.7.2，导致 bootstrap 的 .carousel() 等插件失效。
    // 这里用原生等价实现：滚动 >300px 显示、>1200px 半透明、点击 700ms 平滑回顶。
    cdTopHandler = () => {
      const btn = document.querySelector('.cd-top')
      if (!btn) return
      const top = window.pageYOffset || document.documentElement.scrollTop
      if (top > 300) btn.classList.add('cd-is-visible')
      else btn.classList.remove('cd-is-visible', 'cd-fade-out')
      if (top > 1200) btn.classList.add('cd-fade-out')
    }
    window.addEventListener('scroll', cdTopHandler, { passive: true })
    cdTopHandler()
  }

  // 运行时长（原 footer 内联脚本）
  const start = new Date('3/14/2021 05:20:00')
  const tick = () => {
    const el = document.getElementById('span_dt_dt')
    if (!el) return
    const days = Math.floor((Date.now() - start.getTime()) / 86400000)
    el.innerHTML = days + '&nbsp;day'
  }
  tick()
  timer = setInterval(tick, 1000)

  // 登录态：前台 Token 存在时取资料用于导航栏显示
  if (user.isLogin) user.fetchProfile()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (scrollHandler && window.jQuery) window.jQuery(window).off('scroll', scrollHandler)
  if (cdTopHandler) window.removeEventListener('scroll', cdTopHandler)
})
</script>
