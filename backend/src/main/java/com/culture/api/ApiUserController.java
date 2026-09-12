package com.culture.api;

import com.culture.entity.Culture;
import com.culture.entity.User;
import com.culture.service.CultureService;
import com.culture.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台用户 API（需 JWT）：个人中心、收藏/取消收藏。
 */
@RestController
@RequestMapping("/api")
public class ApiUserController {

    @Autowired
    private CultureService cultureService;
    @Autowired
    private UserService userService;

    /** 个人中心：用户信息 + 我的收藏 */
    @GetMapping("/user/center")
    public ApiResult<Map<String, Object>> center(HttpServletRequest request) {
        Long uid = currentUserId(request);
        User user = userService.findById(uid);
        List<Culture> likes = cultureService.findMyScCulture(uid);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", user);
        data.put("likes", likes);
        return ApiResult.ok(data);
    }

    /** 收藏文化 */
    @PostMapping("/culture/like/{id}")
    public ApiResult<Void> like(@PathVariable Long id, HttpServletRequest request) {
        Long uid = currentUserId(request);
        if (cultureService.isExistScCulture(uid, id)) {
            return ApiResult.error("您已经收藏过了");
        }
        cultureService.scCulture(uid, id);
        return ApiResult.ok("收藏成功", null);
    }

    /** 取消收藏 */
    @PostMapping("/culture/cancel/{id}")
    public ApiResult<Void> cancel(@PathVariable Long id, HttpServletRequest request) {
        Long uid = currentUserId(request);
        cultureService.cancelScCulture(uid, id);
        return ApiResult.ok("已取消收藏", null);
    }

    private Long currentUserId(HttpServletRequest request) {
        return (Long) request.getAttribute("loginUserId");
    }
}
