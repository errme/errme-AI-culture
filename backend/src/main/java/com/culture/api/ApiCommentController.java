package com.culture.api;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Comment;
import com.culture.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 评论公开 API（前台匿名可访问）。
 *
 * <pre>
 * GET  /api/comment/list?cultureId=   某文化下「已通过」的评论（两级树）：{ total, comments }
 * POST /api/comment/submit            body { cultureId, parentId, content }（需前台登录；先审后发）
 * </pre>
 *
 * 只有列表匿名放行（WebSecurityConfig 与 JwtAuthFilter.isPublic 两处登记）；
 * 提交接口不在白名单内 —— 未带前台 Token 会被过滤器直接 401。
 */
@RestController
@RequestMapping("/api")
public class ApiCommentController {

    @Autowired
    private CommentService commentService;

    /** 已通过的评论列表（两级树） */
    @GetMapping("/comment/list")
    public ApiResult<Map<String, Object>> list(@RequestParam(value = "cultureId", required = false) Long cultureId) {
        return ApiResult.ok(commentService.listApproved(cultureId));
    }

    /** 提交评论：内容清洗后入库，status=0 待审 */
    @PostMapping("/comment/submit")
    public ApiResult<Map<String, Object>> submit(@RequestBody Comment body, HttpServletRequest request) {
        try {
            // 评论必须登录后提交：用户身份只认令牌，不接受前端传入的昵称/邮箱
            Object uid = request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
            if (uid == null) {
                return ApiResult.error(401, "请先登录后再留言");
            }
            body.setUserId((Long) uid);
            body.setNickname(null);
            body.setEmail(null);
            Map<String, Object> data = commentService.submit(body, clientIp(request),
                    request.getHeader("User-Agent"));
            return ApiResult.ok("留言已提交，审核通过后展示", data);
        } catch (BusinessException e) {
            return ApiResult.error(e.getMessage());
        } catch (Exception e) {
            return ApiResult.error("提交失败，请稍后再试");
        }
    }

    /** 取客户端真实 IP（Nginx 反代后 remoteAddr 是代理地址，优先取转发头） */
    private String clientIp(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String h : headers) {
            String v = request.getHeader(h);
            if (v != null && !v.isEmpty() && !"unknown".equalsIgnoreCase(v)) {
                int comma = v.indexOf(',');
                return (comma > 0 ? v.substring(0, comma) : v).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
