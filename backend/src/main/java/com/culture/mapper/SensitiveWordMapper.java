package com.culture.mapper;

import com.culture.entity.SensitiveWord;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 评论敏感词 Mapper（新库 biz_sensitive_word，注解 SQL 风格）。
 *
 * <p>读路径只有两条：① {@link #findEnabled()} 取启用词表（会被
 * {@link com.culture.service.impl.SensitiveWordServiceImpl} 做 60 秒进程内缓存）；
 * ② 后台分页 queryTotal / queryData。</p>
 */
@Mapper
public interface SensitiveWordMapper {

    /**
     * 启用且未删除的词（命中判定用）。
     * 只取匹配需要的三列，词表很小时缓存成本可忽略；
     * 命中「大小写不敏感包含」只需要 word / action。
     */
    @Select("select id, word, action from biz_sensitive_word " +
            "where enabled = 1 and deleted = 0 order by id")
    List<SensitiveWord> findEnabled();

    /** 后台分页：总数（keyword 模糊匹配词本身或备注） */
    @Select("<script>" +
            "select count(*) from biz_sensitive_word where deleted = 0 " +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (word like concat('%', #{keyword}, '%') or remark like concat('%', #{keyword}, '%'))" +
            "</if>" +
            "</script>")
    Long queryTotal(@Param("keyword") String keyword);

    /** 后台分页：数据（按 id 倒序，新建的排在前面） */
    @Select("<script>" +
            "select id, word, action, enabled, remark, created_at createTime, deleted " +
            "from biz_sensitive_word where deleted = 0 " +
            "<if test=\"keyword != null and keyword != ''\">" +
            " and (word like concat('%', #{keyword}, '%') or remark like concat('%', #{keyword}, '%'))" +
            "</if>" +
            " order by id desc limit #{offset}, #{pageSize}" +
            "</script>")
    List<SensitiveWord> queryData(@Param("keyword") String keyword,
                                  @Param("offset") int offset,
                                  @Param("pageSize") int pageSize);

    /** 按主键查询（仅未删除） */
    @Select("select id, word, action, enabled, remark, created_at createTime, deleted " +
            "from biz_sensitive_word where id = #{id} and deleted = 0")
    SensitiveWord findById(Long id);

    /**
     * 按词查询（<b>不过滤 deleted</b>）。
     * uk_word 是唯一键，被逻辑删除的词仍占用名称；返回它便于「复活」而不是插入时撞唯一键。
     */
    @Select("select id, word, action, enabled, remark, created_at createTime, deleted " +
            "from biz_sensitive_word where word = #{word} limit 1")
    SensitiveWord findByWord(String word);

    /** 新增（created_at 由库默认值填充） */
    @Insert("insert into biz_sensitive_word(word, action, enabled, remark) " +
            "values(#{word}, #{action}, #{enabled}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SensitiveWord sensitiveWord);

    /** 更新词本身（命中处理/启停/备注），只影响未删除的行 */
    @Update("update biz_sensitive_word set word = #{word}, action = #{action}, " +
            "enabled = #{enabled}, remark = #{remark} where id = #{id} and deleted = 0")
    int update(SensitiveWord sensitiveWord);

    /** 复活被逻辑删除的词（同名词重复新增时复用旧行，同时覆盖 action/enabled/remark） */
    @Update("update biz_sensitive_word set word = #{word}, action = #{action}, " +
            "enabled = #{enabled}, remark = #{remark}, deleted = 0 where id = #{id}")
    int revive(SensitiveWord sensitiveWord);

    /** 逻辑删除；where 带 deleted=0，重复删除返回 0 */
    @Update("update biz_sensitive_word set deleted = 1 where id = #{id} and deleted = 0")
    int logicalDelete(Long id);

    /** 批量逻辑删除；返回实际更新条数（ids 由 Service 保证非空且 ≤ 500） */
    @Update("<script>" +
            "update biz_sensitive_word set deleted = 1 " +
            "where deleted = 0 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int batchLogicalDelete(@Param("ids") List<Long> ids);
}
