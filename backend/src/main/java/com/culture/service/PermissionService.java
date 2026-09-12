package com.culture.service;


import com.culture.entity.Permission;

import java.util.List;
import java.util.Map;

public interface PermissionService {

    /** 「菜单权限」菜单的 url，与 docs/sql/05_menu_permission.sql 保持一致（防自锁判断用） */
    String PERMISSION_MENU_URL = "/permission/index";

    //根据用户查询权限
    List<Permission> listPermissionByUserId(Long userid);
    //查找所有的权限
     List<Permission> findAllPermisisons();

    // ========================================================================
    // 后台「菜单/目录角色权限配置」（增量新增）
    // ========================================================================

    /**
     * 菜单/目录扁平列表，供前端勾选树使用。
     * 每项固定键：id / name / url / icon / pid / sort / status（只含 deleted = 0，按 pid,sort,id 排序）
     */
    List<Map<String, Object>> listMenus();

    /**
     * 所有启用角色 + 每个角色已授予的 menuId 列表。
     * 每项固定键：id / code / name / description / menuIds
     */
    List<Map<String, Object>> listRolesWithMenuIds();

    /**
     * 全量覆盖某角色的「菜单类」权限（事务）：
     * 校验角色与菜单 → 按 menu_id 查找/恢复/新建 sys_permission 行 →
     * 删除该角色已有的菜单类授权（保留 menu_id 为 NULL 的按钮/操作类权限）→ 按新 menuIds 重新授权。
     *
     * @return 实际授予的菜单数
     */
    int grantMenus(Long roleId, List<Long> menuIds);

    /** 某角色已授予的菜单 id（防自锁判断用） */
    List<Long> listMenuIdsByRoleId(Long roleId);

    /** 按 url 查菜单 id（防自锁判断用；查不到返回 null） */
    Long findMenuIdByUrl(String url);

    // ========================================================================
    // 后台「按钮级权限」（增量新增）
    // 约定：sys_permission.menu_id IS NULL = 按钮/操作级权限；menu_id 非空 = 菜单级权限。
    // ========================================================================

    /**
     * 按钮级权限配置数据：全部可配置按钮 + 某角色已授予的按钮 id。
     * 固定键：{@code all: [{id,name,title,sort}], granted: [2,3,4]}。
     * roleId 为空或角色不存在时抛业务错误（→400）。
     */
    Map<String, Object> listButtons(Long roleId);

    /**
     * 全量覆盖某角色的「按钮类」授权（事务）：
     * 校验角色 → 校验每个 permissionId 都是 <b>menu_id IS NULL 且未删除</b> 的权限
     * （菜单类、已删除、不存在的一律 400，避免与菜单权限互相覆盖）→
     * 去重后先删该角色的按钮类旧授权（菜单类保留），再插入新集合。
     *
     * @return 实际授予的按钮数
     */
    int grantButtons(Long roleId, List<Long> permissionIds);

}
