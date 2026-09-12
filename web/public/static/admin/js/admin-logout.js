/**
 * 后台退出登录：点击侧边栏「退出登录」（/logout）时同步清理后台专用 Token。
 *
 * 原因：后台 Token 也能通过 /api/admin/** 鉴权（JwtAuthFilter 会用它建立会话），
 * 若退出时只注销 Session 而保留 Token，旧 Token 仍可继续访问后台接口。
 * 前台 Token（culture_front_token）不受影响。
 */
(function () {
  'use strict';

  function isLogoutLink(el) {
    if (!el || el.tagName !== 'A') return false;
    var href = el.getAttribute('href') || '';
    return href === '/logout' || href === '/api/auth/admin/logout';
  }

  document.addEventListener('click', function (e) {
    var el = e.target;
    while (el && el !== document) {
      if (isLogoutLink(el)) {
        try {
          localStorage.removeItem('culture_admin_token');
          localStorage.removeItem('culture_admin_email');
        } catch (err) { /* 隐私模式忽略 */ }
        break;
      }
      el = el.parentNode;
    }
  }, true);
})();
