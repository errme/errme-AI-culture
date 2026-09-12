package com.culture.mapper;

import com.culture.entity.Category;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 分类 Mapper（新库 biz_category，列 name 映射回 categoryName 属性）。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {

    //查询所有分类目录
    List<Category> queryAll();

    @Insert("insert into biz_category(name) values(#{categoryName})")
    void addSave(Category category);

    @Update("update biz_category set name=#{categoryName} where id=#{id}")
    void editSaveCategory(Category category);

    /** 逻辑删除 */
    @Update("update biz_category set deleted=1 where id=#{id}")
    void deleteCategory(Long id);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的分类：只把 deleted 改回 0，返回实际影响行数。
     * where 带 deleted=1，因此对正常记录调用不会产生任何影响行数（幂等、不会误改其它字段）。
     */
    @Update("update biz_category set deleted=0 where id=#{id} and deleted=1")
    int restoreById(@Param("id") Long id);

    /** 批量恢复被逻辑删除的分类；只影响 deleted=1 的行，返回实际影响行数（ids 由 Service 保证非空且 ≤ 500） */
    @Update("<script>" +
            "update biz_category set deleted=0 " +
            "where deleted=1 and id in " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    int restoreByIds(@Param("ids") List<Long> ids);
}
