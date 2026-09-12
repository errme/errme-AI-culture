package com.culture.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Set;

//登录用户封装
public class UserSecurity extends User {

    com.culture.entity.User loginUser;

    public UserSecurity(com.culture.entity.User user, Set<GrantedAuthority> authorities) {
        super(user.getUsername(), user.getPassword(), true, true, true, true, authorities);
        this.loginUser = user;
    }

    public com.culture.entity.User getLoginUser() {
        return loginUser;
    }

    public void setLoginUser(com.culture.entity.User loginUser) {
        this.loginUser = loginUser;
    }
}
