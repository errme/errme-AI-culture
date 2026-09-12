package com.culture.util;

import com.culture.config.UserSecurity;
import com.culture.entity.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录用户。
 *
 * <p><b>设计（Spring Boot 3 升级后）：</b></p>
 * <ul>
 *   <li>前后台都走 JWT：{@code JwtAuthFilter} 校验令牌后，把<b>已加载的 User 实体</b>
 *       直接作为 principal 放进 SecurityContext；</li>
 *   <li>{@link UserSecurity} 是更早期「Session 表单登录」时代的 principal 类型，
 *       保留兼容分支以防还有残留调用，但当前正常流程不会走到；</li>
 *   <li>未登录 / 匿名访问时返回 {@code null}，调用方<b>必须判空</b>。</li>
 * </ul>
 *
 * <p><b>踩坑记录：</b>本项目曾出现「后台新增句子报保存失败」的回归 ——
 * 原因是改成纯 Token 后 principal 一度被写成 {@code "admin:1"} 这样的字符串，
 * 而本方法只认 {@code UserSecurity}，于是返回 null，
 * 业务层紧接着调用 {@code getLoginUser().getId()} 抛 NPE。
 * 现在明确支持 User 实体，并且业务层也要判空。</p>
 */
public final class CommonUtil {

    private CommonUtil() {
    }

    /** 当前登录用户；未登录返回 null */
    public static User getLoginUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserSecurity) {
            return ((UserSecurity) principal).getLoginUser();
        }
        if (principal instanceof User) {
            return (User) principal;
        }
        // 匿名（"anonymousUser" 字符串）或其它类型：视为未登录
        return null;
    }

    /** 当前登录用户 id；未登录返回 null（避免调用方为了取 id 而触发 NPE） */
    public static Long getLoginUserId() {
        User user = getLoginUser();
        return user == null ? null : user.getId();
    }
}
