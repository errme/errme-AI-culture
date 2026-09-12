package com.culture.mapper;

import com.culture.entity.User;
import com.culture.query.UserQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 用户 Mapper（新库 sys_user）。
 * 新增 findByEmail / findById / updateLastLoginAt 供 auth 模块（JWT 登录）使用。
 */
@Mapper
public interface UserMapper {

    /** 按用户名查询（未逻辑删除） */
    User findUserByUserName(String username);

    /** 按邮箱查询（登录认证主入口，邮箱唯一） */
    User findByEmail(String email);

    /** 按主键查询 */
    User findById(Long id);

    /** 更新最后登录时间 */
    int updateLastLoginAt(@Param("id") Long id, @Param("time") Date time);

    /** 更新密码（auth 重置密码用，密码已 BCrypt 加密） */
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    @Select("select username from sys_user where id=#{id}")
    String findUserById(Long id);

    @Select("select avatar from sys_user where id=#{id}")
    String findUserByImg(Long id);

    /** 查询所有用户 */
    List<User> queryAll();

    @Select("select id, username, password_hash password, email, phone tel, sex, avatar headImg, " +
            "nickname, status, source, last_login_at lastLoginAt, created_at createTime, " +
            "updated_at updatedAt, deleted, remark, extra from sys_user")
    List<User> findAll();

    /** 新增用户（auth 注册 / 后台添加共用） */
    Integer addUser(User user);

    /** 更新头像（新库 avatar 列） */
    void updateUserHeadImg(User user);

    Long queryTotal(UserQuery userQuery);

    List<User> queryData(UserQuery userQuery);

    /** 逻辑删除 */
    void deleteUser(Long id);

    /** 批量逻辑删除 */
    void batchRemove(List ids);

    void editSaveUser(User user);

    // ===================== 删除可撤销：恢复（增量追加，SQL 见 UserMapper.xml） =====================

    /**
     * 恢复单条被逻辑删除的用户：<b>只把 deleted 改回 0，不动 status（启用/禁用）等任何其它字段</b>。
     * 即：用户被删除前是「禁用」的，恢复后仍是禁用，不会被意外启用。
     * 返回实际影响行数；where 带 deleted=1，对正常用户调用返回 0。
     */
    int restoreById(@Param("id") Long id);

    /**
     * 批量恢复被逻辑删除的用户（同样只改 deleted，不动 status）；
     * 只影响 deleted=1 的行，返回实际影响行数（ids 由 Service 保证非空且 ≤ 500）。
     */
    int restoreByIds(@Param("ids") List<Long> ids);
}
