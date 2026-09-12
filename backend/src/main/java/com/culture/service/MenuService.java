package com.culture.service;

import com.culture.entity.Menu;

import java.util.List;

public interface MenuService {
    //查询所有的用户
    List<Menu> findAll(Long loginUserId);
    //查询所有的菜单
    List<Menu> queryAllMenu();

}
