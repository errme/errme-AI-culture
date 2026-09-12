package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Sentence;
import com.culture.mapper.SentenceMapper;
import com.culture.mapper.UserMapper;
import com.culture.query.SentenceQuery;
import com.culture.service.SentenceService;
import com.culture.service.ThumbnailService;
import com.culture.util.CommonUtil;
import com.culture.util.PageList;
import com.culture.util.SearchUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class SentenceServiceImpl implements SentenceService {

    /** 单次批量操作上限（与前端「一次最多选 500 条」对齐） */
    private static final int MAX_BATCH_SIZE = 500;

    @Autowired
    private SentenceMapper sentenceMapper;

    @Autowired
    private UserMapper userMapper;

    /** 列表缩略图派生（A3）：句子卡片上的作者头像改用缩略图 */
    @Autowired
    private ThumbnailService thumbnailService;


    @Override
    public List<Sentence> findAll() {
        List<Sentence> list = sentenceMapper.findAll();
        thumbnailService.applySentenceAvatarThumb(list);
        return list;
    }

    @Override
    public List<Sentence> queryAll() {
        List<Sentence> list = sentenceMapper.queryAll();
        thumbnailService.applySentenceAvatarThumb(list);
        return list;
    }

    @Override
    public PageList listpage(SentenceQuery sentenceQuery) {
        PageList pageList = new PageList();

        Long total = sentenceMapper.queryTotal(sentenceQuery);
        List<Sentence> sentences = sentenceMapper.queryData(sentenceQuery);

        pageList.setTotal(total);
        pageList.setRows(sentences);
        return pageList;
    }

    /** 前台首页聚合缓存（/api/home 含句子列表）：句子一变就失效 */
    @Autowired
    private com.culture.service.CacheService cacheService;

    private void evictHome() {
        if (cacheService != null) {
            cacheService.evictHome();
        }
    }

    @Override
    public void addSentence(Sentence sentence) {
        sentence.setCreateId(CommonUtil.getLoginUser().getId());
        Long uid = CommonUtil.getLoginUser().getId();
        sentence.setCreateTime(new Date());
        sentence.setCreateName(userMapper.findUserById(uid));
        sentence.setCreateImg(userMapper.findUserByImg(uid));
        sentenceMapper.addSentence(sentence);
        evictHome();
    }

    @Override
    public void editSaveSentence(Sentence sentence) {
        sentenceMapper.editSaveSentence(sentence);
        evictHome();
    }

    @Override
    public void deleteSentence(Long id) {
        sentenceMapper.deleteSentence(id);
        evictHome();
    }

    /**
     * 全站搜索（句子）——命中总数（不带时间筛选，等价于两个时间参数为 null）。
     *
     * <p>保留这个既有签名给老调用方使用，行为与改造前逐字一致。</p>
     */
    @Override
    public Long querySearchTotal(String keyword) {
        return querySearchTotal(keyword, null, null);
    }

    /**
     * 全站搜索（句子）——命中总数。
     *
     * <p>检索策略（增量）：关键词 &gt;= 2 个字先走 ngram 全文索引
     * （MATCH(content) AGAINST('"关键词"' IN BOOLEAN MODE)，作者名 create_name 仍 or 上 LIKE）；
     * 索引缺失（ERROR 1191）或任何 SQL 异常都被吞掉并降级回 LIKE，搜索接口不会 500。</p>
     *
     * <p>【增量】句子侧只支持时间范围筛选（biz_sentence 只有 created_at 可筛；
     * 分类 / 标签对句子无意义，由控制层在句子侧忽略）。两个时间参数都为 null 时
     * 生成的 SQL 与改造前逐字一致；全文路径与 LIKE 路径传同一组时间参数，
     * 且与分页查询共用 Mapper 的同一段 {@code SEARCH_TIME_FILTER_SQL}，总数与列表口径一致。</p>
     *
     * @param startTime 已规范化的开始时间（{@code yyyy-MM-dd HH:mm:ss}），null = 不限
     * @param endTime   已规范化的结束时间（{@code yyyy-MM-dd HH:mm:ss}），null = 不限
     */
    @Override
    public Long querySearchTotal(String keyword, String startTime, String endTime) {
        String kw = SearchUtil.normalizeKeyword(keyword);
        if (kw.isEmpty()) {
            // 与原 LIKE 行为一致：空关键词命中 0
            return 0L;
        }
        if (SearchUtil.supportsFullText(kw) && SearchUtil.isFullTextUsable()) {
            try {
                return sentenceMapper.querySearchTotalByFullText(SearchUtil.toBooleanPhrase(kw),
                        SearchUtil.toLikePattern(kw), startTime, endTime);
            } catch (Exception e) {
                SearchUtil.markFullTextUnavailable("SentenceService.querySearchTotal", e);
            }
        }
        return sentenceMapper.querySearchTotal(SearchUtil.toLikePattern(kw), startTime, endTime);
    }

    /**
     * 全站搜索（句子）——分页数据（不带时间筛选，等价于两个时间参数为 null）。
     *
     * <p>保留这个既有签名给老调用方使用，行为与改造前逐字一致。</p>
     */
    @Override
    public List<Sentence> querySearchData(String keyword, Integer offset, Integer pageSize) {
        return querySearchData(keyword, offset, pageSize, null, null);
    }

    /**
     * 全站搜索（句子）——分页数据。
     *
     * <p>与总数接口同一套策略与同一筛选片段；全文/LIKE 两种方式的返回字段、
     * 过滤条件（含时间范围）、排序参数一致，前端无感。全文路径抛异常时降级回 LIKE，
     * 降级时同样带上时间参数，筛选结果不会因为降级而变样。</p>
     */
    @Override
    public List<Sentence> querySearchData(String keyword, Integer offset, Integer pageSize,
                                          String startTime, String endTime) {
        String kw = SearchUtil.normalizeKeyword(keyword);
        if (kw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Sentence> list = null;
        if (SearchUtil.supportsFullText(kw) && SearchUtil.isFullTextUsable()) {
            try {
                list = sentenceMapper.querySearchDataByFullText(SearchUtil.toBooleanPhrase(kw),
                        SearchUtil.toLikePattern(kw), offset, pageSize, startTime, endTime);
            } catch (Exception e) {
                SearchUtil.markFullTextUnavailable("SentenceService.querySearchData", e);
                list = null;
            }
        }
        if (list == null) {
            list = sentenceMapper.querySearchData(SearchUtil.toLikePattern(kw), offset, pageSize,
                    startTime, endTime);
        }
        // A3：搜索结果里的句子作者头像同样走缩略图
        thumbnailService.applySentenceAvatarThumb(list);
        return list;
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /** 恢复单条句子：只把 deleted 改回 0，返回实际影响行数 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少句子 id");
        }
        int rows = sentenceMapper.restoreById(id);
        evictHome();
        return rows;
    }

    /** 批量恢复句子；ids 为空/全为 null 或超过 500 条时抛 BusinessException */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        int rows = sentenceMapper.restoreByIds(normalizeIds(ids));
        evictHome();
        return rows;
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误（而不是 500）。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条句子");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条句子");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 条");
        }
        return safeIds;
    }
}
