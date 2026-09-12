/**
 * 老页面 jQuery 实例的加载与隔离。
 *
 * <h3>要解决的问题</h3>
 * 本项目的前台是「Vue 3 SPA + 原有老页面资源」的混合体：
 * <ul>
 *   <li>{@code front.html} 在 &lt;head&gt; 里全局加载了 <b>jQuery 3.4.1</b>，
 *       供 FrontLayout 等组件使用（例如导航栏滚动吸顶）；</li>
 *   <li>文化列表页 / 详情页又要复用老页面的插件，那些插件只兼容
 *       <b>jQuery 1.11.1</b>，于是页面运行时会再加载一份 1.11.1。</li>
 * </ul>
 *
 * <p>而 jQuery 是<b>全局单例</b>：后加载的 1.11.1 会直接覆盖 {@code window.jQuery} 与
 * {@code window.$}。若不还原，页面切走后整个 SPA 会话里的全局 jQuery 就永久变成了 1.11.1，
 * 后果是——</p>
 * <ul>
 *   <li>用 3.4.1 注册的事件监听器无法被解绑：
 *       jQuery 每个副本各自维护事件存储，用 1.11.1 的 {@code off()} 解绑 3.4.1 的
 *       {@code on()} 是无效操作，监听器会一直泄漏；</li>
 *   <li>已初始化的 owl / typed 等插件实例拿不到正确的实例，无法销毁。</li>
 * </ul>
 *
 * <h3>做法</h3>
 * 老脚本加载完成后<b>立即</b>调用 {@code noConflict(true)}：
 * jQuery 在自身加载时会记下此前的 {@code window.jQuery}，因此 noConflict(true) 的作用是
 * 「把当前（1.11.1）实例从全局摘下来返回给调用方，同时把全局还原成之前那份（3.4.1）」。
 *
 * <p>时机上是安全的：老插件（如 diaspora.js）在脚本执行的当下就完成了事件绑定，
 * 之后全局引用还原并不影响它们已经绑好的行为。</p>
 */

import { loadScripts } from '@/utils/loadScript'

/** 最近一次被隔离出来的老 jQuery 实例（供页面卸载时解绑使用） */
let detachedJq = null

/**
 * 把当前全局 jQuery 收回本地实例，并把 {@code window.jQuery} / {@code window.$}
 * 还原为加载它之前的版本。
 *
 * <p><b>务必注意</b>：只有当全局 jQuery 确实<b>被换过</b>时才能调用。
 * 如果当前全局仍是 front.html 的 3.4.1（例如老脚本已被 {@code loadScript} 按 src 去重
 * 而跳过执行），调用 noConflict 会把 3.4.1 从全局摘下来，反而破坏整个 SPA。
 * 用 {@link #loadLegacyJQuery} 可以自动处理这个判断。</p>
 *
 * @param {object} [previous] 加载前记录的全局 jQuery；传入时会做「是否真的换了」的校验
 * @returns {object|null} 隔离出来的 jQuery 实例
 */
export function detachLegacyJQuery(previous) {
  const jq = window.jQuery
  if (!jq || !jq.fn || typeof jq.noConflict !== 'function') {
    // 不是标准 jQuery（或已被别的代码换掉），不强行处理，避免制造更隐蔽的问题
    return jq || null
  }
  if (previous && jq === previous) {
    // 全局没变 → 本次没有真正加载新的 jQuery，绝不能动全局
    return jq
  }
  detachedJq = jq.noConflict(true)
  return detachedJq
}

/**
 * 按给定顺序加载老脚本，再把其中的 jQuery 隔离出来。
 *
 * <p>内部会先记下加载前的全局 jQuery，加载后只有在「确实换了实例」时才执行隔离，
 * 因此可以安全地重复调用（例如先从列表页再进详情页）。</p>
 *
 * @param {string[]} scripts 老脚本地址（顺序即原始 &lt;head&gt; 中的顺序）
 * @returns {Promise<object|null>} 隔离后的 jQuery 实例
 */
export async function loadLegacyJQuery(scripts) {
  const before = window.jQuery
  await loadScripts(scripts)
  return detachLegacyJQuery(before)
}

/** 取最近一次隔离出来的实例（可能为 null） */
export function getDetachedJQuery() {
  return detachedJq
}
