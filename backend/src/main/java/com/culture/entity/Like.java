package com.culture.entity;

import lombok.Data;

import java.util.Date;

/**
 * @description: Likes
 */
@Data
public class Like extends BaseEntity{
    private Long uid;//用户id
    private User user;
    private Long bid;//id
//    private Date createTime;
    private Culture culture;
}
