package com.culture.config;

import com.culture.entity.Permission;
import com.culture.entity.Role;
import com.culture.entity.User;
import com.culture.service.PermissionService;
import com.culture.service.RoleService;
import com.culture.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

/**
 * 后台登录用户加载：支持用「用户名」或「邮箱」登录。
 * 找不到用户时抛出 UsernameNotFoundException（不能返回 null，否则 Spring Security 会报内部错误）。
 */
@Component
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserService userService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private PermissionService permissionService;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 优先按用户名查，其次按邮箱查（兼容两种登录方式）
        User user = userService.findUserByUserName(username);
        if (user == null) {
            user = userService.findByEmail(username);
        }
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        //查询角色集合
        Long userid = user.getId();
        List<Role> roles = roleService.listRoleByUserId(userid);
        //查询权限集合
        List<Permission> permissions = permissionService.listPermissionByUserId(userid);
        //构建所有权限集合==ROLE_角色+权限
        HashSet<GrantedAuthority> authorities = new HashSet<GrantedAuthority>();
        for (Role role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
        }
        for (Permission permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission.getName()));
        }
        return new UserSecurity(user, authorities);
    }
}
