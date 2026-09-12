package com.culture.entity;

import lombok.Data;

import java.util.Date;

@Data
public class Announcement {
    private Long id;
    private String announcement;
    private Date createTime;
}
