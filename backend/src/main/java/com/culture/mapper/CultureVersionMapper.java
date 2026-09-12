package com.culture.mapper;

import com.culture.entity.CultureVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 内容版本历史 Mapper（biz_culture_version）。
 *
 * <p>SQL 见 resources/com/culture/mapper/CultureVersionMapper.xml
 * （mapper-locations 是 classpath:com/culture/mapper/*.xml，新文件会被自动加载）。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link #queryPage} <b>不查 content 全文</b>，只查 CHAR_LENGTH(content) 作为 contentLength；</li>
 *   <li>{@link #countRecentInCurrentMinute} 用数据库时间（NOW()）判断，避免应用与数据库时钟不一致。</li>
 * </ul>
 */
@Mapper
public interface CultureVersionMapper {

    /**
     * 写入一条版本快照。created_at 交给数据库默认值 CURRENT_TIMESTAMP，
     * 保证与 {@link #countRecentInCurrentMinute} 的判断使用同一套时钟。
     *
     * @return 影响行数（正常为 1）
     */
    int insert(CultureVersion version);

    /** 按版本 id 查单条（<b>含 content 全文</b>，用于对比/预览/回滚） */
    CultureVersion findById(@Param("id") Long id);

    /** 某个内容的版本总数（版本列表分页用） */
    Long countByCultureId(@Param("cultureId") Long cultureId);

    /**
     * 某个内容的版本分页（按 id 倒序 = 时间倒序）。
     * <b>不返回 content 全文</b>，只返回 contentLength。
     */
    List<CultureVersion> queryPage(@Param("cultureId") Long cultureId,
                                   @Param("offset") int offset,
                                   @Param("pageSize") int pageSize);

    /**
     * 当前<b>自然分钟</b>内该内容已有几条快照（同一分钟去重用）。
     * 用 DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:00') 把当前时间截断到分钟，
     * 因此「同一分钟内重复保存只保留一条」是自然分钟窗口，不是滑动 60 秒窗口。
     *
     * @return 已有条数；> 0 表示本分钟已经写过快照，本次跳过
     */
    int countRecentInCurrentMinute(@Param("cultureId") Long cultureId);
}
