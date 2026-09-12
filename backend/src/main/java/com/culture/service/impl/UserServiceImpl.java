package com.culture.service.impl;

import com.culture.auth.service.BusinessException;
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
import java.util.List;

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
        pageList.setTotal(total);
        pageList.setRows(users);
        // 分页查询的数据
        return pageList;
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
