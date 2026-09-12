/**
 * 端到端验证（无头 Chrome + CDP，无第三方依赖）
 *
 * 覆盖：干净 URL、前台/后台登录真实提交、双 Token 落库、页面 Vue 渲染、
 *       后台表格（bootstrap-table）经 REST 取数、旧 .html 301。
 *
 * 前置：
 *   1) 后端已启动（默认 http://localhost:8081）
 *   2) 前端已构建并由 serve-dist（或 Nginx）托管（默认 http://localhost:8080）
 * 运行：node web/tools/e2e-check.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.BASE_URL || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const DEBUG_PORT = Number(process.env.CDP_PORT || 9333)
const ADMIN_ACCOUNT = process.env.ADMIN_ACCOUNT || 'admin@qq.com'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '123456'

const results = []

/**
 * 断言「所有样式表都在应用挂载之前加载完成」——这正是 FOUC（先显示无样式内容）的判定条件。
 * 页面脚本里 __appMountedAt 由入口在 mount 之后写入。
 */
const CSS_TIMING = `(() => {
  const mount = window.__appMountedAt || 0
  const css = performance.getEntriesByType('resource').filter(r => r.name.indexOf('.css') > -1)
  const late = css.filter(r => r.responseEnd > mount).map(r => r.name.split('/').pop())
  return { mount: Math.round(mount), total: css.length, late }
})()`
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? '  — ' + detail : ''}`)
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

/** 简单 CDP 客户端 */
class Cdp {
  constructor(ws) {
    this.ws = ws
    this.seq = 0
    this.pending = new Map()
    this.console = []
    this.errors = []
    ws.addEventListener('message', ev => {
      const msg = JSON.parse(ev.data)
      if (msg.method === 'Runtime.consoleAPICalled') {
        const text = (msg.params.args || []).map(a => a.value ?? a.description ?? a.type).join(' ')
        this.console.push(`[${msg.params.type}] ${text}`)
        if (msg.params.type === 'error' || msg.params.type === 'warning') this.errors.push(text)
      }
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails
        this.errors.push('EXCEPTION: ' + (d.exception?.description || d.text))
      }
      if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
        this.errors.push('LOG: ' + msg.params.entry.text + ' ' + (msg.params.entry.url || ''))
      }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
      }
    })
  }
  resetErrors() { this.errors = []; this.console = [] }
  send(method, params = {}) {
    const id = ++this.seq
    this.ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }))
  }
  async evaluate(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true
    })
    if (res.exceptionDetails) {
      const d = res.exceptionDetails
      throw new Error((d.exception && (d.exception.description || d.exception.value)) || d.text || 'JS 执行异常')
    }
    return res.result.value
  }
  /** 容忍「执行期间页面跳转」导致的 context 失效（登录提交后就是整页跳转） */
  async evaluateSafe(expression) {
    try {
      return await this.evaluate(expression)
    } catch (e) {
      if (/navigated or closed|Cannot find context|Execution context/.test(e.message)) return null
      throw e
    }
  }
  /** 填表并提交（提交会触发整页跳转，因此不等待返回） */
  async submitForm(selector, values) {
    const expr = `(() => {
      const set = (el, v) => { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })) }
      const form = document.querySelector('${selector}')
      ${Object.entries(values).map(([sel, v]) => `set(form.querySelector('${sel}'), ${JSON.stringify(v)})`).join('\n')}
      form.requestSubmit()
      return true
    })()`
    await this.evaluateSafe(expr)
  }
  async goto(url, waitMs = 1800) {
    await this.send('Page.navigate', { url })
    await sleep(waitMs)
  }
  /** 轮询等待表达式为真（页面数据/脚本就绪用，避免固定 sleep 造成的误判） */
  async waitFor(expression, timeoutMs = 12000, intervalMs = 250) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      const value = await this.evaluateSafe(expression)
      if (value) return true
      await sleep(intervalMs)
    }
    return false
  }
}

async function startChrome() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-e2e-'))
  const child = spawn(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${profile}`,
    'about:blank'
  ], { stdio: 'ignore' })

  for (let i = 0; i < 40; i++) {
    await sleep(300)
    try {
      const res = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)
      if (res.ok) return { child, profile, version: await res.json() }
    } catch (e) { /* 等 Chrome 起来 */ }
  }
  throw new Error('Chrome 调试端口未就绪')
}

async function newTarget() {
  const res = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/new?about:blank`, { method: 'PUT' })
  return res.json()
}

async function main() {
  console.log(`\n=== 端到端验证  BASE=${BASE}  API=${API} ===\n`)
  const { child, profile } = await startChrome()
  try {
    const target = await newTarget()
    const ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve)
      ws.addEventListener('error', reject)
    })
    const cdp = new Cdp(ws)
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')
    await cdp.send('Log.enable')

    // ---------- 1. 旧地址 301 ----------
    const legacy = await fetch(`${BASE}/static/auth/login.html`, { redirect: 'manual' })
    record('旧地址 /static/auth/login.html 301 到干净 URL',
      legacy.status === 301 && (legacy.headers.get('location') || '').endsWith('/auth/login'),
      `${legacy.status} -> ${legacy.headers.get('location')}`)

    // ---------- 2. 前台认证页渲染（含 orb 动画脚本） ----------
    await cdp.goto(`${BASE}/auth/login`)
    const loginPage = await cdp.evaluate(`(() => {
      const canvas = document.querySelector('#intro-canvas canvas')
      return { form: !!document.querySelector('#login-form'),
               orb: !!canvas,
               slogan: (document.querySelector('.auth-slogan') || {}).textContent || '' }
    })()`)
    record('前台登录页 Vue 渲染 + orb 背景动画初始化',
      loginPage.form && loginPage.orb, JSON.stringify(loginPage))

    // ---------- 3. 前台登录（页面内真实提交） ----------
    // 【为什么要重试】后端刚重启时第一次登录要付「JIT 预热 + Spring Security 建会话」的冷启动成本：
    // 实测 SlowSqlInterceptor 抓到过 UserMapper.findByEmail 单次 2441ms（SessionIdGenerator 又要 208ms），
    // 固定睡 3 秒就断言会把「冷启动慢」误判成「登录坏了」（曾出现过 43/47 的假红）。
    // 这里改成「提交 → 轮询等 token（最多 8 秒）」，仍拿不到再重试一次，并在详情里报告实际提交次数。
    let frontLogin = null
    let loginAttempts = 0
    for (; loginAttempts < 2; loginAttempts++) {
      await cdp.submitForm('#login-form', { '#email': ADMIN_ACCOUNT, '#password': ADMIN_PASSWORD })
      for (let waited = 0; waited < 8000; waited += 400) {
        await sleep(400)
        const hasToken = await cdp.evaluate(`!!localStorage.getItem('culture_front_token')`)
        if (hasToken) break
      }
      // token 一出现就取快照有可能正好卡在「跳转中」（#app 还没挂上），稍等一下让路由落定
      await sleep(800)
      frontLogin = await cdp.evaluate(`({
        token: localStorage.getItem('culture_front_token') ? 'yes' : 'no',
        adminToken: localStorage.getItem('culture_admin_token') ? 'yes' : 'no',
        url: location.pathname + location.search,
        rendered: !!(document.querySelector('#app') && document.querySelector('#app').children.length)
      })`)
      if (frontLogin.token === 'yes') break
    }
    const loginDetail = JSON.stringify(frontLogin) + `（提交 ${loginAttempts + 1} 次）`
    record('前台登录成功并只写前台 Token', frontLogin.token === 'yes' && frontLogin.adminToken === 'no',
      loginDetail)
    record('前台登录后跳转干净路径', frontLogin.url === '/' || frontLogin.url.startsWith('/?'), frontLogin.url)

    // ---------- 4. 前台站点页面渲染 ----------
    const frontPages = [
      ['/', ['tashuo', 'sentence', 'post']],
      ['/culture', ['proerty-item', 'pagination']],
      ['/culture/1', ['title', 'proerty-th']],
      ['/sentence', ['item', 'box']],
      ['/about', []]
    ]
    for (const [p, markers] of frontPages) {
      await cdp.goto(BASE + p, 800)
      const ready = markers.length
        ? await cdp.waitFor(`(${JSON.stringify(markers)}).every(m => document.querySelector('.' + m))`)
        : await cdp.waitFor(`!!document.querySelector('#app').children.length`)
      const info = await cdp.evaluate(`(() => ({
        hasVue: !!document.querySelector('#app').children.length,
        markers: ${JSON.stringify(markers)}.filter(m => document.querySelector('.' + m))
      }))()`)
      const errs = cdp.errors.slice(-2)
      record(`前台页面渲染 ${p}`, ready && info.hasVue,
        `命中=${info.markers.join(',') || '-'}` + (errs.length ? ' | 错误: ' + errs.join(' ; ').slice(0, 220) : ''))
      const css = await cdp.evaluate(CSS_TIMING)
      record(`前台 CSS 先于渲染就位（无 FOUC） ${p}`, css && css.late.length === 0,
        css ? `样式表=${css.total} 渲染后才到达=[${css.late.join(',')}]` : '无法读取')
      cdp.resetErrors()
    }

    // ---------- 5. 后台登录页 + 真实提交 ----------
    await cdp.goto(`${BASE}/admin/login`)
    const adminPageOk = await cdp.evaluate(`!!document.querySelector('#admin-login-form') && !!document.querySelector('.admin-badge')`)
    record('后台登录页渲染（管理后台标识）', adminPageOk)

    await cdp.submitForm('#admin-login-form', { '#account': ADMIN_ACCOUNT, '#password': ADMIN_PASSWORD })
    await sleep(3000)
    const adminLogin = await cdp.evaluate(`({
      token: localStorage.getItem('culture_admin_token') ? 'yes' : 'no',
      frontToken: localStorage.getItem('culture_front_token') ? 'yes' : 'no',
      url: location.pathname
    })`)
    record('后台登录写入后台 Token，且与前台 Token 各自独立共存',
      adminLogin.token === 'yes' && adminLogin.frontToken === 'yes',
      JSON.stringify(adminLogin))

    // ---------- 6. 后台各页面渲染（带 Token） ----------
    const adminPages = [
      ['/admin', ['coder-layout-sidebar', 'coder-layout-content']],
      ['/admin/culture', ['coder-layout-content']],
      ['/admin/category', ['coder-layout-content']],
      ['/admin/announcement', ['coder-layout-content']],
      ['/admin/sentence', ['coder-layout-content']],
      ['/admin/user', ['coder-layout-content']],
      ['/admin/mail', ['coder-layout-content']]
    ]
    for (const [p, markers] of adminPages) {
      await cdp.goto(BASE + p, 1200)
      const ready = await cdp.waitFor(
        `(${JSON.stringify(markers)}).every(m => document.querySelector('.' + m)) && !!document.querySelector('.coder-layout-content')`,
        15000)
      const info = await cdp.evaluate(`(() => ({
        markers: ${JSON.stringify(markers)}.filter(m => document.querySelector('.' + m)),
        tableRows: document.querySelectorAll('table tbody tr').length,
        placeholder: document.body.innerText.includes('页面建设中')
      }))()`)
      const cssA = await cdp.evaluate(CSS_TIMING)
      record(`后台 CSS 先于渲染就位（无 FOUC） ${p}`, cssA && cssA.late.length === 0,
        cssA ? `样式表=${cssA.total} 渲染后才到达=[${cssA.late.join(',')}]` : '无法读取')
      const aerrs = cdp.errors.slice(-3)
      record(`后台页面渲染 ${p}`, ready && !info.placeholder,
        `表格行=${info.tableRows}` + (info.placeholder ? ' (仍是占位组件!)' : '') +
        (aerrs.length ? ' | 错误: ' + aerrs.join(' ; ').slice(0, 300) : ''))
      cdp.resetErrors()
    }

    // ---------- 6.5 原生 Symbol 与旧插件加载顺序（回归守卫） ----------
    // 背景：bootstrap-table / quill 内置 core-js，会用 defineProperty 覆盖原生 Symbol。
    // 若在 Vue 模块初始化之前被覆盖，Vue 内部 Symbol.for('v-txt')（文本节点类型）会变成
    // 「假 Symbol 对象」，createVNode 因 isObject(type) 为真把文本节点误判成组件节点，
    // 路由切换卸载旧页面时抛「Cannot destructure property 'bum' of 'instance' as it is null」
    // 或 parentNode 空指针，并让之后所有菜单点击全部失效
    // （详见 admin.html 顶部说明与 tools/click-seq.mjs）。
    await cdp.goto(BASE + '/admin/culture', 1500)
    const symState = await cdp.evaluate(`(() => {
      const txt = (function () { try { return window.Symbol.for('v-txt') } catch (e) { return null } })()
      return {
        txtType: typeof txt,
        txtStr: txt ? String(txt) : null,
        jq: !!window.jQuery,
        tablePlugin: !!(window.jQuery && window.jQuery.fn && window.jQuery.fn.bootstrapTable),
        validatorPlugin: !!(window.jQuery && window.jQuery.fn && window.jQuery.fn.bootstrapValidator),
        confirmShim: !!(window.jQuery && typeof window.jQuery.confirm === 'function' && window.jQuery.confirm.__dshShim),
        guard: typeof window.__guardNativeSymbol
      }
    })()`)
    record('后台原生 Symbol 未被 core-js polyfill 覆盖（Text vnode 关键前提）',
      symState && symState.txtType === 'symbol' && symState.txtStr === 'Symbol(v-txt)',
      `Symbol.for('v-txt') = ${symState && symState.txtStr}（typeof=${symState && symState.txtType}，守卫=${symState && symState.guard}）`)
    record('后台旧插件在 Vue 初始化之后加载且均已就绪',
      symState && symState.tablePlugin && symState.validatorPlugin && symState.confirmShim,
      `jQuery=${symState && symState.jq} bootstrapTable=${symState && symState.tablePlugin} ` +
      `validator=${symState && symState.validatorPlugin} 确认弹窗兼容层=${symState && symState.confirmShim}`)

    // 打开新增弹窗会动态加载 quill.js（同样内置 core-js），确认加载后 Symbol 仍是原生
    // 等页面初始化完成（onMounted 末尾会挂 window.edit / window.CultureEditor），
    // 保证测的是「资源已就绪」的正常路径而不是加载竞态
    await cdp.waitFor(`!!window.__appMountedAt && !!window.edit && !!window.CultureEditor &&
      [...document.querySelectorAll('.coder-layout-content a, .coder-layout-content button')]
        .some(el => (el.textContent || '').includes('添加文化'))`, 15000)
    await cdp.evaluate(`(() => {
      const scope = document.querySelector('.coder-layout-content') || document
      const btn = [...scope.querySelectorAll('a,button')].find(el => (el.textContent || '').includes('添加文化'))
      if (btn) btn.click()
      return !!btn
    })()`)
    const afterQuill = await cdp.evaluate(`(() => {
      const txt = (function () { try { return window.Symbol.for('v-txt') } catch (e) { return null } })()
      return {
        txtType: typeof txt,
        quill: !!window.Quill,
        editor: !!(window.infoEditor && typeof window.infoEditor.getHTML === 'function'),
        editorHost: !!document.querySelector('#info-editor .ql-editor'),
        modalCount: document.querySelectorAll('#cultureAddModal').length,
        modalOpen: [...document.querySelectorAll('#cultureAddModal')].some(m => getComputedStyle(m).display !== 'none')
      }
    })()`)
    record('动态加载 quill.js（core-js）后 Symbol 仍为原生、富文本编辑器可用',
      afterQuill && afterQuill.txtType === 'symbol' && afterQuill.quill && afterQuill.editor && afterQuill.editorHost,
      `Symbol=${afterQuill && afterQuill.txtType} Quill=${afterQuill && afterQuill.quill} ` +
      `editor=${afterQuill && afterQuill.editor} 编辑器DOM=${afterQuill && afterQuill.editorHost} 弹窗=${afterQuill && afterQuill.modalOpen}` +
      (cdp.errors.length ? ' | 错误: ' + cdp.errors.slice(-2).join(' ; ').slice(0, 300) : ''))
    cdp.resetErrors()

    // ---------- 6.7 SEO / RSS / 缩略图（回归守卫） ----------
    const sm = await fetch(BASE + '/sitemap.xml')
    const smText = await sm.text()
    // 内容量大时 sitemap.xml 输出 sitemapindex（分片）；两种形态都合法，都要能跟进分片取到内容 URL
    const isIndex = /<sitemapindex[\s>]/.test(smText)
    let urls = (smText.match(/<loc>[^<]+<\/loc>/g) || []).map(m => m.replace(/<\/?loc>/g, ''))
    if (isIndex) {
      const shards = urls.slice()
      urls = []
      for (const shard of shards) {
        try {
          const u = new URL(shard)
          const shardXml = await fetch(BASE + u.pathname + u.search).then(r => r.text())
          urls.push(...(shardXml.match(/<loc>[^<]+<\/loc>/g) || []).map(m => m.replace(/<\/?loc>/g, '')))
        } catch (e) { /* 单个分片失败不影响断言 */ }
      }
    }
    record('sitemap 可访问、结构合法且不含后台地址',
      sm.status === 200 && (isIndex || /<urlset/.test(smText)) && !/\/admin/.test(smText) && urls.length > 0,
      `HTTP ${sm.status} ${isIndex ? '索引+分片' : 'urlset'} URL 数=${urls.length}`)

    const rb = await fetch(BASE + '/robots.txt')
    const rbText = await rb.text()
    record('robots.txt 可访问、禁止后台并声明 Sitemap',
      rb.status === 200 && /Disallow: \/admin/.test(rbText) && /Sitemap:/.test(rbText),
      `HTTP ${rb.status}`)

    const rss = await fetch(BASE + '/rss.xml')
    const rssText = await rss.text()
    record('rss.xml 可访问且含条目', rss.status === 200 && /<item>/.test(rssText),
      `HTTP ${rss.status} item 数=${(rssText.match(/<item>/g) || []).length}`)

    const metaRes = await fetch(API + '/api/seo/meta?path=/culture/32')
    const meta = await metaRes.json().catch(() => null)
    record('服务端 SEO meta 返回 canonical / og / JSON-LD',
      metaRes.status === 200 && meta && meta.data && meta.data.canonical && meta.data.ogImage && meta.data.jsonLd,
      meta && meta.data ? `title=${String(meta.data.title).slice(0, 20)} type=${meta.data.jsonLd && meta.data.jsonLd['@type']}` : '无数据')

    const listRes = await fetch(API + '/api/culture/list?page=1&pageSize=20')
    const list = await listRes.json().catch(() => null)
    const rows = ((list && list.data) || {}).rows || []
    const withCover = rows.find(r => r.fmUrl && r.coverOriginal)
    record('列表封面改用缩略图且保留原图字段',
      !!withCover && /_thumb\.jpg$/.test(withCover.fmUrl) && withCover.coverOriginal !== withCover.fmUrl,
      withCover ? `${withCover.fmUrl} ← 原图 ${withCover.coverOriginal}` : '没有带封面的数据可验证')
    if (withCover) {
      const thumb = await fetch(API + '/showFmImg/' + withCover.fmUrl)
      record('缩略图可通过 /showFmImg 访问', thumb.status === 200, `HTTP ${thumb.status}`)
    }

    await cdp.goto(BASE + '/culture/32', 2200)
    const headSeo = await cdp.evaluate(`JSON.stringify({
      og: !!document.querySelector('meta[property="og:image"]'),
      canonical: !!document.querySelector('link[rel="canonical"]'),
      ld: !!document.querySelector('script[type="application/ld+json"]'),
      title: document.title
    })`)
    const hs = JSON.parse(headSeo)
    record('文化详情页 head 注入 og:image / canonical / JSON-LD',
      hs.og && hs.canonical && hs.ld, `og=${hs.og} canonical=${hs.canonical} jsonLd=${hs.ld} title=${String(hs.title).slice(0, 24)}`)
    cdp.resetErrors()

    // ---------- 6.8 富文本增强（草稿自动保存 / 进度条 DOM / 版本） ----------
    // 注意：上一段 6.7 把页面导航到了前台详情页，这里必须先回到后台并打开新增弹窗
    await cdp.goto(BASE + '/admin/culture', 2500)
    await cdp.waitFor('!!window.CultureEditor && !!window.__appMountedAt', 15000)
    await cdp.waitFor(`[...document.querySelectorAll('.coder-layout-content a, .coder-layout-content button')]
      .some(el => (el.textContent || '').includes('添加文化'))`, 15000)
    await cdp.evaluate(`(() => {
      const scope = document.querySelector('.coder-layout-content') || document
      const btn = [...scope.querySelectorAll('a,button')].find(el => (el.textContent || '').includes('添加文化'))
      if (btn) btn.click()
      return !!btn
    })()`)
    await sleep(2500)

    const editorState = await cdp.evaluate(`(() => {
      const CE = window.CultureEditor
      const ed = window.infoEditor
      return {
        version: CE && CE.version,
        hasDraftApi: !!(CE && typeof CE.hasDraft === 'function' && typeof CE.loadDraft === 'function' && typeof CE.clearDraft === 'function'),
        draftKey: ed && ed.draftKey,
        progressDom: !!document.querySelector('#info-editor .ce-progress'),
        uploadTip: !!document.querySelector('#info-editor .ce-upload-tip'),
        saveNow: !!(ed && typeof ed.saveDraftNow === 'function')
      }
    })()`)
    record('富文本编辑器已升级（版本 2.1.0 + 草稿 API + 上传进度 DOM）',
      editorState.version === '2.1.0' && editorState.hasDraftApi && editorState.draftKey === 'culture-add' &&
      editorState.progressDom && editorState.saveNow,
      `version=${editorState.version} draftKey=${editorState.draftKey} 进度条=${editorState.progressDom} 草稿API=${editorState.hasDraftApi}`)

    const draftRoundTrip = await cdp.evaluate(`(() => {
      const CE = window.CultureEditor
      const ed = window.infoEditor
      if (!CE || !ed) return JSON.stringify({ has: false, html: '', afterClear: false })
      try { CE.clearDraft('culture-add') } catch (e) { /* 忽略 */ }
      ed.setHTML('<p>草稿自测内容</p>')
      ed.saveDraftNow()
      const d = CE.loadDraft('culture-add')
      const has = CE.hasDraft('culture-add')
      CE.clearDraft('culture-add')
      const afterClear = CE.loadDraft('culture-add')
      return JSON.stringify({ has, html: d && d.html, savedAt: !!(d && d.savedAt), afterClear: afterClear === null })
    })()`)
    const dr = JSON.parse(draftRoundTrip)
    record('草稿可自动落盘、读取并清除（localStorage）',
      dr.has && /草稿自测内容/.test(dr.html || '') && dr.savedAt && dr.afterClear,
      `hasDraft=${dr.has} afterClear=${dr.afterClear}`)
    // 收尾：把编辑器清空，避免影响后续断言
    await cdp.evaluate(`(() => { const ed = window.infoEditor; if (ed) ed.setHTML(''); return true })()`)
    cdp.resetErrors()

    // ---------- 6.9b 菜单权限入口与页面（管理员默认可见） ----------
    await cdp.goto(BASE + '/admin', 2500)
    const permMenuCheck = await cdp.evaluate(`(async () => {
      const token = localStorage.getItem('culture_admin_token')
      const d = await fetch('/api/admin/menus', { headers: { Authorization: 'Bearer ' + token } })
        .then(r => r.json()).catch(() => null)
      return JSON.stringify({ visible: JSON.stringify(d || {}).indexOf('菜单权限') > -1 })
    })()`)
    const pm = JSON.parse(permMenuCheck)
    await cdp.goto(BASE + '/admin/permission', 3000)
    const permRender = await cdp.evaluate(`JSON.stringify({
      roles: document.querySelectorAll('.perm-role').length,
      groups: document.querySelectorAll('.perm-group').length,
      items: document.querySelectorAll('.perm-item').length
    })`)
    const pr = JSON.parse(permRender)
    record('后台菜单含「菜单权限」且页面可直接访问',
      pm.visible && pr.roles > 0 && pr.groups > 0 && pr.items > 0,
      `菜单可见=${pm.visible} 角色=${pr.roles} 目录=${pr.groups} 菜单项=${pr.items}`)
    cdp.resetErrors()

    // ---------- 6.10 后台编辑弹窗正文回填（防止「保存把正文清空」） ----------
    // 列表接口已瘦身（不再下发 info 正文），编辑弹窗必须单独取后台详情；
    // 若回填失败，原来的实现会拿空正文保存 → 真数据丢失。这里断言回填内容与接口一致。
    await cdp.goto(BASE + '/admin/culture', 3000)
    await cdp.waitFor('!!window.edit && !!window.CultureEditor', 15000)
    const editProbe = await cdp.evaluate(`(async () => {
      // 注意：本段会被当作 JS 源码注入页面，模板字符串里不要出现带反斜杠的正则
      // （反斜杠会被模板字符串吞掉，导致注入的正则语法错误）。
      //
      // 【为什么改用后台列表接口找目标行】以前是从行内 onclick="edit(123)" 里抠 id，
      // 但后台表格已从 bootstrap-table 迁到 Vue 渲染（@click 绑定，DOM 上不再有 onclick），
      // 抠不到 id 会整条用例被 SKIP。改为直接查 /api/admin/culture/list（列表已瘦身、
      // 只带 infoSummary），挑一条「摘要有内容」的记录，再用同一个 id 取 detail 比对编辑器回填。
      const token = localStorage.getItem('culture_admin_token')
      const stripTags = s => String(s || '').replace(/<[^>]*>/g, ' ').split('&nbsp;').join(' ').split(' ').join('')
      const list = await fetch('/api/admin/culture/list?page=1&pageSize=50', {
        headers: { Authorization: 'Bearer ' + token }
      }).then(r => r.json()).catch(() => null)
      const candidates = (list && list.data && (list.data.rows || list.data.list)) || []
      let targetId = null
      let apiInfo = ''
      for (const row of candidates) {
        const id = row && row.id
        if (!id) continue
        const summary = row.infoSummary || row.info || ''
        if (stripTags(summary).length < 20) continue
        const d = await fetch('/api/admin/culture/detail?id=' + id, { headers: { Authorization: 'Bearer ' + token } })
          .then(r => r.json()).catch(() => null)
        const info = d && d.data && d.data.info
        if (info && stripTags(info).length > 20) { targetId = String(id); apiInfo = info; break }
      }
      if (!targetId) return JSON.stringify({ skipped: true })
      window.edit(targetId)
      await new Promise(r => setTimeout(r, 2600))
      const editorHtml = (window.editEditor && window.editEditor.getHTML) ? window.editEditor.getHTML() : ''
      const apiText = stripTags(apiInfo)
      const edText = stripTags(editorHtml)
      const closeBtn = document.querySelector('#cultureEditModal .close, #cultureEditModal [data-dismiss="modal"]')
      if (closeBtn) closeBtn.click()
      return JSON.stringify({
        id: targetId,
        apiLen: apiText.length,
        editorLen: edText.length,
        matched: apiText.length > 0 && edText.length > 0 &&
          (edText.indexOf(apiText.slice(0, 40)) > -1 || apiText.indexOf(edText.slice(0, 40)) > -1)
      })
    })()`)
    const ep = JSON.parse(editProbe)
    if (ep.skipped) {
      console.log('SKIP 编辑回填正文：库里没有带正文的文化可供验证')
    } else {
      record('后台编辑弹窗正文回填正确（不会把正文保存成空）',
        ep.matched && ep.editorLen > 0,
        `文化#${ep.id} 接口正文=${ep.apiLen} 字，编辑器=${ep.editorLen} 字`)
    }
    cdp.resetErrors()

    // ---------- 6.9 健康检查与前端错误上报端点 ----------
    const health = await fetch(API + '/api/health')
    const healthBody = await health.json().catch(() => null)
    record('GET /api/health 返回依赖状态（免登录、不 500）',
      health.status === 200 && healthBody && healthBody.data && typeof healthBody.data.db === 'boolean',
      healthBody && healthBody.data ? `status=${healthBody.data.status} db=${healthBody.data.db} redis=${healthBody.data.redis}` : `HTTP ${health.status}`)

    const clientErr = await fetch(API + '/api/client-error', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'e2e 自测上报', url: BASE + '/e2e', line: 1, col: 1 })
    })
    record('POST /api/client-error 永远 200（上报失败不影响页面）', clientErr.status === 200, `HTTP ${clientErr.status}`)

    // ---------- 7. 双 Token 隔离（前台 Token 打后台接口） ----------
    // 注意：这个判定必须在「确实拿到了前台 Token」的前提下才有意义——否则退化成
    // 「拿 Authorization: Bearer null 去请求」，状态码取决于会话兜底，会给出误导性的 200/403。
    const isolation = await cdp.evaluate(`(async () => {
      const front = localStorage.getItem('culture_front_token')
      const res = await fetch('/api/admin/me', { headers: { Authorization: 'Bearer ' + front } })
      return { status: res.status, hasToken: !!front }
    })()`)
    record('前台 Token 访问 /api/admin/me 被拒绝（403）',
      isolation.hasToken && isolation.status === 403,
      `HTTP ${isolation.status}（前台 Token ${isolation.hasToken ? '存在' : '缺失'}）`)

    fs.writeFileSync(DEBUG_PORT + '-e2e.json', JSON.stringify(results, null, 2))
  } finally {
    child.kill()
    try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  }

  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 结果：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) {
    console.log('失败项：')
    failed.forEach(f => console.log(' - ' + f.name + ' ' + f.detail))
    process.exitCode = 1
  }
}

main().catch(err => {
  console.error('验证脚本异常：', err)
  process.exitCode = 2
})
