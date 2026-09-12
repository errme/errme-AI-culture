package com.culture.service.impl;


import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.entity.User;
import com.culture.mapper.RoleMapper;
import com.culture.query.RoleQuery;
import com.culture.service.RoleService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 角色服务实现。
 *
 * <p>除旧库遗留方法外，新增后台「角色管理」（/api/admin/role/**）所需的
 * {@link #listRoles()}、{@link #saveRole} 与 {@link #deleteRole}。</p>
 *
 * <p><b>写操作的事务</b>：类上是 {@code @Transactional(readOnly = true, propagation = SUPPORTS)}，
 * 新增的写方法都单独标注了 {@code @Transactional(rollbackFor = Exception.class)}；
 * 方法级注解在 Spring 中优先于类级注解，且 readOnly 默认为 false，因此写操作走读写事务。</p>
 */
@Service
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public class RoleServiceImpl implements RoleService {

    /** 超级管理员角色 id（不可改 code、不可停用、不可删除） */
    private static final Long SUPER_ROLE_ID = Long.valueOf(1L);

    /** 角色编码格式：字母/数字/下划线/中划线，长度 2-32 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,32}$");

    /** sys_role.name varchar(30) */
    private static final int MAX_NAME_LEN = 30;
    /** sys_role.code varchar(30)：比需求里的 {2,32} 更紧，避免超列宽写库报错 */
    private static final int MAX_CODE_LEN = 30;
    /** sys_role.description varchar(255) */
    private static final int MAX_DESC_LEN = 255;

    @Autowired
    private RoleMapper roleMapper;

    /**
     * 后台菜单缓存（按用户，key 带全局版本号）：角色 / 用户角色 / 角色权限任何变化都要 bump 版本号，
     * 这样所有相关用户的菜单缓存立即失效（不必知道「谁有这个角色」）。失败只记日志，不影响写结果。
     */
    @Autowired
    private com.culture.service.CacheService cacheService;

    private void bumpMenuCache() {
        if (cacheService != null) {
            cacheService.bumpMenuEpoch();
        }
    }

    @Override
    public List<Role> listRoleByUserId(Long userid) {
        return roleMapper.listRoleByUserId(userid);
    }


    @Override
    public PageList listpage(RoleQuery userQuery) {
        PageList pageList = new PageList();
        //查询总的条数
        Long total = roleMapper.queryTotal(userQuery);
        List<User> users = roleMapper.queryData(userQuery);
        pageList.setTotal(total);
        pageList.setRows(users);
        //分页查询的数据
        return pageList;
    }


    @Override
    public List<Role> queryAll() {
        return roleMapper.queryAll();
    }

    //  insert into t_user_role(userid,roleid) values(xx,xxx),(xx,yy)
    @Override
    @Transactional
    public void addUserRole(String userId, List roleIds) {
        List userRolesList = new ArrayList();
        for (Object roleId : roleIds) {
            Map mp = new HashMap();
            mp.put("userId", userId);
            mp.put("roleId", roleId);
            userRolesList.add(mp);
        }
        //先删除用户角色
        roleMapper.deleteUserRole(Long.parseLong(userId));
        //添加用户角色
        roleMapper.addUserRole(userRolesList);
        // 用户角色变了 -> 该用户看到的菜单可能变，bump 版本号让所有人的菜单缓存失效
        bumpMenuCache();
    }

    @Override
    @Transactional
    public void addRolePermission(String roleId, List permissionIds) {

        List rolePermissionList = new ArrayList();
        for (Object permissionId : permissionIds) {
            Map mp = new HashMap();
            mp.put("roleId", roleId);
            mp.put("permissionId", permissionId);
            rolePermissionList.add(mp);
        }
        //先删除角色对应的权限
        roleMapper.deleteRolePermission(Long.parseLong(roleId));
        //添加角色对应的权限
        roleMapper.addRolePermission(rolePermissionList);
        // 角色权限变了 -> 该角色下所有用户的菜单可能变
        bumpMenuCache();
    }

    // ========================================================================
    // 后台「角色管理」（增量新增）
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> rows = roleMapper.listRolesWithUserCount();
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            // resultType="map" 时 MyBatis 不写入 null 列，这里统一补齐固定键
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", toLong(row.get("id")));
            item.put("code", toStr(row.get("code")));
            item.put("name", toStr(row.get("name")));
            item.put("description", toStr(pick(row, "description", "desc")));
            item.put("sort", toInteger(row.get("sort")));
            item.put("status", toInteger(row.get("status")));
            Object userCountRaw = row.get("userCount");
            if (userCountRaw == null) {
                userCountRaw = row.get("user_count");
            }
            if (userCountRaw == null) {
                userCountRaw = row.get("usercount");
            }
            Long userCount = toLong(userCountRaw);
            item.put("userCount", userCount == null ? Long.valueOf(0L) : userCount);
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveRole(Long id, String code, String name, String description,
                                       Integer sort, Integer status) {
        boolean create = (id == null);

        // 0. 修改时角色必须存在
        Map<String, Object> current = null;
        if (!create) {
            current = roleMapper.getRoleById(id);
            if (current == null) {
                throw new BusinessException("角色不存在或已删除：" + id);
            }
        }

        // 1. code：必填 + 格式 + 列宽
        String newCode = trimToNull(code);
        if (newCode == null) {
            throw new BusinessException("角色编码 code 不能为空");
        }
        if (!CODE_PATTERN.matcher(newCode).matches()) {
            throw new BusinessException("角色编码 code 只能包含字母、数字、下划线、中划线，长度 2-32：" + newCode);
        }
        if (newCode.length() > MAX_CODE_LEN) {
            throw new BusinessException("角色编码 code 最长 " + MAX_CODE_LEN + " 个字符：" + newCode);
        }

        // 2. name：必填 + 列宽
        String newName = trimToNull(name);
        if (newName == null) {
            throw new BusinessException("角色名称 name 不能为空");
        }
        if (newName.length() > MAX_NAME_LEN) {
            throw new BusinessException("角色名称 name 最长 " + MAX_NAME_LEN + " 个字符");
        }

        // 3. description：列宽（允许为空）
        String newDesc = trimToNull(description);
        if (newDesc != null && newDesc.length() > MAX_DESC_LEN) {
            throw new BusinessException("角色描述 description 最长 " + MAX_DESC_LEN + " 个字符");
        }

        // 4. sort / status 默认值与取值
        Integer newSort = (sort == null) ? Integer.valueOf(0) : sort;
        Integer newStatus = (status == null) ? Integer.valueOf(1) : status;
        if (newStatus.intValue() != 0 && newStatus.intValue() != 1) {
            throw new BusinessException("status 只能是 0（停用）或 1（启用）");
        }

        // 5. 保护：超级管理员（id=1）不允许改 code、不允许停用
        if (SUPER_ROLE_ID.equals(id)) {
            String oldCode = trimToNull(toStr(current.get("code")));
            if (oldCode != null && !oldCode.equalsIgnoreCase(newCode)) {
                throw new BusinessException("超级管理员角色（id=1）的编码不允许修改（当前为 " + oldCode + "）");
            }
            if (newStatus.intValue() == 0) {
                throw new BusinessException("超级管理员角色（id=1）不允许停用");
            }
        }

        // 6. 保护：不允许停用「最后一个启用中的管理员角色」（防自锁）
        if (newStatus.intValue() == 0 && isAdminRole(id, newCode, newName)) {
            Long otherAdmin = roleMapper.countEnabledAdminRoles(id);
            if (otherAdmin == null || otherAdmin.longValue() == 0L) {
                throw new BusinessException("不能停用最后一个启用中的管理员角色（" + newName
                        + "），否则将没有人能继续配置权限");
            }
        }

        // 7. code / name 唯一（忽略大小写；修改时排除自己）
        Long codeOwner = roleMapper.findRoleIdByCode(newCode, id);
        if (codeOwner != null) {
            throw new BusinessException("角色编码已存在：" + newCode);
        }
        Long nameOwner = roleMapper.findRoleIdByName(newName, id);
        if (nameOwner != null) {
            throw new BusinessException("角色名称已存在：" + newName);
        }

        // 8. 落库（同时更新 / 新增）
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("code", newCode);
        row.put("name", newName);
        row.put("description", newDesc);
        row.put("sort", newSort);
        row.put("status", newStatus);

        if (create) {
            roleMapper.insertRole(row);
            if (row.get("id") == null) {
                // 极端情况下驱动未回填自增主键，按 code 兜底查一次（同事务内可见）
                row.put("id", roleMapper.findRoleIdByCode(newCode, null));
            }
        } else {
            int updated = roleMapper.updateRole(row);
            if (updated == 0) {
                throw new BusinessException("角色不存在或已删除：" + id);
            }
        }
        // 角色本身（名称/编码/状态/排序）变了，菜单渲染可能受影响：保守地失效全部菜单缓存
        bumpMenuCache();
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteRole(Long id) {
        if (id == null) {
            throw new BusinessException("id 不能为空");
        }
        Map<String, Object> current = roleMapper.getRoleById(id);
        if (current == null) {
            throw new BusinessException("角色不存在或已删除：" + id);
        }

        // 保护 1：超级管理员不可删
        if (SUPER_ROLE_ID.equals(id)) {
            throw new BusinessException("超级管理员角色（id=1）不允许删除");
        }

        // 保护 2：角色下还有用户（sys_user_role 有引用）时不允许删
        Long userCount = roleMapper.countUsersByRoleId(id);
        long users = (userCount == null) ? 0L : userCount.longValue();
        if (users > 0L) {
            throw new BusinessException("该角色下还有 " + users + " 个用户，请先调整这些用户的角色后再删除（仅统计未删除的用户）");
        }

        // 保护 3：不允许删掉最后一个启用中的管理员角色（防自锁）
        Integer currentStatus = toInteger(current.get("status"));
        if (currentStatus != null && currentStatus.intValue() == 1
                && isAdminRole(id, trimToNull(toStr(current.get("code"))), trimToNull(toStr(current.get("name"))))) {
            Long otherAdmin = roleMapper.countEnabledAdminRoles(id);
            if (otherAdmin == null || otherAdmin.longValue() == 0L) {
                throw new BusinessException("不能删除最后一个启用中的管理员角色，否则将没有人能继续配置权限");
            }
        }

        // 清理该角色的全部 sys_role_permission 关联（菜单类 + 按钮类），再逻辑删除角色行
        int permissionCount = roleMapper.deleteRoleAllPermissions(id);
        int count = roleMapper.deleteRoleLogically(id);
        if (count == 0) {
            throw new BusinessException("角色不存在或已删除：" + id);
        }
        bumpMenuCache();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", Integer.valueOf(count));
        data.put("permissionCount", Integer.valueOf(permissionCount));
        return data;
    }

    // ==================== 小工具（增量新增） ====================

    /**
     * 是否算「管理员角色」：id=1、code=admin（忽略大小写）、或 name=管理员。
     * 用于「最后一个启用中的管理员角色」防自锁判断。
     */
    private boolean isAdminRole(Long id, String code, String name) {
        if (SUPER_ROLE_ID.equals(id)) {
            return true;
        }
        if (code != null && "admin".equalsIgnoreCase(code.trim())) {
            return true;
        }
        return name != null && "管理员".equals(name.trim());
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 兼容 MyBatis map 结果里可能出现的 Long / Integer / BigInteger / String */
    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Long.valueOf(((Number) v).longValue());
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return Integer.valueOf(((Number) v).intValue());
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toStr(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 取 map 结果里的列值：先按驼峰键，再退化到下划线键。
     * （resultType="map" 时 MyBatis 用列标签做键；这里兜底不同驱动下的键名差异）
     */
    private Object pick(Map<String, Object> row, String camelKey, String snakeKey) {
        Object v = row.get(camelKey);
        return v != null ? v : row.get(snakeKey);
    }
}
