package com.culture.mapper;

import com.culture.entity.Menu;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MenuMapper {

    //根据登录用户 查询菜单
    List<Menu> findAll(Long loginUserid);
    //查询所有的菜单
    List<Menu> queryAllMenu();

}
