import axios from 'axios'

/**
 * 统一 HTTP 层（前后端分离）
 *
 * 关键点：
 * 1) 前后台 Token 分离存放，互不覆盖：
 *      前台 culture_front_token / 后台 culture_admin_token
 * 2) 响应体统一解包：后端存在两种信封 ——
 *      /api/auth/*   -> { success, message, data }
 *      其余 /api/**  -> { code, message, data }
 *    这里统一解成 data，失败抛 Error(message)；文件上传接口 { errno, url } 原样返回。
 * 3) 401/403 自动清理对应 Token 并跳转对应登录页。
 */

export const FRONT_TOKEN_KEY = 'culture_front_token'
export const FRONT_EMAIL_KEY = 'culture_front_email'
export const ADMIN_TOKEN_KEY = 'culture_admin_token'
export const ADMIN_EMAIL_KEY = 'culture_admin_email'

/** 旧版本单 Token 键（已废弃，登录时清理，避免误导） */
const LEGACY_KEYS = ['token', 'email']

function readToken(key) {
  try { return localStorage.getItem(key) || '' } catch (e) { return '' }
}

function writeToken(key, value) {
  try { value ? localStorage.setItem(key, value) : localStorage.removeItem(key) } catch (e) { /* 隐私模式 */ }
}

/** Token 读写：kind = 'front' | 'admin' */
export const tokenStore = {
  get(kind) {
    return readToken(kind === 'admin' ? ADMIN_TOKEN_KEY : FRONT_TOKEN_KEY)
  },
  getAccount(kind) {
    return readToken(kind === 'admin' ? ADMIN_EMAIL_KEY : FRONT_EMAIL_KEY)
  },
  set(kind, token, account) {
    writeToken(kind === 'admin' ? ADMIN_TOKEN_KEY : FRONT_TOKEN_KEY, token)
    if (account) writeToken(kind === 'admin' ? ADMIN_EMAIL_KEY : FRONT_EMAIL_KEY, account)
    LEGACY_KEYS.forEach(k => writeToken(k, ''))
  },
  clear(kind) {
    writeToken(kind === 'admin' ? ADMIN_TOKEN_KEY : FRONT_TOKEN_KEY, '')
    writeToken(kind === 'admin' ? ADMIN_EMAIL_KEY : FRONT_EMAIL_KEY, '')
  }
}

/** 未授权时的跳转地址（可被路由层覆盖） */
const unauthorizedHandler = {
  front: () => { window.location.assign('/auth/login') },
  admin: () => { window.location.assign('/admin/login') }
}

export function setUnauthorizedHandler(kind, fn) {
  unauthorizedHandler[kind] = fn
}

/** 解包后端响应（两种信封 + 上传返回） */
function unwrap(payload) {
  if (!payload || typeof payload !== 'object') return payload
  if (typeof payload.code === 'number') {
    if (payload.code !== 200) throw new Error(payload.message || '请求失败')
    return payload.data
  }
  if (typeof payload.success === 'boolean') {
    if (!payload.success) throw new Error(payload.message || '请求失败')
    return payload.data
  }
  if (typeof payload.errno === 'number') {
    if (payload.errno !== 0) throw new Error(payload.message || '操作失败')
    return payload
  }
  return payload
}

function createHttp(kind) {
  const http = axios.create({ baseURL: '/api', timeout: 20000 })

  http.interceptors.request.use(config => {
    const token = tokenStore.get(kind)
    if (token) config.headers.Authorization = 'Bearer ' + token
    return config
  })

  http.interceptors.response.use(
    res => {
      // JWT 滑动续期：后端在剩余有效期不足一半时会通过响应头下发新 Token，这里静默替换，
      // 用户长时间在线不会再被突然踢到登录页（响应体结构不变，老逻辑无感）。
      const refreshed = res.headers && (res.headers['x-refreshed-token'] || res.headers['X-Refreshed-Token'])
      if (refreshed) {
        try { tokenStore.set(kind, refreshed) } catch (e) { /* 隐私模式忽略 */ }
      }
      return res.config && res.config.responseType === 'blob' ? res : unwrap(res.data)
    },
    err => {
      const status = err.response && err.response.status
      const body = (err.response && err.response.data) || {}
      if (status === 401 || status === 403) {
        // 凭证失效：清理该端 Token 并跳登录页
        tokenStore.clear(kind)
        if (status === 401) unauthorizedHandler[kind]()
        const message = body.message ||
          (status === 403 ? '没有权限执行该操作' : '登录已过期，请重新登录')
        return Promise.reject(new Error(message))
      }
      const message = body.message || err.message || '网络异常，请稍后重试'
      return Promise.reject(new Error(message))
    }
  )
  return http
}

/** 前台（用户端）请求实例 */
export const frontHttp = createHttp('front')
/** 后台（管理端）请求实例 */
export const adminHttp = createHttp('admin')

/**
 * 通用文件上传（自带后台 Token，供富文本编辑器/CultureEditor 使用）
 * @returns {Promise<{url:string}>}
 */
export function uploadAdminFile(file, type = 'image') {
  const fd = new FormData()
  fd.append('file', file)
  const token = tokenStore.get('admin')
  const path = type === 'video' ? '/file/uploadEditorVideo' : '/file/uploadEditorImage'
  return fetch(path, {
    method: 'POST',
    body: fd,
    credentials: 'same-origin',
    headers: token ? { Authorization: 'Bearer ' + token } : {}
  }).then(res =>
    res.text().then(text => {
      let data = null
      try { data = JSON.parse(text) } catch (e) { data = null }
      if (data && data.errno === 0 && data.url) return data
      const message = (data && data.message) ||
        (res.status === 413 ? '文件过大，请压缩后重试' : '上传失败（HTTP ' + res.status + '）')
      throw new Error(message)
    })
  )
}

export default frontHttp
