import { adminHttp } from './http'

/**
 * 后台管理接口（后台 Token）
 * 说明：后台页面全部走 REST，不再依赖 Thymeleaf 会话；
 *      上传富文本图片/视频见 api/http.js 的 uploadAdminFile()。
 */

/* ============ 通用 ============ */
export const adminProfile = () => adminHttp.get('/admin/me')
export const adminStats = () => adminHttp.get('/admin/stats')
export const adminMenus = () => adminHttp.get('/admin/menus')
export const adminRoles = () => adminHttp.get('/admin/roles')

/* ============ 文化管理 ============ */
export const cultureList = params => adminHttp.get('/admin/culture/list', { params })
export const cultureSave = data => adminHttp.post('/admin/culture/save', data)
export const cultureDelete = id => adminHttp.post('/admin/culture/delete', { id })

/* ============ 分类管理 ============ */
export const categoryList = params => adminHttp.get('/admin/category/list', { params })
export const categorySave = data => adminHttp.post('/admin/category/save', data)
export const categoryDelete = id => adminHttp.post('/admin/category/delete', { id })

/* ============ 公告管理 ============ */
export const announcementList = params => adminHttp.get('/admin/announcement/list', { params })
export const announcementSave = data => adminHttp.post('/admin/announcement/save', data)
export const announcementDelete = id => adminHttp.post('/admin/announcement/delete', { id })

/* ============ 句子管理 ============ */
export const sentenceList = params => adminHttp.get('/admin/sentence/list', { params })
export const sentenceSave = data => adminHttp.post('/admin/sentence/save', data)
export const sentenceDelete = id => adminHttp.post('/admin/sentence/delete', { id })

/* ============ 运维工具（Druid 监控 / API 文档 / 运行指标）============
 * 清单由后端按**当前生效的配置**输出（路径可配置，前端不写死），
 * 关闭的工具不会出现在返回结果里。详见后端 DevToolsController。 */
export const devTools = () => adminHttp.get('/admin/devtools')

/* ============ 系统设置（sys_config）============
 * 配置项清单、分组、类型、允许范围与说明文案全部由后端给出，
 * 前端不维护任何「key → 分组/标签」对照表（那也是写死）。
 * 保存后后端立即刷新缓存，改完即时生效、无需重启。 */
export const configList = () => adminHttp.get('/admin/config/list')
export const configSave = values => adminHttp.post('/admin/config/save', { values })
export const configReset = key => adminHttp.post('/admin/config/reset', { key })

/* ============ 用户管理 ============ */
export const userList = params => adminHttp.get('/admin/user/list', { params })
export const userSave = data => adminHttp.post('/admin/user/save', data)
export const userDelete = id => adminHttp.post('/admin/user/delete', { id })
export const userRoleSave = data => adminHttp.post('/admin/user/role', data)
export const userRoles = userId => adminHttp.get('/admin/user/roles', { params: { userId } })
/**
 * 导出用户 Excel。
 *
 * 路径由 `/user/downloadExcel` 迁到 `/api/admin/user/export`：
 * 旧路径不在 /api 前缀下，绕过了 JwtAuthFilter 的路径鉴权，任何前台登录用户都能下载全站用户表。
 * 现在纳入 /api/admin/** 统一鉴权，并且必须带 Token ——
 * 因此不能再像以前那样用 `window.location.href` 直接跳转（跳转带不上 Authorization 头），
 * 改为与「文化 CSV 导出」一致的 blob 下载。
 */
export const userExport = () => adminHttp.get('/admin/user/export', { responseType: 'blob' })

/* ============ 邮件设置 ============ */
export const mailConfig = () => adminHttp.get('/admin/mail/config')
export const mailConfigSave = data => adminHttp.post('/admin/mail/config', data)
export const mailAccounts = () => adminHttp.get('/admin/mail/accounts')
export const mailAccountsSave = data => adminHttp.post('/admin/mail/accounts', data)
export const mailAccountDelete = id => adminHttp.delete('/admin/mail/accounts/' + id)
export const mailStats = () => adminHttp.get('/admin/mail/stats')
export const mailLogs = () => adminHttp.get('/admin/mail/logs')
export const mailTest = data => adminHttp.post('/admin/mail/test', data)

/* ============ 标签管理 ============ */
/** 后台编辑回填专用详情（含完整 info 正文；不像前台 detail 那样累加浏览量） */
export const cultureAdminDetail = id => adminHttp.get('/admin/culture/detail', { params: { id } })

/* ===== 版本历史 / 定时发布（Batch6）===== */
export const cultureVersions = params => adminHttp.get('/admin/culture/versions', { params })
export const cultureVersion = id => adminHttp.get('/admin/culture/version', { params: { id } })
export const cultureRollback = versionId => adminHttp.post('/admin/culture/rollback', { versionId })
export const cultureBatchStatus = data => adminHttp.post('/admin/culture/batch-status', data)

/* ===== 敏感词（Batch6）===== */
export const sensitiveList = params => adminHttp.get('/admin/sensitive/list', { params })
export const sensitiveSave = data => adminHttp.post('/admin/sensitive/save', data)
export const sensitiveDelete = id => adminHttp.post('/admin/sensitive/delete', { id })
export const sensitiveBatchDelete = data => adminHttp.post('/admin/sensitive/batch-delete', data)
export const commentRecheck = data => adminHttp.post('/admin/comment/recheck', data)

/* ===== 标签合并 / 重命名（Batch6）===== */
export const tagMerge = data => adminHttp.post('/admin/tag/merge', data)
export const tagRename = data => adminHttp.post('/admin/tag/rename', data)

/* ===== 前端错误看板（Batch6）===== */
export const clientErrors = params => adminHttp.get('/admin/client-errors', { params })

/* ===== 回收站（Batch6）===== */
export const recycleCounts = () => adminHttp.get('/admin/recycle/counts')
export const recycleList = params => adminHttp.get('/admin/recycle/list', { params })
export const recyclePurge = data => adminHttp.post('/admin/recycle/purge', data)

/* ===== 角色管理与按钮级权限（Batch6）===== */
export const roleList = () => adminHttp.get('/admin/role/list')
export const roleSave = data => adminHttp.post('/admin/role/save', data)
export const roleDelete = id => adminHttp.post('/admin/role/delete', { id })
export const permissionButtons = roleId => adminHttp.get('/admin/permission/buttons', { params: { roleId } })
export const permissionButtonSave = data => adminHttp.post('/admin/permission/button', data)

/* ===== 菜单权限设置（Batch5）===== */
export const permissionMenus = () => adminHttp.get('/admin/permission/menus')
export const permissionRoles = () => adminHttp.get('/admin/permission/roles')
export const permissionSave = data => adminHttp.post('/admin/permission/role', data)

/* ===== 删除撤销（逻辑删除恢复，Batch5）===== */
const restore = (entity, data) => adminHttp.post('/admin/' + entity + '/restore', data)
export const cultureRestore = data => restore('culture', data)
export const categoryRestore = data => restore('category', data)
export const tagRestore = data => restore('tag', data)
export const announcementRestore = data => restore('announcement', data)
export const sentenceRestore = data => restore('sentence', data)
export const commentRestore = data => restore('comment', data)
export const userRestore = data => restore('user', data)

/* ===== 批量操作与导出（Batch4）===== */
export const commentBatchAudit = data => adminHttp.post('/admin/comment/batch-audit', data)
export const commentBatchDelete = data => adminHttp.post('/admin/comment/batch-delete', data)
export const cultureBatchDelete = data => adminHttp.post('/admin/culture/batch-delete', data)
export const cultureBatchCategory = data => adminHttp.post('/admin/culture/batch-category', data)
export const tagBatchDelete = data => adminHttp.post('/admin/tag/batch-delete', data)
/** 导出文化 CSV：需要带鉴权头，所以走 blob 下载而不是直接开链接 */
export const cultureExport = params => adminHttp.get('/admin/culture/export', { params, responseType: 'blob' })

export const tagList = () => adminHttp.get('/admin/tag/list')
export const tagSave = data => adminHttp.post('/admin/tag/save', data)
export const tagDelete = id => adminHttp.post('/admin/tag/delete', { id })

/* ============ 评论审核 ============ */
export const commentList = params => adminHttp.get('/admin/comment/list', { params })
export const commentPendingCount = () => adminHttp.get('/admin/comment/pending')
export const commentAudit = (id, status) => adminHttp.post('/admin/comment/audit', { id, status })
export const commentDelete = id => adminHttp.post('/admin/comment/delete', { id })

/* ============ 操作日志 ============ */
export const opLogList = params => adminHttp.get('/admin/log/list', { params })

/* ============ 文化-标签绑定 ============ */
export const tagOfCulture = cultureId => adminHttp.get('/admin/tag/culture', { params: { cultureId } })
export const tagBind = (cultureId, tagIds) => adminHttp.post('/admin/tag/bind', { cultureId, tagIds })
