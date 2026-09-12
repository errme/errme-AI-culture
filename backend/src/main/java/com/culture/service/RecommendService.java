package com.culture.service;

import com.culture.entity.Culture;

import java.util.List;

/**
 * 前台推荐接口（详情页「猜你喜欢」）。
 *
 * <p>实现见 {@link com.culture.service.impl.RecommendServiceImpl}：
 * 基于用户的协同过滤，纯 SQL 实现，不再依赖 Apache Mahout。</p>
 */
public interface RecommendService {

    /**
     * 基于用户行为的推荐。
     *
     * @param userId  当前登录用户 id
     * @param howMany 最多返回条数
     * @return 推荐内容列表；用户无任何收藏等冷启动场景返回<b>空列表</b>（不返回 null），
     *         由调用方用热门内容兜底
     */
    List<Culture> getRecommendItemsByUser(Long userId, int howMany);
}
