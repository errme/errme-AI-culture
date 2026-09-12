import { frontHttp, adminHttp, tokenStore } from './http'

/**
 * 认证接口：前台与后台登录完全分离
 *  - 前台：POST /auth/login        -> 前台 Token（scope=front）
 *  - 后台：POST /auth/admin/login  -> 后台 Token（scope=admin，仅管理员）
 */

/* ============ 通用（注册/验证码/找回密码） ============ */
export const sendCode = data => frontHttp.post('/auth/send-code', data)
export const register = data => frontHttp.post('/auth/register', data)
export const resetPassword = data => frontHttp.post('/auth/reset', data)

/* ============ 前台（用户端） ============ */
export async function frontLogin({ account, password }) {
  const data = await frontHttp.post('/auth/login', { email: account, password })
  tokenStore.set('front', data.token, data.email || account)
  return data
}
export const frontMe = () => frontHttp.get('/auth/me')
export async function frontLogout() {
  try { await frontHttp.post('/auth/logout') } catch (e) { /* 忽略网络异常 */ }
  tokenStore.clear('front')
}

/* ============ 后台（管理端） ============ */
export async function adminLogin({ account, password }) {
  const data = await adminHttp.post('/auth/admin/login', { email: account, password })
  tokenStore.set('admin', data.token, data.email || account)
  return data
}
export const adminMe = () => adminHttp.get('/auth/admin/me')
export async function adminLogout() {
  try { await adminHttp.post('/auth/admin/logout') } catch (e) { /* 忽略网络异常 */ }
  tokenStore.clear('admin')
}
