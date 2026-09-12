package com.culture.service;

import com.culture.entity.User;
import com.culture.query.UserQuery;
import com.culture.util.PageList;

import java.util.Date;
import java.util.List;

/**
 * 用户服务：在原管理功能基础上，补充 auth 模块（邮箱登录）所需方法。
 */
public interface UserService {

    /** 根据用户名查询用户 */
    User findUserByUserName(String username);

    /** 根据邮箱查询用户（auth 登录用） */
    User findByEmail(String email);

    /** 根据主键查询用户（auth /me 用） */
    User findById(Long id);

    /** 更新最后登录时间 */
    int updateLastLoginAt(Long id, Date time);

    /** 更新密码（auth 重置密码用） */
    int updatePassword(Long id, String password);

    String findUserById(Long id);

    /** 查询所有用户 */
    List<User> queryAll();

    /** 添加用户 */
    Integer addUser(User user);

    /** 根据用户id更新头像 */
    void updateUserHeadImg(User user);

    /** 分页方法 */
    PageList listpage(UserQuery userQuery);

    /** 删除用户 */
    void deleteUser(Long id);

    /** 批量删除 */
    void batchRemove(List list);

    /** 修改保存用户 */
    void editSaveUser(User user);

    // ===================== 删除可撤销：恢复（增量追加） =====================

    /**
     * 恢复单条被逻辑删除的用户：<b>只把 deleted 改回 0，status（1 启用 / 0 禁用）等字段保持不变</b>，
     * 即删除前被禁用的账号恢复后仍然是禁用状态，不会被意外启用。
     * 权限校验与删除一致，由控制层（/api/admin/user/restore，要求管理员）负责。
     *
     * @param id 用户 id；为 null 时抛 BusinessException（控制层转 400）
     * @return 实际影响行数；0 表示该 id 不存在或本来就没被删除
     */
    int restore(Long id);

    /**
     * 批量恢复被逻辑删除的用户（同样只改 deleted，不动 status）。
     *
     * @param ids 用户 id 列表；为空/全为 null 或超过 500 条时抛 BusinessException（控制层转 400）
     * @return 实际影响行数
     */
    int restoreBatch(List<Long> ids);
}
