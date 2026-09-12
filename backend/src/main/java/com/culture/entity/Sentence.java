package com.culture.entity;

import lombok.Data;

import java.util.Date;
import java.util.List;


@Data
public class Sentence {
    private Long id;
    private String content;
    private Long createId;
    private String createName;
    private String createImg;
    private Date createTime;

    /**
     * 作者头像缩略图（非表字段，A3 新增）。
     * 前台列表接口会把 {@link #createImg} 也指向它（前端 avatarUrl(createImg) 零改动即变小图）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String createImgThumb;

    /**
     * 作者头像原图（非表字段，A3 新增）。
     * 仅在列表接口填充：此时 {@link #createImg} 已被替换为缩略图，原值保留在这里。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String createImgOriginal;

    List<User> userList;
}
