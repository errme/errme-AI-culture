package com.culture.service;


import com.culture.entity.Announcement;
import com.culture.query.AnnouncementQuery;
import com.culture.util.PageList;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AnnouncementService {

    List<Announcement> findAll();

    List<Announcement> queryAll();

    PageList listpage(AnnouncementQuery announcementQuery);

    void addAnnouncement(Announcement announcement);


    void editAnnouncement(Announcement announcement);

    void deleteAnnouncement(Long id);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的公告（deleted 1 → 0，只改这一列）。
     * 权限校验与删除一致，由控制层（/api/admin/announcement/restore，要求管理员）负责。
     *
     * @param id 公告 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的公告。
     *
     * @param ids 公告 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数
     */
    int restoreBatch(List<Long> ids);

}
