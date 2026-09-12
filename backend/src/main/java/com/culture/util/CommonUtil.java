package com.culture.util;

import com.culture.config.UserSecurity;
import com.culture.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录用户。
 * - 后台管理走 Session 表单登录：principal 为 UserSecurity；
 * - 前台走 JWT：JwtAuthFilter 已把用户名桥接进 Session（loginUserName），
 *   页面控制器可继续用 Session 读取；此处对非 UserSecurity 场景返回 null 兜底。
 */
public class CommonUtil {

    public static User getLoginUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        if ("anonymousUser".equals(auth.getPrincipal()) || !(auth.getPrincipal() instanceof UserSecurity)) {
            return null;
        }
        return ((UserSecurity) auth.getPrincipal()).getLoginUser();
    }
}
