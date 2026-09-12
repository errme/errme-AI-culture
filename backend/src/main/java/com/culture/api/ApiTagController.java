package com.culture.api;

import com.culture.entity.Tag;
import com.culture.service.TagService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签公开 API（前台匿名可访问）。
 *
 * <pre>
 * GET /api/tag/list                     全部标签（含每个标签的内容数 cultureCount）
 * GET /api/tag/cultures?tagId=&page=&pageSize=   某标签下的文化分页
 * </pre>
 *
 * 匿名放行需要在两处登记（与 /api/search 同样的加法）：
 * WebSecurityConfig 的 antMatchers 与 JwtAuthFilter.isPublic()。
 */
@RestController
@RequestMapping("/api")
public class ApiTagController {

    @Autowired
    private TagService tagService;
    /** 前台标签列表的 Redis 短缓存（写路径主动失效 + TTL 兜底，Redis 异常自动降级查库） */
    @Autowired
    private com.culture.service.CacheService cacheService;

    /**
     * 全部标签（按 sort,id 升序，cultureCount 为内容数）。
     *
     * <p>走 Redis 短缓存（key = {@link com.culture.service.CacheService#KEY_TAG_LIST}）：
     * 标签列表带 cultureCount 聚合、每个前台页面都要请求，属于典型读多写少的公共数据。
     * 标签的增删改/恢复/合并/改名，以及内容标签变化（setCultureTags）都会主动删 key，
     * 正常操作下前台即时生效；TTL 只兜底。</p>
     */
    @GetMapping("/tag/list")
    public ApiResult<List<Tag>> list() {
        return ApiResult.ok(cacheService.getOrLoad(
                com.culture.service.CacheService.KEY_TAG_LIST,
                () -> tagService.queryAll(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Tag>>() {
                }));
    }

    /** 某标签下的文化分页 */
    @GetMapping("/tag/cultures")
    public ApiResult<PageList> cultures(@RequestParam(value = "tagId", required = false) Long tagId,
                                       @RequestParam(value = "page", required = false) Integer page,
                                       @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResult.ok(tagService.culturesOfTag(tagId, page, pageSize));
    }
}
