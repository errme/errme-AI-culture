package com.culture.mapper;

import com.culture.entity.Announcement;
import com.culture.query.BaseQuery;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 公告 Mapper（新库 biz_announcement）。
 * 修正了旧代码 findAll() 误查 cul_sentence 的 bug。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /** 前台首页公告列表（旧代码误查 cul_sentence，已修正） */
    @Select("select id, title announcement, created_at createTime from biz_announcement where deleted=0")
    List<Announcement> findAll();

    /** 最新一条公告 */
    @Select("select id, title announcement, created_at createTime from biz_announcement " +
            "where deleted=0 order by created_at desc limit 1")
    List<Announcement> queryAll();

    @Insert("insert into biz_announcement(title, content, created_at) values (#{announcement},#{announcement},#{createTime})")
    void addAnnouncement(Announcement announcement);

    @Update("update biz_announcement set title=#{announcement} where id=#{id}")
    void editAnnouncement(Announcement announcement);

    /** 逻辑删除 */
    @Update("update biz_announcement set deleted=1 where id=#{id}")
    void deleteAnnouncement(Long id);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的公告：只把 deleted 改回 0（title/content 等字段一律不动），
     * 返回实际影响行数；where 带 deleted=1，对正常记录调用返回 0。
     */
    @Update("update biz_announcement set deleted=0 where id=#{id} and deleted=1")
    int restoreById(@Param("id") Long id);

    /** 批量恢复被逻辑删除的公告；只影响 deleted=1 的行，返回实际影响行数（ids 由 Service 保证非空且 ≤ 500） */
    @Update("<script>" +
            "update biz_announcement set deleted=0 " +
            "where deleted=1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int restoreByIds(@Param("ids") List<Long> ids);
}
