package com.culture.service.impl;


import com.culture.entity.Menu;
import com.culture.mapper.MenuMapper;
import com.culture.service.MenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
//声明式事务管理
@Transactional(propagation = Propagation.SUPPORTS,readOnly = true)
public class MenuServiceImpl implements MenuService {

    @Autowired
    private MenuMapper menuMapper;


    @Override
    public List<Menu> findAll(Long loginUserId) {
        return menuMapper.findAll(loginUserId);
    }

    @Override
    public List<Menu> queryAllMenu() {
        return menuMapper.queryAllMenu();
    }
}
