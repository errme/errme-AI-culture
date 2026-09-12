package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Announcement;
import com.culture.mapper.AnnouncementMapper;
import com.culture.query.AnnouncementQuery;
import com.culture.service.AnnouncementService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class AnnouncementServiceImpl implements AnnouncementService {

    /** 单次批量操作上限（与前端「一次最多选 500 条」对齐） */
    private static final int MAX_BATCH_SIZE = 500;

    @Autowired
    private AnnouncementMapper announcementMapper;

    /** 前台首页聚合缓存（/api/home 含公告列表）：公告一变就失效，保证管理员的改动立刻可见 */
    @Autowired
    private com.culture.service.CacheService cacheService;

    private void evictHome() {
        if (cacheService != null) {
            cacheService.evictHome();
        }
    }

    @Override
    public List<Announcement> findAll() {
        return announcementMapper.findAll();
    }

    @Override
    public List<Announcement> queryAll() {
        return announcementMapper.queryAll();
    }

    @Override
    public PageList listpage(AnnouncementQuery announcementQuery) {
        PageList pageList = new PageList();

        Long total = announcementMapper.queryTotal(announcementQuery);
        List<Announcement> announcements = announcementMapper.queryData(announcementQuery);

        pageList.setTotal(total);
        pageList.setRows(announcements);
        return pageList;

    }

    @Override
    public void addAnnouncement(Announcement announcement) {
        announcement.setCreateTime(new Date());

        announcementMapper.addAnnouncement(announcement);
        evictHome();
    }

    @Override
    public void editAnnouncement(Announcement announcement) {

        announcementMapper.editAnnouncement(announcement);
        evictHome();
    }

    @Override
    public void deleteAnnouncement(Long id) {

        announcementMapper.deleteAnnouncement(id);
        evictHome();
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /** 恢复单条公告：只把 deleted 改回 0，返回实际影响行数 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少公告 id");
        }
        int rows = announcementMapper.restoreById(id);
        evictHome();
        return rows;
    }

    /** 批量恢复公告；ids 为空/全为 null 或超过 500 条时抛 BusinessException */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        int rows = announcementMapper.restoreByIds(normalizeIds(ids));
        evictHome();
        return rows;
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误（而不是 500）。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条公告");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条公告");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 条");
        }
        return safeIds;
    }
}
