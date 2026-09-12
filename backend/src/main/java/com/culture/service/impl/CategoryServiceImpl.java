package com.culture.service.impl;


import com.culture.auth.service.BusinessException;
import com.culture.entity.Category;
import com.culture.mapper.CategoryMapper;
import com.culture.query.CategoryQuery;
import com.culture.service.CategoryService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {

    /** 单次批量操作上限（与前端「一次最多选 500 条」对齐） */
    private static final int MAX_BATCH_SIZE = 500;

    @Autowired
    private CategoryMapper categoryMapper;

    /**
     * 分类列表的前台读缓存（见 CacheService / ApiHomeController#categorys）。
     * 所有写路径结束后都要删 key，否则前台要等 TTL 到期才更新。
     * 这里是「写库后立即删」：删早了会与并发读产生极短的竞态（读线程可能把旧值写回），
     * 由 TTL 兜底，不会永久不一致。
     */
    @Autowired
    private com.culture.service.CacheService cacheService;

    /** 分类写操作后统一失效前台分类列表缓存（失败只记日志，不影响写结果） */
    private void evictFrontCache() {
        if (cacheService != null) {
            cacheService.evictCategoryList();
        }
    }


    @Override
    public List<Category> queryAll() {
        return categoryMapper.queryAll();
    }

    @Override
    public PageList listpage(CategoryQuery categoryQuery) {
        PageList pageList = new PageList();
        //查询总的条数
        Long total = categoryMapper.queryTotal(categoryQuery);
        List<Category> categories = categoryMapper.queryData(categoryQuery);
        pageList.setTotal(total);
        pageList.setRows(categories);
        //分页查询的数据
        return pageList;
    }

    @Override
    public void addSave(Category category) {
        categoryMapper.addSave(category);
        evictFrontCache();
    }

    @Override
    public void editSaveCategory(Category category) {
        categoryMapper.editSaveCategory(category);
        evictFrontCache();
    }

    @Override
    public void deleteCategory(Long id) {
        categoryMapper.deleteCategory(id);
        evictFrontCache();
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /** 恢复单条分类：只把 deleted 改回 0，返回实际影响行数 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少分类 id");
        }
        int rows = categoryMapper.restoreById(id);
        evictFrontCache();
        return rows;
    }

    /** 批量恢复分类；ids 为空/全为 null 或超过 500 条时抛 BusinessException */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        int rows = categoryMapper.restoreByIds(normalizeIds(ids));
        evictFrontCache();
        return rows;
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误（而不是 500）。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条分类");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条分类");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 条");
        }
        return safeIds;
    }
}
