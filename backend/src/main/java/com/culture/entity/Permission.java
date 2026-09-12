package com.culture.entity;

import lombok.Data;

@Data
public class Permission {

    private Long id;
    private String name;
    private String title;
    private Long pid;
    private Long menuId;
}
