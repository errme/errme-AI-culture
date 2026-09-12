/**
 * CultureEditor —— 文化正文富文本编辑器（基于本地 Quill 2 封装，无 135 / 秀米 依赖）
 *
 * 功能：
 *   - 重新设计的中文工具栏：标题 / 字号 / 加粗 / 斜体 / 下划线 / 删除线 / 字体色 / 背景色 /
 *     有序无序列表 / 缩进 / 对齐 / 引用 / 代码块 / 链接 / 图片 / 视频 / 分割线 / 表格 /
 *     清除格式 / 撤销重做 / 字数统计 / HTML 源码 / 全屏
 *   - 图片上传：POST /file/uploadEditorImage -> 存 <项目根>/upload/media/image/yyyyMM/
 *   - 视频上传：POST /file/uploadEditorVideo -> 存 <项目根>/upload/media/video/yyyyMM/
 *     （本地视频用自定义 blot 输出 <video controls>；外链视频可用 iframe 嵌入）
 *   - 支持粘贴 / 拖拽图片视频直接上传，自动携带后台 Token
 *   - 粘贴图片自动上传：一次可粘贴多张；纯文本 / 富文本粘贴仍走 Quill 默认行为
 *   - 上传进度：容器顶部 3px 进度条 + 工具栏右侧百分比标签，多文件显示总进度
 *   - 草稿自动保存：create 时传 options.draftKey 启用
 *     （localStorage，key = "ce_draft:" + draftKey，值 = {"html": "...", "savedAt": 时间戳}）
 *
 * 用法：
 *   var editor = CultureEditor.create('#info-editor', {
 *     name: 'infoEditor', height: 420, placeholder: '请输入正文', draftKey: 'culture-add'
 *   });
 *   editor.setHTML(html); editor.getHTML(); editor.count(); editor.saveDraftNow();
 *   CultureEditor.get('infoEditor');          // 按名字取实例（create 时传 name）
 *   CultureEditor.hasDraft('culture-add');    // 是否存在可恢复草稿
 *   CultureEditor.loadDraft('culture-add');   // -> { html, savedAt } 或 null
 *   CultureEditor.clearDraft('culture-add');  // 保存成功 / 用户放弃草稿后清理
 *
 * 依赖：quill.snow.css + quill.js + culture-editor.css（无 jQuery 依赖）
 */
var CultureEditor = (function () {
  'use strict';

  var instances = {};
  var blotsReady = false;
  var VERSION = '2.1.0';

  var DRAFT_PREFIX = 'ce_draft:';
  var VIDEO_EXT = /\.(mp4|webm|ogg|ogv|mov|m4v)(\?.*)?$/i;

  /* ==================== 图标（内联 SVG，沿用 Quill 的 ql-stroke 配色） ==================== */
  var ICON = {
    divider: '<svg viewBox="0 0 18 18"><line class="ql-stroke" x1="2" y1="9" x2="16" y2="9"/></svg>',
    source: '<svg viewBox="0 0 18 18"><polyline class="ql-stroke" points="6 5 3 9 6 13"/><polyline class="ql-stroke" points="12 5 15 9 12 13"/><line class="ql-stroke" x1="10.5" y1="4" x2="7.5" y2="14"/></svg>',
    fullscreen: '<svg viewBox="0 0 18 18"><polyline class="ql-stroke" points="6.5 3 3 3 3 6.5"/><polyline class="ql-stroke" points="11.5 3 15 3 15 6.5"/><polyline class="ql-stroke" points="6.5 15 3 15 3 11.5"/><polyline class="ql-stroke" points="11.5 15 15 15 15 11.5"/></svg>',
    undo: '<svg viewBox="0 0 18 18"><polyline class="ql-stroke" points="7 4.5 3 8.5 7 12.5"/><path class="ql-stroke" d="M3 8.5h7.5a4 4 0 0 1 0 8H8"/></svg>',
    redo: '<svg viewBox="0 0 18 18"><polyline class="ql-stroke" points="11 4.5 15 8.5 11 12.5"/><path class="ql-stroke" d="M15 8.5H7.5a4 4 0 0 0 0 8H10"/></svg>'
  };

  /* ==================== 统一错误提示 ==================== */
  /** 优先使用主控注入的顶部 toast（window.__dshNotify），不可用时退回原生 alert */
  function notifyError(message) {
    var msg = (message === null || message === undefined || message === '') ? '操作失败' : String(message);
    try {
      var n = window.__dshNotify;
      if (n && n.toast && typeof n.toast.error === 'function') {
        n.toast.error(msg);
        return;
      }
    } catch (e) { /* 通知组件异常时静默退回 alert */ }
    alert(msg);
  }

  /* ==================== 上传去重（同一文件上传期间禁止重复提交） ==================== */
  var inflightFiles = {};

  /** 文件指纹：同名同大小同修改时间视为同一文件 */
  function fileKey(file) {
    if (!file) return '';
    return [file.name || 'blob', file.size || 0, file.lastModified || 0, file.type || ''].join('|');
  }

  /** 认领文件：返回指纹；若该文件已在上传中则返回 null（调用方应跳过） */
  function claimFile(file) {
    var key = fileKey(file);
    if (!key) return '';
    if (inflightFiles[key]) return null;
    inflightFiles[key] = Date.now();
    return key;
  }

  function releaseFile(key) {
    if (key) delete inflightFiles[key];
  }

  /* ==================== 草稿存储（localStorage，隐私模式 / 超限静默降级） ==================== */

  function draftStorageKey(draftKey) {
    return DRAFT_PREFIX + String(draftKey === null || draftKey === undefined ? '' : draftKey);
  }

  /** HTML 是否为空内容（<p><br></p>、纯 &nbsp; 视为空；含图片/视频/表格等视为非空） */
  function isBlankHtml(html) {
    var v = String(html === null || html === undefined ? '' : html);
    if (/<(img|video|iframe|hr|table|audio|embed)\b/i.test(v)) return false;
    return v.replace(/<[^>]*>/g, '').replace(/&nbsp;/gi, ' ').replace(/\s+/g, '') === '';
  }

  /** 读原始草稿记录；结构不对 / 隐私模式 / JSON 损坏都返回 null */
  function readDraft(draftKey) {
    if (!draftKey) return null;
    try {
      var raw = window.localStorage.getItem(draftStorageKey(draftKey));
      if (!raw) return null;
      var data = JSON.parse(raw);
      if (!data || typeof data !== 'object' || typeof data.html !== 'string') return null;
      return {
        html: data.html,
        savedAt: typeof data.savedAt === 'number' ? data.savedAt : 0
      };
    } catch (e) {
      return null;
    }
  }

  /** 写入草稿：{"html": "...", "savedAt": 毫秒时间戳} */
  function writeDraft(draftKey, html) {
    if (!draftKey) return false;
    try {
      window.localStorage.setItem(draftStorageKey(draftKey), JSON.stringify({
        html: String(html === null || html === undefined ? '' : html),
        savedAt: Date.now()
      }));
      return true;
    } catch (e) {
      // 隐私模式 / 超出配额：不影响编辑，仅告警
      if (window.console) {
        console.warn('[CultureEditor] 草稿保存失败（隐私模式或存储已满）：' + ((e && e.message) || e));
      }
      return false;
    }
  }

  /** 是否存在「有内容」的草稿（空内容草稿不算，避免页面弹出无意义的恢复询问） */
  function hasDraft(draftKey) {
    var d = readDraft(draftKey);
    return !!(d && !isBlankHtml(d.html));
  }

  /** 读取草稿：返回 { html, savedAt }；无草稿或草稿为空返回 null */
  function loadDraft(draftKey) {
    var d = readDraft(draftKey);
    if (!d || isBlankHtml(d.html)) return null;
    return { html: d.html, savedAt: d.savedAt };
  }

  /** 彻底删除草稿 */
  function clearDraft(draftKey) {
    if (!draftKey) return false;
    try {
      window.localStorage.removeItem(draftStorageKey(draftKey));
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ==================== 自定义 blot：本地视频 / 分割线 ==================== */
  function ensureBlots() {
    if (blotsReady || !window.Quill) return;
    try {
      var Parchment = Quill.import('parchment');
      var BlockEmbed = Quill.import('blots/block/embed');

      class CultureVideoBlot extends BlockEmbed {
        static create(value) {
          var url = (value && typeof value === 'object') ? value.url : value;
          var node = document.createElement('video');
          if (url) node.setAttribute('src', url);
          node.setAttribute('controls', 'controls');
          node.setAttribute('preload', 'metadata');
          node.setAttribute('playsinline', 'true');
          node.setAttribute('contenteditable', 'false');
          node.setAttribute('class', 'ce-video');
          return node;
        }
        static value(node) { return node.getAttribute('src') || ''; }
        static formats() { return {}; }
      }
      CultureVideoBlot.blotName = 'ceVideo';
      CultureVideoBlot.tagName = 'VIDEO';
      CultureVideoBlot.className = 'ce-video';
      CultureVideoBlot.scope = Parchment.Scope.BLOCK_BLOT;

      class DividerBlot extends BlockEmbed {
        static create() {
          var node = document.createElement('hr');
          node.setAttribute('contenteditable', 'false');
          node.setAttribute('class', 'ce-divider-node');
          return node;
        }
        static value() { return true; }
      }
      DividerBlot.blotName = 'ceDivider';
      DividerBlot.tagName = 'HR';
      DividerBlot.className = 'ce-divider-node';
      DividerBlot.scope = Parchment.Scope.BLOCK_BLOT;

      Quill.register(CultureVideoBlot, true);
      Quill.register(DividerBlot, true);
      blotsReady = true;
    } catch (e) {
      // 注册失败不阻塞编辑器：视频/分割线按钮会给出提示
      if (window.console) console.warn('[CultureEditor] 自定义格式注册失败：' + e.message);
    }
  }

  /* ==================== 工具栏 ==================== */
  function toolbarHtml() {
    return '' +
      '<span class="ce-group">' +
        '<select class="ql-header" title="标题级别">' +
          '<option value="1">标题1</option>' +
          '<option value="2">标题2</option>' +
          '<option value="3">标题3</option>' +
          '<option selected>正文</option>' +
        '</select>' +
        '<select class="ql-size" title="字号">' +
          '<option value="small">小</option>' +
          '<option selected>正常</option>' +
          '<option value="large">大</option>' +
          '<option value="huge">特大</option>' +
        '</select>' +
      '</span>' +
      '<span class="ce-group">' +
        '<button type="button" class="ql-bold" title="加粗"></button>' +
        '<button type="button" class="ql-italic" title="斜体"></button>' +
        '<button type="button" class="ql-underline" title="下划线"></button>' +
        '<button type="button" class="ql-strike" title="删除线"></button>' +
        '<select class="ql-color" title="字体颜色"></select>' +
        '<select class="ql-background" title="背景颜色"></select>' +
      '</span>' +
      '<span class="ce-group">' +
        '<button type="button" class="ql-list" value="ordered" title="有序列表"></button>' +
        '<button type="button" class="ql-list" value="bullet" title="无序列表"></button>' +
        '<button type="button" class="ql-indent" value="-1" title="减少缩进"></button>' +
        '<button type="button" class="ql-indent" value="+1" title="增加缩进"></button>' +
        '<select class="ql-align" title="对齐方式"></select>' +
      '</span>' +
      '<span class="ce-group">' +
        '<button type="button" class="ql-blockquote" title="引用"></button>' +
        '<button type="button" class="ql-code-block" title="代码块"></button>' +
        '<button type="button" class="ql-link" title="插入链接"></button>' +
        '<button type="button" class="ql-image" title="插入图片"></button>' +
        '<button type="button" class="ql-video" title="插入视频"></button>' +
        '<button type="button" class="ce-btn ce-divider" title="插入分割线">' + ICON.divider + '</button>' +
        '<span class="ce-dropdown">' +
          '<button type="button" class="ce-btn ce-table-btn" title="表格操作">表格</button>' +
          '<span class="ce-menu" hidden>' +
            '<a data-op="insert-3x3">插入 3×3 表格</a>' +
            '<a data-op="insert-custom">插入自定义表格…</a>' +
            '<a data-op="row-above">上方插入行</a>' +
            '<a data-op="row-below">下方插入行</a>' +
            '<a data-op="col-left">左侧插入列</a>' +
            '<a data-op="col-right">右侧插入列</a>' +
            '<a data-op="row-del">删除当前行</a>' +
            '<a data-op="col-del">删除当前列</a>' +
            '<a data-op="table-del">删除表格</a>' +
          '</span>' +
        '</span>' +
      '</span>' +
      '<span class="ce-group ce-group--right">' +
        '<button type="button" class="ce-btn ce-undo" title="撤销">' + ICON.undo + '</button>' +
        '<button type="button" class="ce-btn ce-redo" title="重做">' + ICON.redo + '</button>' +
        '<button type="button" class="ql-clean" title="清除格式"></button>' +
        '<span class="ce-count" title="正文字数">0 字</span>' +
        '<button type="button" class="ce-btn ce-source" title="HTML 源码">' + ICON.source + '</button>' +
        '<button type="button" class="ce-btn ce-fullscreen" title="全屏编辑">' + ICON.fullscreen + '</button>' +
      '</span>';
  }

  /* ==================== 工具方法 ==================== */
  function $(selector, scope) { return (scope || document).querySelector(selector); }

  /**
   * 上传单个文件到后端，返回可访问 URL 的 Promise（含明确的失败原因）。
   * 用 XMLHttpRequest 实现以便通过 upload.onprogress 上报进度；
   * 请求地址、请求头、字段名（file）与响应解析（errno / url / message）与旧版完全一致。
   * @param {File} file 待上传文件
   * @param {string} kind 'image' | 'video'
   * @param {function} [onProgress] (loaded, total, lengthComputable) 进度回调
   */
  function upload(file, kind, onProgress) {
    return new Promise(function (resolve, reject) {
      var fd = new FormData();
      fd.append('file', file);
      var token = null;
      try {
        // 后台页面依赖 Session，另附后台 Token 兜底（Session 过期时依旧可上传）
        token = localStorage.getItem('culture_admin_token');
      } catch (e) { /* 隐私模式忽略 */ }
      var url = kind === 'video' ? '/file/uploadEditorVideo' : '/file/uploadEditorImage';

      var xhr;
      try {
        xhr = new XMLHttpRequest();
      } catch (e) {
        reject(new Error('当前浏览器不支持上传'));
        return;
      }

      var settled = false;
      function settle(fn, value) {
        if (settled) return;
        settled = true;
        fn(value);
      }

      // 与旧版 fetch credentials:'same-origin' 等价：同源请求自动带 Cookie，跨源不带凭据
      xhr.open('POST', url, true);
      try {
        if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
      } catch (e) { /* 忽略 */ }

      if (xhr.upload && typeof onProgress === 'function') {
        xhr.upload.onprogress = function (evt) {
          try {
            onProgress(evt.loaded || 0, evt.total || 0, !!evt.lengthComputable);
          } catch (e) { /* 进度回调异常不影响上传 */ }
        };
      }

      xhr.onload = function () {
        var data = null;
        try { data = JSON.parse(xhr.responseText || ''); } catch (e) { data = null; }
        if (data && data.errno === 0 && data.url) { settle(resolve, data.url); return; }
        var status = xhr.status;
        if (status === 413 || (data && /过大/.test(data.message || ''))) {
          settle(reject, new Error(kind === 'video'
            ? '视频过大：单个视频请不超过 200MB'
            : '图片过大：单张图片请不超过 10MB'));
        } else if (status === 401 || status === 403) {
          settle(reject, new Error('没有上传权限，请重新登录后台后再试'));
        } else {
          settle(reject, new Error((data && data.message) || ('上传失败（HTTP ' + status + '）')));
        }
      };
      xhr.onerror = function () {
        settle(reject, new Error('上传失败：请确认后端已启动且已登录后台'));
      };
      xhr.ontimeout = function () {
        settle(reject, new Error('上传超时，请稍后重试'));
      };
      xhr.onabort = function () {
        settle(reject, new Error('上传已取消'));
      };

      try {
        xhr.send(fd);
      } catch (e) {
        settle(reject, new Error('上传失败：' + ((e && e.message) || e)));
      }
    });
  }

  /** 依据 MIME 与扩展名判定媒体类型（部分浏览器 file.type 为空） */
  function kindOf(file) {
    var type = (file && file.type) || '';
    if (/^image\//.test(type)) return 'image';
    if (/^video\//.test(type)) return 'video';
    var name = ((file && file.name) || '').toLowerCase();
    if (/\.(jpg|jpeg|png|gif|webp|bmp)$/.test(name)) return 'image';
    if (/\.(mp4|webm|ogg|ogv|mov|m4v)$/.test(name)) return 'video';
    return '';
  }

  /** 选择本地文件 */
  function pickFiles(accept, multiple, onPick) {
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    if (multiple) input.multiple = true;
    input.style.display = 'none';
    input.addEventListener('change', function () {
      if (input.files && input.files.length) onPick(Array.prototype.slice.call(input.files));
      document.body.removeChild(input);
    });
    document.body.appendChild(input);
    input.click();
  }

  /* ==================== 创建编辑器 ==================== */
  function create(target, options) {
    options = options || {};
    ensureBlots();

    var root = typeof target === 'string' ? $(target) : target;
    if (!root) throw new Error('CultureEditor: 找不到容器 ' + target);
    if (options.name && instances[options.name]) return instances[options.name];
    if (!window.Quill) throw new Error('CultureEditor: 未加载 quill.js');

    root.classList.add('ce-editor');
    root.innerHTML = '';

    var toolbar = document.createElement('div');
    toolbar.className = 'ce-toolbar';
    toolbar.innerHTML = toolbarHtml();
    // 抬到上传遮罩（z-index:20）之上，保证上传百分比标签仍然可见（纯内联样式，不动 CSS 文件）
    toolbar.style.position = 'relative';
    toolbar.style.zIndex = '25';

    var input = document.createElement('div');
    input.className = 'ce-input';
    input.style.height = (options.height || 420) + 'px';

    var sourceBox = document.createElement('textarea');
    sourceBox.className = 'ce-source-box';
    sourceBox.spellcheck = false;
    sourceBox.hidden = true;

    var overlay = document.createElement('div');
    overlay.className = 'ce-uploading';
    overlay.textContent = '上传中，请稍候…';

    // 上传进度条：绝对定位在容器顶部，高度 3px，全部内联样式（不改任何 CSS 文件）
    var progressEl = document.createElement('div');
    progressEl.className = 'ce-progress';
    progressEl.style.cssText = 'position:absolute;top:0;left:0;right:0;height:3px;display:none;' +
      'background:rgba(79,70,229,.15);border-radius:6px 6px 0 0;overflow:hidden;z-index:40;pointer-events:none;';
    var progressBar = document.createElement('div');
    progressBar.className = 'ce-progress-bar';
    progressBar.style.cssText = 'width:0;height:100%;background:#4f46e5;transition:width .15s linear;';
    progressEl.appendChild(progressBar);

    root.appendChild(toolbar);
    root.appendChild(input);
    root.appendChild(sourceBox);
    root.appendChild(overlay);
    root.appendChild(progressEl);

    var quill = new Quill(input, {
      theme: 'snow',
      placeholder: options.placeholder || '请输入正文内容…',
      modules: {
        toolbar: { container: toolbar },
        table: true,
        history: { delay: 800, maxStack: 200, userOnly: true }
      }
    });

    var countEl = $('.ce-count', root);

    // 工具栏右侧的百分比小标签（多文件时显示 2/3 · 45%）
    var tipEl = document.createElement('span');
    tipEl.className = 'ce-upload-tip';
    tipEl.style.cssText = 'display:none;font-size:12px;line-height:1;color:#4f46e5;white-space:nowrap;padding:0 6px;';
    var rightGroup = $('.ce-group--right', root);
    if (rightGroup && countEl) rightGroup.insertBefore(tipEl, countEl);
    else if (rightGroup) rightGroup.appendChild(tipEl);

    /* ---------- 基础读写 ---------- */
    function refreshCount() {
      var text = quill.getText().replace(/\s/g, '');
      if (countEl) countEl.textContent = text.length + ' 字';
    }

    function setHTML(html) {
      quill.setContents([], 'silent');
      var value = html == null ? '' : String(html);
      if (value.trim()) {
        try { quill.clipboard.dangerouslyPasteHTML(value, 'silent'); }
        catch (e) { quill.root.innerHTML = value; }
      }
      if (quill.history) quill.history.clear();
      refreshCount();
    }

    function getHTML() { return quill.root.innerHTML; }

    function insertEmbedAt(blotName, value) {
      var range = quill.getSelection(true) || { index: quill.getLength(), length: 0 };
      quill.insertEmbed(range.index, blotName, value, 'user');
      quill.setSelection(range.index + 1, 0, 'user');
    }

    /* ---------- 上传遮罩 ---------- */
    function busy(on, text) {
      root.classList.toggle('ce-busy', !!on);
      if (text) overlay.textContent = text;
    }
    function fail(err) { notifyError((err && err.message) || '操作失败'); }

    /* ---------- 上传进度 UI ---------- */
    var progressHideTimer = null;

    function showProgress(label) {
      if (progressHideTimer) { clearTimeout(progressHideTimer); progressHideTimer = null; }
      progressEl.style.transition = 'none';
      tipEl.style.transition = 'none';
      progressEl.style.opacity = '1';
      tipEl.style.opacity = '1';
      progressEl.style.display = 'block';
      progressBar.style.width = '0%';
      tipEl.style.display = '';
      tipEl.textContent = (label || '上传中') + ' 0%';
    }

    function setProgress(pct, text) {
      var v = Math.max(0, Math.min(100, Math.round(pct)));
      progressBar.style.width = v + '%';
      if (text) tipEl.textContent = text;
    }

    /** 结束进度显示：immediate=true 立即复位清空（失败），否则进度补满后 300ms 淡出 */
    function hideProgress(immediate) {
      if (progressHideTimer) { clearTimeout(progressHideTimer); progressHideTimer = null; }
      if (immediate) {
        progressEl.style.transition = 'none';
        tipEl.style.transition = 'none';
        progressEl.style.opacity = '1';
        tipEl.style.opacity = '1';
        progressEl.style.display = 'none';
        progressBar.style.width = '0%';
        tipEl.textContent = '';
        tipEl.style.display = 'none';
        return;
      }
      progressBar.style.width = '100%';
      progressEl.style.transition = 'opacity .3s linear';
      tipEl.style.transition = 'opacity .3s linear';
      progressEl.style.opacity = '0';
      tipEl.style.opacity = '0';
      progressHideTimer = setTimeout(function () {
        progressHideTimer = null;
        progressEl.style.display = 'none';
        progressEl.style.opacity = '1';
        progressBar.style.width = '0%';
        tipEl.style.display = 'none';
        tipEl.style.opacity = '1';
        tipEl.textContent = '';
      }, 300);
    }

    /** 多文件总进度：整体进度 = (已完成文件数 + 当前文件比例) / 文件总数 */
    function makeTracker(totalCount, label) {
      var doneCount = 0;
      var fileFraction = 0;
      var lastPct = -1;

      function render() {
        var ratio = totalCount > 0 ? (doneCount + fileFraction) / totalCount : 1;
        var pct = Math.max(0, Math.min(100, Math.round(ratio * 100)));
        if (pct === lastPct) return;
        lastPct = pct;
        setProgress(pct, totalCount > 1
          ? label + ' ' + Math.min(doneCount + 1, totalCount) + '/' + totalCount + ' · ' + pct + '%'
          : label + ' ' + pct + '%');
        if (root.classList.contains('ce-busy')) overlay.textContent = label + '… ' + pct + '%';
      }

      return {
        start: function () { showProgress(label); },
        fileProgress: function (loaded, total, lengthComputable) {
          if (lengthComputable && total > 0) fileFraction = Math.max(0, Math.min(1, loaded / total));
          else fileFraction = Math.min(0.9, fileFraction + 0.05); // 拿不到总大小时缓慢推进
          render();
        },
        fileDone: function () { doneCount += 1; fileFraction = 0; render(); },
        finish: function () { render(); hideProgress(false); },
        abort: function () { hideProgress(true); }
      };
    }

    /* ---------- 图片 / 视频上传（串行 + 总进度 + 同文件去重） ---------- */
    function startUploads(list, kind) {
      var items = [];
      var skipped = 0;
      (list || []).forEach(function (file) {
        var key = claimFile(file);
        if (key) items.push({ file: file, key: key });
        else skipped += 1;
      });
      var unit = kind === 'video' ? '视频' : '图片';
      if (!items.length) {
        if (skipped) notifyError('该' + unit + '正在上传中，已跳过重复提交');
        return Promise.resolve();
      }
      if (skipped) notifyError('有 ' + skipped + ' 个文件正在上传中，已自动跳过');

      var label = unit + '上传中';
      var tracker = makeTracker(items.length, label);
      var errors = [];
      busy(true, label + '…');
      tracker.start();

      var chain = Promise.resolve();
      items.forEach(function (item) {
        chain = chain.then(function () {
          return upload(item.file, kind, function (loaded, total, lengthComputable) {
            tracker.fileProgress(loaded, total, lengthComputable);
          }).then(function (url) {
            releaseFile(item.key);
            tracker.fileDone();
            if (kind === 'video') {
              if (blotsReady) insertEmbedAt('ceVideo', { url: url });
              else insertEmbedAt('video', url);
            } else {
              insertEmbedAt('image', url);
            }
          }, function (err) {
            // 单个文件失败：释放去重标记、推进总进度、继续剩余文件；失败的图片不插入正文
            releaseFile(item.key);
            tracker.fileDone();
            errors.push(err || new Error('上传失败'));
          });
        });
      });

      return chain.then(function () {
        busy(false);
        if (errors.length) {
          tracker.abort();
          if (errors.length > 1) {
            notifyError(errors.length + ' 个文件上传失败：' + (errors[0].message || '未知错误'));
          } else {
            fail(errors[0]);
          }
        } else {
          tracker.finish();
        }
      });
    }

    function insertImages(files) {
      var list = (files || []).filter(function (f) { return kindOf(f) === 'image'; });
      if (!list.length) { alert('请选择图片文件（支持 jpg / jpeg / png / gif / webp / bmp）'); return; }
      return startUploads(list, 'image');
    }

    function insertVideos(files) {
      var list = (files || []).filter(function (f) { return kindOf(f) === 'video'; });
      if (!list.length) { alert('请选择视频文件（支持 mp4 / webm / ogg / mov / m4v）'); return; }
      return startUploads(list, 'video');
    }

    /** 按 URL 插入视频：本地/直链视频用 <video>，其它（B 站等）用 iframe 嵌入 */
    function insertVideoUrl(url) {
      var value = String(url || '').trim();
      if (!value) return;
      if (VIDEO_EXT.test(value)) {
        if (blotsReady) insertEmbedAt('ceVideo', { url: value });
        else insertEmbedAt('video', value);
      } else {
        insertEmbedAt('video', value);
      }
    }

    /* ---------- 插入视频弹窗 ---------- */
    var modal = null;
    function openVideoDialog() {
      if (!modal) {
        modal = document.createElement('div');
        modal.className = 'ce-modal';
        modal.innerHTML =
          '<div class="ce-modal-box">' +
          '  <h4>插入视频</h4>' +
          '  <p class="ce-modal-tip">支持上传本地视频（mp4/webm/ogg/mov/m4v），或粘贴视频直链 / 第三方播放页地址。</p>' +
          '  <div class="ce-modal-row">' +
          '    <button type="button" class="ce-pick-video">上传本地视频</button>' +
          '  </div>' +
          '  <div class="ce-modal-row">' +
          '    <input type="text" class="ce-input-text ce-video-url" placeholder="https://… .mp4 或第三方播放页地址">' +
          '    <button type="button" class="ce-insert-url">插入链接</button>' +
          '  </div>' +
          '  <div class="ce-modal-actions">' +
          '    <button type="button" class="ce-ghost ce-modal-cancel">取消</button>' +
          '  </div>' +
          '</div>';
        root.appendChild(modal);
        $('.ce-pick-video', modal).addEventListener('click', function () {
          closeVideoDialog();
          pickFiles('video/*', false, insertVideos);
        });
        $('.ce-insert-url', modal).addEventListener('click', function () {
          var url = $('.ce-video-url', modal).value.trim();
          if (!url) { alert('请先填写视频地址'); return; }
          closeVideoDialog();
          insertVideoUrl(url);
        });
        $('.ce-modal-cancel', modal).addEventListener('click', closeVideoDialog);
        modal.addEventListener('click', function (e) { if (e.target === modal) closeVideoDialog(); });
      }
      $('.ce-video-url', modal).value = '';
      modal.classList.add('ce-open');
    }
    function closeVideoDialog() { if (modal) modal.classList.remove('ce-open'); }

    /* ---------- HTML 源码模式 ---------- */
    function toggleSource() {
      if (sourceBox.hidden) {
        sourceBox.value = getHTML();
        sourceBox.hidden = false;
        input.style.display = 'none';
        sourceBox.focus();
      } else {
        setHTML(sourceBox.value);
        sourceBox.hidden = true;
        input.style.display = '';
        scheduleDraftSave(); // 源码模式改完内容也算一次用户编辑
      }
    }

    /* ---------- 全屏 ---------- */
    function toggleFullscreen() {
      var on = root.classList.toggle('ce-fullscreen');
      document.body.classList.toggle('ce-fullscreen-lock', on);
      quill.focus();
    }

    /* ---------- 表格操作 ---------- */
    function runTableOp(action) {
      if (!action) return;
      var table = quill.getModule('table');
      if (!table) { alert('当前编辑器未启用表格模块'); return; }
      quill.focus();
      try {
        switch (action) {
          case 'insert-3x3': table.insertTable(3, 3); break;
          case 'insert-custom': {
            var rows = parseInt(prompt('表格行数（1-20）', '3'), 10);
            var cols = parseInt(prompt('表格列数（1-10）', '3'), 10);
            if (!rows || !cols) return;
            table.insertTable(Math.min(Math.max(rows, 1), 20), Math.min(Math.max(cols, 1), 10));
            break;
          }
          case 'row-above': table.insertRowAbove(); break;
          case 'row-below': table.insertRowBelow(); break;
          case 'col-left': table.insertColumnLeft(); break;
          case 'col-right': table.insertColumnRight(); break;
          case 'row-del': table.deleteRow(); break;
          case 'col-del': table.deleteColumn(); break;
          case 'table-del': table.deleteTable(); break;
        }
      } catch (err) {
        alert('表格操作失败：' + (err && err.message ? err.message : err) + '（请先把光标放进表格内）');
      }
    }

    /* ---------- 工具栏事件绑定 ---------- */
    var toolbarModule = quill.getModule('toolbar');
    if (toolbarModule && toolbarModule.addHandler) {
      toolbarModule.addHandler('image', function () { pickFiles('image/*', true, insertImages); });
      toolbarModule.addHandler('video', function () { openVideoDialog(); });
    }
    var bindClick = function (selector, fn) {
      var el = $(selector, root);
      if (el) el.addEventListener('click', function (e) { e.preventDefault(); fn(); });
    };
    bindClick('.ce-source', toggleSource);
    bindClick('.ce-fullscreen', toggleFullscreen);
    bindClick('.ce-undo', function () { if (quill.history) quill.history.undo(); });
    bindClick('.ce-redo', function () { if (quill.history) quill.history.redo(); });
    bindClick('.ce-divider', function () {
      if (blotsReady) insertEmbedAt('ceDivider', true);
      else alert('分割线组件未就绪');
    });

    // 表格下拉菜单（用自研按钮 + 菜单，避免 Quill 把 <select> 改造成无文字标签的 picker）
    var tableBtn = $('.ce-table-btn', root);
    var tableMenu = $('.ce-menu', root);
    if (tableBtn && tableMenu) {
      tableBtn.addEventListener('click', function (e) {
        e.preventDefault();
        tableMenu.hidden = !tableMenu.hidden;
      });
      tableMenu.addEventListener('click', function (e) {
        var item = e.target && e.target.closest ? e.target.closest('a[data-op]') : null;
        if (!item) return;
        e.preventDefault();
        tableMenu.hidden = true;
        runTableOp(item.getAttribute('data-op'));
      });
      document.addEventListener('click', function (e) {
        if (!root.contains(e.target)) tableMenu.hidden = true;
      });
    }

    /* ---------- 草稿自动保存（仅 options.draftKey 非空时启用） ---------- */
    var draftKey = (typeof options.draftKey === 'string' && options.draftKey !== '') ? options.draftKey : '';
    var draftDelay = (typeof options.draftDebounce === 'number' && options.draftDebounce >= 0)
      ? options.draftDebounce : 1800;
    var draftTimer = null;
    var draftLastHtml = null;

    /** 立即落盘（同时取消待触发的 debounce）；返回是否真的写入 */
    function saveDraftNow() {
      if (!draftKey) return false;
      if (draftTimer) { clearTimeout(draftTimer); draftTimer = null; }
      var html = getHTML();
      if (draftLastHtml !== null && html === draftLastHtml) return false;
      if (!writeDraft(draftKey, html)) return false;
      draftLastHtml = html;
      return true;
    }

    function scheduleDraftSave() {
      if (!draftKey) return;
      if (draftTimer) clearTimeout(draftTimer);
      draftTimer = setTimeout(function () {
        draftTimer = null;
        saveDraftNow();
      }, draftDelay);
    }

    /** destroy 时只在「还有没落盘的改动」时补写一次，避免凭空产生草稿 */
    function flushPendingDraft() {
      if (draftKey && draftTimer) saveDraftNow();
    }

    /* ---------- 粘贴解析辅助 ---------- */
    /** 去掉 <img>/<br>/标签与空白后判断 HTML 是否只剩图片 */
    function htmlIsOnlyImages(html) {
      var s = String(html || '')
        .replace(/<img\b[^>]*>/gi, '')
        .replace(/<br\s*\/?>/gi, '')
        .replace(/&nbsp;/gi, '')
        .replace(/<[^>]*>/g, '');
      return s.replace(/\s/g, '') === '';
    }

    function clipboardHtml(e) {
      try {
        return (e.clipboardData && e.clipboardData.getData('text/html')) || '';
      } catch (err) {
        return '';
      }
    }

    /** 剪贴板是否带「真正的文本/富文本」内容（只含 <img> 的 HTML 不算，按图片处理） */
    function clipboardHasText(e) {
      var cd = e.clipboardData;
      if (!cd) return false;
      var types = [];
      try { types = Array.prototype.slice.call(cd.types || []); } catch (err) { types = []; }
      for (var i = 0; i < types.length; i++) {
        var t = types[i];
        if (t !== 'text/plain' && t !== 'text/html' && t !== 'text/rtf') continue;
        var v = '';
        try { v = cd.getData(t) || ''; } catch (e2) { v = ''; }
        if (!v || v.replace(/\s/g, '') === '') continue;
        if (t === 'text/html' && htmlIsOnlyImages(v)) continue;
        // 个别浏览器复制网页图片时把 data:image URI 放进 text/plain，这也不算「文本内容」
        if (t === 'text/plain' && /^data:image\/[a-z0-9.+-]+;base64,[a-z0-9+/=\s]+$/i.test(v)) continue;
        return true;
      }
      return false;
    }

    /** 取剪贴板文件：files 优先，部分浏览器只有 items（兜底 getAsFile） */
    function clipboardFiles(e) {
      var cd = e.clipboardData;
      if (!cd) return [];
      var out = [];
      var files = cd.files;
      if (files && files.length) {
        for (var i = 0; i < files.length; i++) out.push(files[i]);
        return out;
      }
      var items = cd.items || [];
      for (var j = 0; j < items.length; j++) {
        var it = items[j];
        if (it && it.kind === 'file' && typeof it.getAsFile === 'function') {
          var f = it.getAsFile();
          if (f) out.push(f);
        }
      }
      return out;
    }

    /** 把 HTML 里的 data:image base64 还原成 File（个别浏览器复制网页图片时不给 File） */
    function dataUriImages(html) {
      var out = [];
      if (typeof window.File !== 'function' || typeof window.atob !== 'function') return out;
      var re = /<img\b[^>]*\bsrc\s*=\s*["'](data:image\/([a-z0-9.+-]+);base64,([^"']+))["'][^>]*>/gi;
      var m;
      while ((m = re.exec(String(html || '')))) {
        try {
          var bin = window.atob(m[3]);
          var len = bin.length;
          var bytes = new Uint8Array(len);
          for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
          var ext = m[2] === 'jpeg' ? 'jpg' : m[2];
          out.push(new window.File([bytes], 'pasted-' + Date.now() + '-' + out.length + '.' + ext, {
            type: 'image/' + m[2]
          }));
        } catch (err) { /* 单张解析失败忽略 */ }
      }
      return out;
    }

    /* ---------- 粘贴 / 拖拽媒体文件 ---------- */
    quill.root.addEventListener('paste', function (e) {
      var files = clipboardFiles(e);
      var images = [], videos = [];
      files.forEach(function (f) {
        var kind = kindOf(f);
        if (kind === 'image') images.push(f);
        else if (kind === 'video') videos.push(f);
      });

      // 没有文件对象时，尝试把「只含 data:image 的富文本」还原成图片文件
      if (!images.length && !videos.length) {
        var html = clipboardHtml(e);
        if (html && htmlIsOnlyImages(html)) images = dataUriImages(html);
      }

      // 带文本/富文本时不接管，保持 Quill 默认粘贴行为
      var hasText = clipboardHasText(e);
      var takeImages = images.length > 0 && !hasText;
      var takeVideos = videos.length > 0 && !hasText;
      if (!takeImages && !takeVideos) return;

      e.preventDefault();
      if (takeImages) insertImages(images);
      if (takeVideos) insertVideos(videos);
    });
    quill.root.addEventListener('drop', function (e) {
      var files = e.dataTransfer && e.dataTransfer.files;
      if (!files || !files.length) return;
      var hasMedia = Array.prototype.some.call(files, function (f) { return kindOf(f) !== ''; });
      if (!hasMedia) return;
      e.preventDefault();
      insertImages(files);
      insertVideos(files);
    });

    /* ---------- ESC 退出全屏 / 关闭弹窗 ---------- */
    root.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      if (modal && modal.classList.contains('ce-open')) { closeVideoDialog(); return; }
      if (root.classList.contains('ce-fullscreen')) toggleFullscreen();
    });

    quill.on('text-change', refreshCount);
    quill.on('text-change', function (delta, oldDelta, source) {
      // 只有用户编辑才写草稿：setHTML / 恢复草稿（silent）不会触发
      if (source === 'user') scheduleDraftSave();
    });

    /* ---------- 对外 API ---------- */
    var api = {
      name: options.name || '',
      quill: quill,
      getHTML: getHTML,
      setHTML: function (html) { setHTML(html); return api; },
      getText: function () { return quill.getText(); },
      count: function () { return quill.getText().replace(/\s/g, '').length; },
      isEmpty: function () {
        return quill.getText().trim() === '' &&
          !quill.root.querySelector('img, video, hr, table, iframe');
      },
      clear: function () { setHTML(''); return api; },
      focus: function () { quill.focus(); return api; },
      insertImage: function (url) { insertEmbedAt('image', url); return api; },
      insertVideo: function (url) { insertVideoUrl(url); return api; },
      uploadImages: insertImages,
      uploadVideos: insertVideos,
      toggleSource: toggleSource,
      toggleFullscreen: toggleFullscreen,
      busy: busy,
      on: function (evt, fn) { quill.on(evt, fn); return api; },
      // --- 草稿（draftKey 为空时全部为 no-op / false / null） ---
      draftKey: draftKey,
      saveDraftNow: saveDraftNow,
      hasDraft: function () { return draftKey ? hasDraft(draftKey) : false; },
      loadDraft: function () { return draftKey ? loadDraft(draftKey) : null; },
      clearDraft: function () { return draftKey ? clearDraft(draftKey) : false; },
      _flushPendingDraft: flushPendingDraft
    };

    if (options.name) instances[options.name] = api;
    if (options.initialHtml) setHTML(options.initialHtml);
    refreshCount();
    return api;
  }

  function get(name) { return instances[name] || null; }

  function destroy(name) {
    var inst = instances[name];
    if (!inst) return;
    try {
      // 未落盘的草稿在销毁前补写一次（没有待写改动时不会凭空产生草稿）
      if (typeof inst._flushPendingDraft === 'function') inst._flushPendingDraft();
    } catch (e) { /* 忽略 */ }
    delete instances[name];
  }

  return {
    create: create,
    get: get,
    destroy: destroy,
    hasDraft: hasDraft,
    loadDraft: loadDraft,
    clearDraft: clearDraft,
    draftStorageKey: draftStorageKey,
    version: VERSION
  };
})();
