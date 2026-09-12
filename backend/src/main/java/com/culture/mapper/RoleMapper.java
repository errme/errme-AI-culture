package com.culture.mapper;


import com.culture.entity.Role;
import com.culture.entity.User;
import com.culture.query.RoleQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface RoleMapper {

    List<Role> listRoleByUserId(Long userid);

    Long queryTotal(RoleQuery userQuery);

    List<User> queryData(RoleQuery userQuery);


    //查询所有的角色
    List<Role> queryAll();
    //添加用户角色
    void addUserRole(List userRole);

    //删除用户角色
    void deleteUserRole(Long userId);
    //删除角色对应的权限
    void deleteRolePermission(Long parseLong);
    //添加角色对应的权限
    void addRolePermission(List rolePermissionList);

    // ========================================================================
    // 后台「角色管理」相关（增量新增，对应 RoleMapper.xml 末尾）
    // ========================================================================

    /**
     * 所有未删除角色 + 各角色用户数（一次 group by 查完，避免 N+1）。
     * 每条记录的键：id / code / name / description / sort / status / userCount
     */
    List<Map<String, Object>> listRolesWithUserCount();

    /** 某角色明细（deleted = 0），键：id / code / name / description / sort / status；查不到返回 null */
    Map<String, Object> getRoleById(@Param("id") Long id);

    /** code 是否已被其它未删除角色占用（忽略大小写），返回占用者 id，未占用返回 null */
    Long findRoleIdByCode(@Param("code") String code, @Param("excludeId") Long excludeId);

    /** name 是否已被其它未删除角色占用，返回占用者 id，未占用返回 null */
    Long findRoleIdByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    /**
     * 启用中的「管理员角色」数量（id=1 或 code=admin 或 name=管理员），用于防自锁。
     * excludeId 非空时统计的是「排除该角色之后还剩几个」。
     */
    Long countEnabledAdminRoles(@Param("excludeId") Long excludeId);

    /** 新增角色；useGeneratedKeys 会把自增 id 回填到入参 map 的 id 键 */
    int insertRole(Map<String, Object> role);

    /** 修改角色基础信息（不改 deleted / created_at），返回影响行数 */
    int updateRole(Map<String, Object> role);

    /** 该角色下的用户数（sys_user_role 引用数，用于删除前的保护判断） */
    Long countUsersByRoleId(@Param("roleId") Long roleId);

    /** 逻辑删除角色（deleted 置 1），返回影响行数 */
    int deleteRoleLogically(@Param("id") Long id);

    /** 清理该角色的全部权限关联（菜单类 + 按钮类），返回删除行数 */
    int deleteRoleAllPermissions(@Param("roleId") Long roleId);
}
