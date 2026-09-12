package com.culture.service;


import com.culture.entity.Category;
import com.culture.query.CategoryQuery;
import com.culture.util.PageList;

import java.util.List;


public interface CategoryService {

    //查询所有分类
    List<Category> queryAll();

    //分页查询方法
    PageList listpage(CategoryQuery categoryQuery);

    void addSave(Category category);

    void editSaveCategory(Category category);

    void deleteCategory(Long id);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的分类（deleted 1 → 0，只改这一列）。
     * 权限校验与删除一致，由控制层（/api/admin/category/restore，要求管理员）负责。
     *
     * @param id 分类 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的分类。
     *
     * @param ids 分类 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数
     */
    int restoreBatch(List<Long> ids);
}
