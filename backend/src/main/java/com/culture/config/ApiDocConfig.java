package com.culture.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 文档（springdoc-openapi / Swagger UI）配置。
 *
 * <p>替代原先的 springfox 2.9.2 —— 它不支持 Spring Boot 3，上一轮升级时已移除。
 * springdoc 2.x 与 Spring Boot 3.x 对应（3.x 对应 Spring Boot 4）。</p>
 *
 * <p><b>全部可配置</b>：开关、路径、标题、描述、版本都来自 {@code app.api-doc.*}
 * （见 application.yml），不写死在代码里；关掉时本 Bean 不创建，
 * 也不会注册任何文档端点。</p>
 *
 * <p>这里额外声明了<b>双 Token 的 SecurityScheme</b>：项目的鉴权是「前台 / 后台两套
 * JWT 密钥 + 两套 scope」，在 Swagger UI 右上角 Authorize 里填对应令牌即可直接调试，
 * 无需手工拼 Authorization 头。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.api-doc.enabled", havingValue = "true", matchIfMissing = true)
public class ApiDocConfig {

    /** 前台令牌 scheme 名（Swagger UI 上显示的名称） */
    private static final String SCHEME_FRONT = "前台Token";
    /** 后台令牌 scheme 名 */
    private static final String SCHEME_ADMIN = "后台Token";

    @Bean
    public OpenAPI cultureOpenAPI(
            @Value("${app.api-doc.title:遇你 · culture REST API}") String title,
            @Value("${app.api-doc.description:传统文化站前后端分离接口文档（前台 + 后台）}") String description,
            @Value("${app.api-doc.version:2.0}") String version,
            @Value("${app.site.base-url:http://localhost:8080}") String baseUrl,
            @Value("${app.site.name:遇你}") String siteName) {

        SecurityScheme frontScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization")
                .description("前台登录签发的令牌（scope=front）。"
                        + "对应接口：POST /api/auth/login");

        SecurityScheme adminScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .in(SecurityScheme.In.HEADER)
                .name("Authorization")
                .description("后台登录签发的令牌（scope=admin）。"
                        + "对应接口：POST /api/auth/admin/login。"
                        + "前台令牌访问 /api/admin/** 会返回 403。");

        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .description(description
                                + "\n\n**站点**：" + siteName + "（" + baseUrl + "）"
                                + "\n\n**鉴权说明**：前台与后台使用两套独立密钥签发的 JWT，互不通用。"
                                + "点击右上角 Authorize 填入对应令牌后再调试。")
                        .version(version)
                        .contact(new Contact().name(siteName).url(baseUrl))
                        .license(new License().name("Apache 2.0")
                                .url("http://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes(SCHEME_FRONT, frontScheme)
                        .addSecuritySchemes(SCHEME_ADMIN, adminScheme))
                // 默认不全局要求认证：公开接口（首页/列表/搜索/SEO）直接可试，
                // 需要登录的接口点 Authorize 填令牌即可。
                .addSecurityItem(new SecurityRequirement());
    }
}
