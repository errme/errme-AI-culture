package com.culture.service;



import com.culture.entity.Role;
import com.culture.query.RoleQuery;
import com.culture.util.PageList;

import java.util.List;
import java.util.Map;

public interface RoleService {

    List<Role> listRoleByUserId(Long userid);

    PageList listpage(RoleQuery roleQuery);

    //查询所有角色
    List<Role> queryAll();
    //添加角色
    void addUserRole(String userId, List roleIds);
    //添加角色对应的权限
    void addRolePermission(String roleId, List permissionIds);

    // ========================================================================
    // 后台「角色管理」（增量新增）
    // ========================================================================

    /**
     * 角色列表（只含 deleted = 0，按 sort,id 排序）。
     * 每项固定键：id / code / name / description / sort / status / userCount
     */
    List<Map<String, Object>> listRoles();

    /**
     * 新增（id 为空）或修改（id 非空）角色，返回落库后的
     * { id, code, name, description, sort, status }。
     *
     * <p>校验（不通过抛 {@link com.culture.auth.service.BusinessException} → 400）：
     * code 必填、唯一（忽略大小写）、且匹配 {@code [a-zA-Z0-9_-]{2,32}} 并不得超过列宽 30；
     * name 必填、唯一；id=1「超级管理员」的 code 不允许改、status 不允许置 0；
     * 不允许把「最后一个启用中的管理员角色」停用（防自锁）。</p>
     */
    Map<String, Object> saveRole(Long id, String code, String name, String description, Integer sort, Integer status);

    /**
     * 逻辑删除角色（deleted=1）并清理其全部 sys_role_permission 关联，返回
     * { count: 逻辑删除的角色数(0/1), permissionCount: 清理掉的授权行数 }。
     *
     * <p>保护：id=1 不可删；角色下仍有 sys_user_role 引用时拒绝并提示用户数。</p>
     */
    Map<String, Object> deleteRole(Long id);
}
