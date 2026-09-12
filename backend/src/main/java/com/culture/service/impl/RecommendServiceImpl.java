package com.culture.service.impl;

import com.culture.entity.Culture;
import com.culture.mapper.CultureMapper;
import com.culture.service.RecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 前台推荐服务——基于用户的协同过滤。
 *
 * <p><b>实现已从 Apache Mahout 改为纯 SQL</b>（见
 * {@link CultureMapper#findRecommendByUser}）：</p>
 * <ul>
 *   <li>Mahout 0.9 发布于 2014 年，依赖 hadoop-core 1.2.1，且整体基于 {@code javax.*}，
 *       与 Spring Boot 3（{@code jakarta.*}）不可能共存；</li>
 *   <li>本项目的 {@code biz_like} 只有数百行数据，为此引入 Hadoop + Lucene + Mahout
 *       约 30MB 依赖属于严重过度设计；</li>
 *   <li>原实现还在<b>每次请求</b>重建 UserSimilarity / UserNeighborhood / Recommender
 *       三个对象，缓存一失效就是一次完整重算。</li>
 * </ul>
 *
 * <p>现在整条链路只发一条 SQL，排序语义（共同收藏者数量优先、其次偏好值之和）
 * 与原协同过滤一致。冷启动用户（无任何收藏）返回空列表，
 * 由 {@code CultureServiceImpl} 用「热门内容」兜底，与原逻辑相同。</p>
 */
@Service
public class RecommendServiceImpl implements RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendServiceImpl.class);

    private final CultureMapper cultureMapper;

    /** 构造注入：字段注入在单元测试里无法直接替换依赖 */
    public RecommendServiceImpl(CultureMapper cultureMapper) {
        this.cultureMapper = cultureMapper;
    }

    @Override
    public List<Culture> getRecommendItemsByUser(Long userId, int howMany) {
        if (userId == null || howMany <= 0) {
            return Collections.emptyList();
        }
        try {
            List<Culture> list = cultureMapper.findRecommendByUser(userId, howMany);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            // 推荐只是详情页的锦上添花：任何异常都不应让详情页 500。
            // 用参数化日志而不是 e.printStackTrace()（后者绕过日志框架，生产环境等于丢异常）。
            log.warn("[recommend] 用户 {} 的推荐查询失败，降级为空列表：{}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
