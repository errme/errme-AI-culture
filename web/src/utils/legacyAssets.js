/**
 * 后台复用的「老静态资源」路径常量
 *
 * 目的：这些库只被部分页面使用，全部塞进入口 HTML 的 <head> 会让每个页面都下载一遍
 * （jQuery/Bootstrap 全站需要，保留在 head；其余改为按路由/按需加载，见 router/admin.js
 *  的 meta.scripts / meta.styles 与 AdminLayout 的主题脚本加载）。
 */
/**
 * 会随项目迭代改动的老静态资源必须带版本号：
 * 这些文件没有内容 hash，而 Nginx/预览服对 /static/** 设了 1 天缓存，
 * 不带版本号的话用户会继续用旧文件（例如旧版富文本编辑器）。
 */
const V = '?v=2.1.0'

export const LEGACY = {
  /* 表单校验（新增/编辑弹窗） */
  validatorJs: '/static/admin/js/plugins/validator/js/bootstrapValidator.js',
  validatorCss: '/static/admin/js/plugins/validator/css/bootstrapValidator.css',
  /* 文件上传控件 */
  fileinputJs: '/static/admin/js/plugins/fileinput/js/fileinput.js',
  fileinputZhJs: '/static/admin/js/plugins/fileinput/js/locales/zh.js',
  fileinputCss: '/static/admin/js/plugins/fileinput/css/fileinput.css',
  /* 主题脚本依赖（侧边栏滚动条）与其本体 */
  perfectScrollbarJs: '/static/admin/js/perfect-scrollbar.min.js',
  mainThemeJs: '/static/admin/js/main.min.js',
  /* 富文本编辑器（文化管理按需加载） */
  quillJs: '/static/admin/js/vendor/quill/quill.js',
  quillCss: '/static/admin/js/vendor/quill/quill.snow.css',
  editorJs: '/static/admin/js/culture-editor.js' + V,
  editorCss: '/static/admin/css/culture-editor.css',
  serializeJs: '/static/admin/js/jquery.serialize-object.min.js'
}

/*
 * 已删除的死常量（原本导出但全仓无任何引用）：
 *   · chartJs   —— 后台首页已改为纯数据看板，不再加载 555KB 的 Chart.js
 *   · TABLE_CSS —— 表格页已全部迁到 Vue 渲染，bootstrap-table 插件与其样式整体移除
 * 对应的静态文件也已一并删除。
 */
