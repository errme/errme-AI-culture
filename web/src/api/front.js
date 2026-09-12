import { frontHttp } from './http'

/** ============ 前台公开接口（无需登录） ============ */
export const getHome = () => frontHttp.get('/home')
export const getCultureList = params => frontHttp.get('/culture/list', { params })
export const getCultureDetail = id => frontHttp.get('/culture/detail', { params: { id } })
export const getCategorys = () => frontHttp.get('/culture/categorys')
export const getSentences = () => frontHttp.get('/sentence/list')

/** ============ 前台用户接口（前台 Token） ============ */
export const getCenter = () => frontHttp.get('/user/center')
export const likeCulture = id => frontHttp.post('/culture/like/' + id)
export const cancelLike = id => frontHttp.post('/culture/cancel/' + id)

/** 全站搜索（文化 + 句子），params: { keyword, page, pageSize, type } */
export const search = params => frontHttp.get('/search', { params })
export const searchHot = params => frontHttp.get('/search/hot', { params })
export const searchHistory = params => frontHttp.get('/search/history', { params })

/** ============ 标签（公开） ============ */
export const getTags = () => frontHttp.get('/tag/list')
export const getTagCultures = params => frontHttp.get('/tag/cultures', { params })

/** ============ 评论（公开：列表只返回已审核通过的） ============ */
export const getComments = cultureId => frontHttp.get('/comment/list', { params: { cultureId } })
export const submitComment = data => frontHttp.post('/comment/submit', data)

/**
 * 组合筛选版搜索（服务端筛选，新增，不影响上面的 search）。
 *
 * 后端 /api/search 现已支持（见 ApiSearchController#search）：
 *   · keyword、page、pageSize、type（all | culture | sentence）；
 *   · categoryId / tagId：只作用于**文化**结果（句子表没有这两个字段，被有意忽略）；
 *   · startTime / endTime：两侧都生效（文化用 created_at，句子同理）；
 *   时间接受 `yyyy-MM-dd`（按一整天理解）或 `yyyy-MM-dd HH:mm:ss`；
 *   非法值后端会降级为「不启用该筛选」，不会 400/500。
 *   pageSize 上限 50（后端 MAX_PAGE_SIZE），page 从 1 开始。
 *
 * 这里仍做一次「空值不发送」的白名单处理：空串/undefined 直接不发，
 * 一是让 URL 与请求干净，二是避免 ?categoryId= 这种空串走到后端再被忽略。
 */
export const searchFiltered = ({
  keyword,
  page = 1,
  pageSize = 10,
  type = 'all',
  categoryId,
  tagId,
  startTime,
  endTime
} = {}) => {
  const params = { keyword, page, pageSize, type }
  if (categoryId) params.categoryId = categoryId
  if (tagId) params.tagId = tagId
  if (startTime) params.startTime = startTime
  if (endTime) params.endTime = endTime
  return frontHttp.get('/search', { params })
}
