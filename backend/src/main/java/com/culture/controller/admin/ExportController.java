package com.culture.controller.admin;

import cn.afterturn.easypoi.excel.ExcelExportUtil;
import cn.afterturn.easypoi.excel.entity.ExportParams;
import com.culture.api.JwtAuthFilter;
import com.culture.entity.Role;
import com.culture.entity.User;
import com.culture.service.RoleService;
import com.culture.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 后台用户数据导出（Excel）。
 *
 * <p><b>安全修复（重要）：</b>本接口原先的路径是 {@code /user/downloadExcel}，
 * 且<b>没有任何管理员校验</b>。由于该路径不在 {@code /api/} 前缀下，
 * {@code JwtAuthFilter} 的 {@code isProtectedApi()} 判断会直接跳过它，
 * 最终只落到 Spring Security 的 {@code anyRequest().authenticated()}；
 * 而前台令牌同样会被视为「已认证」——于是<b>任何自助注册的前台用户</b>
 * 都可以下载全站用户表（姓名 / 邮箱 / 电话 / 注册时间）。</p>
 *
 * <p>本次修复做了三件事：</p>
 * <ol>
 *   <li>路径迁到 {@code /api/admin/user/export}，纳入 {@code /api/admin/**}
 *       的统一鉴权（只有后台令牌可达）；</li>
 *   <li>方法内再做一次显式的管理员校验，与其它后台接口保持一致（双保险）；</li>
 *   <li>导出内容排除逻辑删除的用户（{@code UserMapper.queryAll} 已补上
 *       {@code deleted = 0}），与后台列表口径一致。</li>
 * </ol>
 *
 * <p><b>鉴权方式变更：</b>随着 Session 桥接被移除，浏览器直接跳转 URL 的下载方式
 * 无法再携带登录态（跳转带不上 Authorization 头）。前端改为用 axios 以
 * blob 方式请求并本地保存，与既有的「文化 CSV 导出」保持同一套写法。</p>
 */
@Controller
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final UserService userService;
    private final RoleService roleService;

    public ExportController(UserService userService, RoleService roleService) {
        this.userService = userService;
        this.roleService = roleService;
    }

    @GetMapping("/api/admin/user/export")
    public void download(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // ---- 1) 管理员校验（与 /api/admin/** 其它接口同一口径）----
        Long userId = (Long) request.getAttribute(JwtAuthFilter.ATTR_LOGIN_USER_ID);
        if (!isAdmin(userId)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"无权限\",\"data\":null}");
            return;
        }

        // ---- 2) 生成 Excel ----
        response.setContentType("application/vnd.ms-excel");
        response.setCharacterEncoding("UTF-8");
        String filename = URLEncoder.encode("用户数据表", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment;filename=" + filename + ".xls");

        List<User> list = userService.queryAll();

        // try-with-resources：POI 的 Workbook 持有临时文件与内存缓冲，不关闭会造成泄漏
        // （原实现从未 close，也没有 finally）。
        try (Workbook workbook = ExcelExportUtil.exportExcel(new ExportParams(), User.class, list);
             OutputStream out = response.getOutputStream()) {
            workbook.write(out);
            out.flush();
        } catch (Exception e) {
            // 这里可能只是「客户端取消下载」导致的管道中断，不必当成严重错误；
            // 但必须记录，不能像原实现那样静默（原实现连异常都没接）。
            log.warn("[export] 用户表导出中断：{}", e.getMessage());
            throw e;
        }
    }

    /** 当前用户是否管理员（与各后台控制器同一判定口径：角色名为「管理员」） */
    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        List<Role> roles = roleService.listRoleByUserId(userId);
        if (roles == null) {
            return false;
        }
        for (Role r : roles) {
            if (JwtAuthFilter.ROLE_ADMIN_NAME.equals(r.getName())) {
                return true;
            }
        }
        return false;
    }
}
