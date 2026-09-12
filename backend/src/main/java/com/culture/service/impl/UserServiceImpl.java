package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
import com.culture.entity.Role;
import com.culture.entity.User;
import com.culture.mapper.UserMapper;
import com.culture.query.UserQuery;
import com.culture.service.UserService;
import com.culture.util.PageList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户服务实现。
 */
@Service
public class UserServiceImpl implements UserService {

    /** 单次批量操作上限（与前端「一次最多选 500 条」对齐） */
    private static final int MAX_BATCH_SIZE = 500;

    @Autowired
    private UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public User findUserByUserName(String username) {
        return userMapper.findUserByUserName(username);
    }

    @Override
    public User findByEmail(String email) {
        return userMapper.findByEmail(email);
    }

    @Override
    public User findById(Long id) {
        return userMapper.findById(id);
    }

    @Override
    public int updateLastLoginAt(Long id, Date time) {
        return userMapper.updateLastLoginAt(id, time);
    }

    @Override
    public int updatePassword(Long id, String password) {
        // 统一 BCrypt 加密后落库
        return userMapper.updatePassword(id, passwordEncoder.encode(password));
    }

    @Override
    public String findUserById(Long id) {
        return userMapper.findUserById(id);
    }

    @Override
    public List<User> queryAll() {
        return userMapper.queryAll();
    }

    @Override
    public Integer addUser(User user) {
        // 设置创建时间
        user.setCreateTime(new Date());
        // 加密
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userMapper.addUser(user);
    }

    @Override
    public void updateUserHeadImg(User user) {
        userMapper.updateUserHeadImg(user);
    }

    @Override
    public PageList listpage(UserQuery userQuery) {
        PageList pageList = new PageList();
        // 查询总的条数
        Long total = userMapper.queryTotal(userQuery);
        List<User> users = userMapper.queryData(userQuery);
        // 批量回填角色（见下方 attachRoles），替代原先 resultMap 里逐行查询的 N+1
        attachRoles(users);
        pageList.setTotal(total);
        pageList.setRows(users);
        // 分页查询的数据
        return pageList;
    }

    /**
     * 一次性批量回填这一页用户的角色，避免 N+1。
     *
     * <p>原先 {@code UserMap} resultMap 里是
     * {@code <collection property="roles" column="id" select="getRoleByUserId"/>}，
     * MyBatis 会对分页结果的每一行再发一条 SQL —— 一页 N 个用户就是 1 + N 次查询
     * （分页上限放到 200 之后最多 201 次）。</p>
     *
     * <p>现在改为一条 {@code where user_id in (...)} 取回「用户-角色」对，
     * 再按 userId 分组塞回各个 User。与 {@code TagServiceImpl.tagsOfCultures}
     * 是同一套做法。</p>
     */
    private void attachRoles(List<User> users) {
        if (users == null || users.isEmpty()) return;

        List<Long> ids = new ArrayList<>();
        for (User u : users) {
            if (u != null && u.getId() != null) ids.add(u.getId());
        }
        if (ids.isEmpty()) return;

        Map<Long, List<Role>> grouped = new HashMap<>();
        List<Role> rows = userMapper.getRolesByUserIds(ids);
        if (rows != null) {
            for (Role r : rows) {
                if (r == null || r.getUserId() == null) continue;
                grouped.computeIfAbsent(r.getUserId(), k -> new ArrayList<>()).add(r);
            }
        }
        for (User u : users) {
            if (u == null) continue;
            List<Role> roles = grouped.get(u.getId());
            // 没有角色的用户给空列表而不是 null：前端 v-if="!(row.roles && row.roles.length)"
            // 两种都能处理，但列表语义更明确，也避免下游再判空
            u.setRoles(roles == null ? new ArrayList<>() : roles);
        }
    }

    //删除用户
    @Override
    public void deleteUser(Long id) {
        userMapper.deleteUser(id);
    }

    @Override
    public void batchRemove(List list) {
        userMapper.batchRemove(list);
    }

    @Override
    public void editSaveUser(User user) {
        userMapper.editSaveUser(user);
    }

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条用户：SQL 里 set 只有 deleted=0，
     * <b>status（1 启用 / 0 禁用）等字段保持原值</b>，不会把被禁用的账号意外启用。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restore(Long id) {
        if (id == null) {
            throw new BusinessException("参数错误：缺少用户 id");
        }
        return userMapper.restoreById(id);
    }

    /** 批量恢复用户（同样只改 deleted，不动 status）；ids 为空/全为 null 或超过 500 条时抛 BusinessException */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int restoreBatch(List<Long> ids) {
        return userMapper.restoreByIds(normalizeIds(ids));
    }

    /**
     * 批量 id 规整：去 null、去重，并校验「非空 + 不超过 500 条」。
     * 校验失败抛 BusinessException，由控制层转成 400 业务错误（而不是 500）。
     */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        List<Long> safeIds = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || safeIds.contains(id)) continue;
            safeIds.add(id);
        }
        if (safeIds.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        if (safeIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("一次最多操作 " + MAX_BATCH_SIZE + " 条");
        }
        return safeIds;
    }
}
