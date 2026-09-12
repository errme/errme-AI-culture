package com.culture.service.impl;


import com.culture.auth.service.BusinessException;
import com.culture.entity.Permission;
import com.culture.mapper.PermissionMapper;
import com.culture.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 权限服务实现。
 *
 * <p>除既有方法外，新增后台「菜单/目录角色权限配置」所需的三块能力：</p>
 * <ul>
 *   <li>菜单扁平列表 / 角色及其已授予菜单（只读）；</li>
 *   <li>{@link #grantMenus(Long, List)}：全量覆盖某角色的「菜单类」权限（写，事务）。</li>
 * </ul>
 *
 * <p>按钮级权限（{@link #listButtons(Long)} / {@link #grantButtons(Long, List)}）与之互补：
 * 按钮类只认 <code>menu_id IS NULL</code> 的权限行，菜单类只认 <code>menu_id IS NOT NULL</code>，
 * 两边各自「先删本类、再插新集合」，互不覆盖。</p>
 *
 * <p><b>覆盖授权的取舍</b>：没有采用「删光该角色所有 sys_role_permission 再重建」的写法，
 * 而是只删 <code>sys_permission.menu_id IS NOT NULL</code> 的关联行；因为库里存在
 * menu_id 为 NULL 的按钮/操作类权限（如 user:add、user:delete，见 PermissionMapper.xml
 * 的 deleteRoleMenuPermissions），全删会把它们一起抹掉。</p>
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    @Autowired
    private PermissionMapper permissionMapper;

    /** 后台菜单缓存版本号：角色菜单/按钮授权变化时 bump（见 CacheService.KEY_MENU_EPOCH） */
    @Autowired
    private com.culture.service.CacheService cacheService;

    private void bumpMenuCache() {
        if (cacheService != null) {
            cacheService.bumpMenuEpoch();
        }
    }

    // ==================== 既有方法 ====================

    @Override
    public List<Permission> listPermissionByUserId(Long userid) {
        return permissionMapper.listPermissionByUserId(userid);
    }

    @Override
    public List<Permission> findAllPermisisons() {
        return permissionMapper.findAllPermisisons();
    }

    // ==================== 菜单 / 角色（只读） ====================

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMenus() {
        List<Map<String, Object>> rows = permissionMapper.listAllMenusFlat();
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            // MyBatis 对 resultType="map" 默认不写入 null 列，这里统一补齐固定键，
            // 保证前端拿到 {id,name,url,icon,pid,sort,status} 七个字段。
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", toLong(row.get("id")));
            item.put("name", toStr(row.get("name")));
            item.put("url", toStr(row.get("url")));
            item.put("icon", toStr(row.get("icon")));
            item.put("pid", toLong(row.get("pid")));
            item.put("sort", toInteger(row.get("sort")));
            item.put("status", toInteger(row.get("status")));
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRolesWithMenuIds() {
        List<Map<String, Object>> roles = permissionMapper.listEnabledRoles();
        List<Map<String, Object>> grants = permissionMapper.listAllRoleMenuIds();

        // roleId -> menuIds，两次查询后在内存里拼装，避免 N+1
        Map<Long, List<Long>> grantMap = new HashMap<>();
        if (grants != null) {
            for (Map<String, Object> grant : grants) {
                Long roleId = toLong(pick(grant, "roleId", "role_id"));
                Long menuId = toLong(pick(grant, "menuId", "menu_id"));
                if (roleId == null || menuId == null) {
                    continue;
                }
                List<Long> menuIds = grantMap.get(roleId);
                if (menuIds == null) {
                    menuIds = new ArrayList<>();
                    grantMap.put(roleId, menuIds);
                }
                menuIds.add(menuId);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        if (roles == null) {
            return result;
        }
        for (Map<String, Object> role : roles) {
            Long roleId = toLong(role.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", roleId);
            item.put("code", toStr(role.get("code")));
            item.put("name", toStr(role.get("name")));
            item.put("description", toStr(role.get("description")));
            List<Long> menuIds = grantMap.get(roleId);
            item.put("menuIds", menuIds == null ? new ArrayList<Long>() : menuIds);
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> listMenuIdsByRoleId(Long roleId) {
        if (roleId == null) {
            return new ArrayList<>();
        }
        List<Long> ids = permissionMapper.listMenuIdsByRoleId(roleId);
        return ids == null ? new ArrayList<Long>() : ids;
    }

    @Override
    @Transactional(readOnly = true)
    public Long findMenuIdByUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        return permissionMapper.findMenuIdByUrl(url);
    }

    // ==================== 全量覆盖授权（写，事务） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantMenus(Long roleId, List<Long> menuIds) {
        // 1. 角色必须存在
        if (roleId == null) {
            throw new BusinessException("roleId 不能为空");
        }
        Long roleCount = permissionMapper.countRoleById(roleId);
        if (roleCount == null || roleCount.longValue() == 0L) {
            throw new BusinessException("角色不存在：" + roleId);
        }

        // 2. menuIds 去重（保持提交顺序），并校验每个菜单都存在且未删除
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        if (menuIds != null) {
            for (Long id : menuIds) {
                if (id != null) {
                    distinct.add(id);
                }
            }
        }
        List<Long> ids = new ArrayList<>(distinct);
        if (ids.isEmpty()) {
            // 空数组 = 撤销该角色全部「菜单类」授权（按钮/操作类权限保留）
            permissionMapper.deleteRoleMenuPermissions(roleId);
            bumpMenuCache();
            return 0;
        }

        List<Map<String, Object>> menus = permissionMapper.listMenusByIds(ids);
        Map<Long, Map<String, Object>> menuMap = new LinkedHashMap<>();
        if (menus != null) {
            for (Map<String, Object> menu : menus) {
                Long menuId = toLong(menu.get("id"));
                if (menuId != null) {
                    menuMap.put(menuId, menu);
                }
            }
        }
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (!menuMap.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("菜单不存在或已删除：" + joinIds(missing));
        }

        // 3. 每个菜单对应一行 sys_permission：被逻辑删除的恢复，没有的新建（name/title 用菜单名）
        permissionMapper.restorePermissionsByMenuIds(ids);
        List<Long> withoutPermission = permissionMapper.listMenuIdsWithoutPermission(ids);
        if (withoutPermission != null) {
            for (Long menuId : withoutPermission) {
                Map<String, Object> menu = menuMap.get(menuId);
                String menuName = menu == null ? null : toStr(menu.get("name"));
                if (menuName == null || menuName.trim().isEmpty()) {
                    menuName = "menu:" + menuId;
                }
                Integer sort = menu == null ? null : toInteger(menu.get("sort"));
                permissionMapper.insertMenuPermission(menuName, menuName, menuId, sort == null ? 0 : sort);
            }
        }

        // 取回 menuId -> permissionId（同一菜单存在多行时取最小 id）
        Map<Long, Long> permissionIdMap = new HashMap<>();
        List<Map<String, Object>> permRows = permissionMapper.listPermissionIdsByMenuIds(ids);
        if (permRows != null) {
            for (Map<String, Object> perm : permRows) {
                Long menuId = toLong(pick(perm, "menuId", "menu_id"));
                Long permissionId = toLong(pick(perm, "permissionId", "permission_id"));
                if (menuId != null && permissionId != null) {
                    permissionIdMap.put(menuId, permissionId);
                }
            }
        }
        LinkedHashSet<Long> permissionIds = new LinkedHashSet<>();
        for (Long menuId : ids) {
            Long permissionId = permissionIdMap.get(menuId);
            if (permissionId == null) {
                throw new BusinessException("菜单权限行创建失败，menuId=" + menuId);
            }
            permissionIds.add(permissionId);
        }

        // 4. 覆盖授权：先删该角色的「菜单类」授权（保留 menu_id 为 NULL 的按钮/操作类权限），再按新集合插入
        permissionMapper.deleteRoleMenuPermissions(roleId);
        List<Long> toGrant = new ArrayList<>(permissionIds);
        if (!toGrant.isEmpty()) {
            permissionMapper.insertRolePermissions(roleId, toGrant);
        }
        bumpMenuCache();
        return toGrant.size();
    }

    // ==================== 按钮级权限（写，事务） ====================

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> listButtons(Long roleId) {
        if (roleId == null) {
            throw new BusinessException("roleId 不能为空");
        }
        Long roleCount = permissionMapper.countRoleById(roleId);
        if (roleCount == null || roleCount.longValue() == 0L) {
            throw new BusinessException("角色不存在：" + roleId);
        }

        List<Map<String, Object>> rows = permissionMapper.listAllButtonsFlat();
        List<Map<String, Object>> all = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                // resultType="map" 不写入 null 列，这里补齐固定键：id/name/title/sort
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", toLong(row.get("id")));
                item.put("name", toStr(row.get("name")));
                item.put("title", toStr(row.get("title")));
                item.put("sort", toInteger(row.get("sort")));
                all.add(item);
            }
        }
        List<Long> granted = permissionMapper.listButtonIdsByRoleId(roleId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("all", all);
        data.put("granted", granted == null ? new ArrayList<Long>() : granted);
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int grantButtons(Long roleId, List<Long> permissionIds) {
        // 1. 角色必须存在
        if (roleId == null) {
            throw new BusinessException("roleId 不能为空");
        }
        Long roleCount = permissionMapper.countRoleById(roleId);
        if (roleCount == null || roleCount.longValue() == 0L) {
            throw new BusinessException("角色不存在：" + roleId);
        }

        // 2. 去重（保持提交顺序），防撞 uk_role_perm
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        if (permissionIds != null) {
            for (Long pid : permissionIds) {
                if (pid != null) {
                    distinct.add(pid);
                }
            }
        }
        List<Long> ids = new ArrayList<>(distinct);

        // 3. 校验：每个 id 都必须是「menu_id IS NULL 且未逻辑删除」的按钮级权限
        if (!ids.isEmpty()) {
            List<Map<String, Object>> metas = permissionMapper.listPermissionMetaByIds(ids);
            Map<Long, Map<String, Object>> metaMap = new HashMap<>();
            if (metas != null) {
                for (Map<String, Object> meta : metas) {
                    Long pid = toLong(meta.get("id"));
                    if (pid != null) {
                        metaMap.put(pid, meta);
                    }
                }
            }
            List<Long> missing = new ArrayList<>();
            List<Long> deleted = new ArrayList<>();
            List<Long> menuType = new ArrayList<>();
            for (Long pid : ids) {
                Map<String, Object> meta = metaMap.get(pid);
                if (meta == null) {
                    missing.add(pid);
                    continue;
                }
                if (isFlagSet(meta.get("deleted"))) {
                    deleted.add(pid);
                    continue;
                }
                Long menuId = toLong(pick(meta, "menuId", "menu_id"));
                if (menuId != null) {
                    menuType.add(pid);
                }
            }
            if (!missing.isEmpty()) {
                throw new BusinessException("权限不存在：" + joinIds(missing));
            }
            if (!deleted.isEmpty()) {
                throw new BusinessException("权限已被删除：" + joinIds(deleted));
            }
            if (!menuType.isEmpty()) {
                throw new BusinessException("权限 " + joinIds(menuType)
                        + " 是菜单类权限（menu_id 非空），本接口只处理按钮级权限；菜单类请用 POST /api/admin/permission/role");
            }
        }

        // 4. 全量覆盖：先删该角色的「按钮类」旧授权（菜单类保留），再插入新集合
        permissionMapper.deleteRoleButtonPermissions(roleId);
        if (!ids.isEmpty()) {
            permissionMapper.insertRolePermissions(roleId, ids);
        }
        // 按钮授权也可能影响菜单可见性（按钮权限挂菜单下），保守 bump 一次版本号
        bumpMenuCache();
        return ids.size();
    }

    // ==================== 小工具 ====================

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
     * 0/1 标志位判断（容错 Number / String / Boolean）：非 0 视为 true。
     * 用于 sys_permission.deleted —— 不同驱动下 tinyint 可能映射成 Integer 或 Boolean。
     */
    private boolean isFlagSet(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        Integer i = toInteger(v);
        return i != null && i.intValue() != 0;
    }

    /**
     * 取 map 结果里的列值：先按驼峰键，再退化到下划线键。
     * （resultType="map" 时 MyBatis 用列标签做键；这里做一层兜底，避免不同版本/驱动下键名不一致）
     */
    private Object pick(Map<String, Object> row, String camelKey, String snakeKey) {
        Object v = row.get(camelKey);
        return v != null ? v : row.get(snakeKey);
    }

    /** 拼错误信息里的 id 列表，最多 10 个 */
    private String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size() && i < 10; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        if (ids.size() > 10) {
            sb.append("...");
        }
        return sb.toString();
    }
}
