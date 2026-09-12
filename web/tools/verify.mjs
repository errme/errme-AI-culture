/**
 * 一键回归验证
 *
 * 依次运行所有端到端套件（全部基于无头 Chrome + CDP），汇总结果并在有失败时以非 0 退出，
 * 便于本地一键自检与 CI 使用。
 *
 * 前置：后端 8081 已启动；静态预览 8080 已启动（node web/tools/serve-dist.mjs）。
 *
 * 用法：
 *   node web/tools/verify.mjs                 # 默认 http://localhost:8080
 *   node web/tools/verify.mjs http://host:8080
 *   SKIP=click-seq,click-all-menus node web/tools/verify.mjs   # 跳过耗时套件
 */
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const TOOLS = path.dirname(fileURLToPath(import.meta.url))
const BASE = process.argv[2] || process.env.BASE_URL || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const SKIP = String(process.env.SKIP || '').split(',').map(s => s.trim()).filter(Boolean)
/**
 * 并发度：各套件互不依赖（每个都启动独立的 headless Chrome，CDP 端口也不同），可以并发跑。
 * 顺序跑一轮约 6 分钟，4 并发约 1.5~2 分钟。
 *   node tools/verify.mjs --parallel 4      # 全量并发
 *   node tools/verify.mjs --parallel 4 --fast   # 只跑最关键的 3 套（日常自查）
 */
const argv = process.argv.slice(2)
const PARALLEL = (() => {
  const i = argv.indexOf('--parallel')
  if (i === -1) return Number(process.env.VERIFY_PARALLEL || 0)
  const n = Number(argv[i + 1])
  return Number.isFinite(n) && n > 0 ? n : 4
})()
const FAST = argv.includes('--fast')

/** 套件清单：入口脚本 -> 通过判定的输出标记 */
const SUITES = [
  { name: 'check-entry-html', script: 'check-entry-html.mjs', args: [], noBase: true, expect: '入口 HTML 检查通过' },
  { name: 'e2e-check', script: 'e2e-check.mjs', expect: /=== 结果：(\d+)\/(\d+) 通过 ===/ },
  { name: 'ui-check', script: 'ui-check.mjs', expect: /提示与确认弹窗验证：(\d+)\/(\d+) 通过/ },
  { name: 'debug-menu', script: 'debug-menu.mjs', expect: /菜单验证：(\d+)\/(\d+) 通过/ },
  { name: 'click-check', script: 'click-check.mjs', expect: /真实点击验证：(\d+)\/(\d+) 通过/ },
  { name: 'click-all-menus', script: 'click-all-menus.mjs', expect: /菜单逐项点击：(\d+)\/(\d+) 通过/ },
  { name: 'click-seq', script: 'click-seq.mjs', expect: /连续点击：(\d+)\/(\d+) 通过/ },
  { name: 'delete-check', script: 'delete-check.mjs', expect: /高危操作（删除）验证：(\d+)\/(\d+) 通过/ },
  { name: 'feature-check', script: 'feature-check.mjs', expect: /第 7 批功能验证：(\d+)\/(\d+) 通过/ }
]

function run(suite) {
  return new Promise(resolve => {
    const args = [path.join(TOOLS, suite.script), ...(suite.noBase ? [] : [BASE])]
    const child = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let out = ''
    child.stdout.on('data', d => { out += d })
    child.stderr.on('data', d => { out += d })
    child.on('close', code => {
      const m = suite.expect ? out.match(suite.expect) : null
      const passed = m ? Number(m[1]) : null
      const total = m ? Number(m[2]) : null
      resolve({ ok: code === 0 && (!suite.expect || !!m), code, passed, total, out })
    })
  })
}

async function preflight() {
  try {
    const res = await fetch(BASE, { redirect: 'manual' })
    console.log(`[verify] 静态服务 OK  ${BASE}  (HTTP ${res.status})`)
  } catch (e) {
    console.error(`[verify] 无法访问 ${BASE}：${e.message}\n         请先运行 node web/tools/serve-dist.mjs`)
    process.exit(2)
  }
  try {
    const res = await fetch(API + '/api/culture/hot')
    console.log(`[verify] 后端服务 OK  ${API}  (HTTP ${res.status})`)
  } catch (e) {
    console.error(`[verify] 无法访问后端 ${API}：${e.message}\n         请先启动 Spring Boot（java -jar backend/target/*.jar）`)
    process.exit(2)
  }
}

await preflight()

const FAST_SET = ['e2e-check', 'feature-check', 'delete-check']
const queue = SUITES.filter(s => !SKIP.includes(s.name) && (!FAST || FAST_SET.includes(s.name)))
const results = []

async function runOne(suite) {
  const started = Date.now()
  const r = await run(suite)
  const secs = ((Date.now() - started) / 1000).toFixed(1)
  if (!r.ok) {
    console.error(`----- ${suite.name} 失败详情 -----`)
    console.error(r.out.split(String.fromCharCode(10)).filter(l => /FAIL|✗|错误|Error/.test(l)).slice(0, 20).join(String.fromCharCode(10)) || r.out.slice(-1500))
  }
  results.push({ name: suite.name, ...r, secs })
  console.log(`${r.ok ? '✅' : '❌'} ${suite.name}  ${r.passed != null ? `${r.passed}/${r.total} 通过` : ''}  (${secs}s)`)
}

if (PARALLEL > 0) {
  console.log(`[verify] 并发模式：最多 ${PARALLEL} 个套件同时跑（每个套件独立 Chrome + 独立 CDP 端口）`)
  const pending = queue.slice()
  await Promise.all(Array.from({ length: Math.min(PARALLEL, pending.length) }, async () => {
    while (pending.length) await runOne(pending.shift())
  }))
} else {
  for (const suite of queue) await runOne(suite)
}

console.log('\n================ 汇总 ================')
for (const r of results) {
  console.log(`${r.ok ? '✅' : '❌'} ${r.name.padEnd(18)} ${r.passed != null ? `${r.passed}/${r.total}` : ''}  ${r.secs}s`)
}
const failed = results.filter(r => !r.ok)
if (failed.length) {
  console.error(`\n${failed.length} 个套件未通过：${failed.map(f => f.name).join(', ')}`)
  process.exitCode = 1
} else {
  console.log('\n全部套件通过 ✅')
}
