package com.culture.mapper;


import com.culture.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface PermissionMapper {

    List<Permission> listPermissionByUserId(Long userid);
    //查找所有的权限
    List<Permission> findAllPermisisons();

    // ========================================================================
    // 后台「菜单/目录角色权限配置」相关（增量新增，对应 PermissionMapper.xml 末尾）
    // ========================================================================

    /**
     * 菜单/目录扁平列表（deleted = 0，按 pid, sort, id 排序）。
     * 每条记录的键：id / name / url / icon / pid / sort / status
     */
    List<Map<String, Object>> listAllMenusFlat();

    /**
     * 所有启用角色（deleted = 0 且 status = 1）。
     * 每条记录的键：id / code / name / description
     */
    List<Map<String, Object>> listEnabledRoles();

    /**
     * 所有角色已授予的菜单 id（过滤权限行 deleted=0、menu_id 非空、菜单 deleted=0）。
     * 每条记录的键：roleId / menuId
     */
    List<Map<String, Object>> listAllRoleMenuIds();

    /** 某角色已授予的菜单 id 列表（防自锁判断用） */
    List<Long> listMenuIdsByRoleId(@Param("roleId") Long roleId);

    /** 角色是否存在（deleted = 0），返回 0 或 1 */
    Long countRoleById(@Param("roleId") Long roleId);

    /** 按 id 批量取有效菜单（deleted = 0），键：id / name / sort；id 为空集合时不会被调用 */
    List<Map<String, Object>> listMenusByIds(@Param("ids") List<Long> ids);

    /** 把这些菜单下被逻辑删除的权限行恢复（deleted 1 → 0，status 置 1），返回影响行数 */
    int restorePermissionsByMenuIds(@Param("ids") List<Long> ids);

    /** 这些菜单中还没有「有效权限行」的菜单 id（deleted = 0 的权限行不存在） */
    List<Long> listMenuIdsWithoutPermission(@Param("ids") List<Long> ids);

    /** 为菜单新建权限行：name/title 用菜单名，sort 用菜单 sort，status=1，deleted=0 */
    int insertMenuPermission(@Param("name") String name,
                            @Param("title") String title,
                            @Param("menuId") Long menuId,
                            @Param("sort") Integer sort);

    /** 取回这些菜单对应的权限 id（同一菜单多行时取最小 id），键：menuId / permissionId */
    List<Map<String, Object>> listPermissionIdsByMenuIds(@Param("ids") List<Long> ids);

    /**
     * 删除某角色的「菜单类」授权：只删 sys_permission.menu_id 非空的关联行，
     * menu_id 为 NULL 的按钮/操作类权限（如 user:add）不受影响。
     */
    int deleteRoleMenuPermissions(@Param("roleId") Long roleId);

    /** 批量授予角色权限（permissionIds 为空集合时不会被调用） */
    int insertRolePermissions(@Param("roleId") Long roleId,
                              @Param("permissionIds") List<Long> permissionIds);

    /** 按 url 查菜单 id（取最小 id，deleted = 0），查不到返回 null */
    Long findMenuIdByUrl(@Param("url") String url);

    // ========================================================================
    // 后台「按钮级权限」相关（增量新增，对应 PermissionMapper.xml 末尾）
    // 约定：menu_id IS NULL 的行 = 按钮/操作级权限；menu_id 非空 = 菜单级权限。
    // ========================================================================

    /**
     * 按钮级权限扁平列表（menu_id IS NULL 且 deleted = 0，按 sort,id 排序）。
     * 每条记录的键：id / name / title / sort
     */
    List<Map<String, Object>> listAllButtonsFlat();

    /** 某角色已授予的按钮级权限 id（只算 menu_id IS NULL 且 deleted = 0 的行） */
    List<Long> listButtonIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 按 id 批量取权限元信息（<b>不过滤 deleted</b>），键：id / name / menuId / deleted。
     * 用于区分「不存在」「已逻辑删除」「菜单类（menu_id 非空）」三种非法入参。
     */
    List<Map<String, Object>> listPermissionMetaByIds(@Param("ids") List<Long> ids);

    /**
     * 删除某角色的「按钮类」授权：只删 sys_permission.menu_id IS NULL 的关联行，
     * menu_id 非空的菜单类授权不受影响（与 deleteRoleMenuPermissions 互补）。
     */
    int deleteRoleButtonPermissions(@Param("roleId") Long roleId);

}
