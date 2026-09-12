package com.culture.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Role {
    private Long id;
    private String name;
    private String sn;
    private String desc;
    List<Permission> permissions = new ArrayList();

    /**
     * 非表字段：批量按用户查角色时携带所属用户 id（不对外输出 JSON）。
     *
     * <p>用途：用户列表原本用 MyBatis 的
     * {@code <collection property="roles" column="id" select="getRoleByUserId"/>}
     * 逐行查询角色，形成 N+1（一页 N 个用户就是 1+N 次 SQL，pageSize 放大后更明显）。
     * 现在改为「一次 {@code where user_id in (...)} 批量查回 + 内存分组」，
     * 需要一个字段承载分组键，与 {@code Tag.cultureId} 是同一套做法。</p>
     */
    @JsonIgnore
    private Long userId;
}
