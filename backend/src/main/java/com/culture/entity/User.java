package com.culture.entity;

import cn.afterturn.easypoi.excel.annotation.Excel;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 用户实体（对应新库 sys_user 表）。
 * 注意：Java 属性名保持旧名（headImg/tel/createTime 等），
 * 由 Mapper XML 把新库列(avatar/phone/created_at)映射回这些属性，
 * 从而前端模板与 JS 无需改动。
 */
@Data
public class User {
    @Excel(name = "编号", width = 25)
    private Long id;

    @Excel(name = "姓名", width = 25)
    private String username;

    /** 明文密码（仅写入时使用；@JsonIgnore 保证响应中不泄露密码哈希） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String password;

    @Excel(name = "邮箱", width = 25)
    private String email;

    @Excel(name = "电话", width = 25)
    private String tel;

    @Excel(name = "创建时间", width = 25, format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 性别：false=女(0) true=男(1) */
    private Boolean sex;

    /** 头像（映射自新库 avatar 列） */
    private String headImg;

    /** 昵称（新库新增，注册时默认取邮箱@前缀） */
    private String nickname;

    /** 状态：1启用 0禁用 */
    private Integer status;

    /** 注册来源：legacy=旧库迁移 email=邮箱注册 */
    private String source;

    /** 最后登录时间 */
    private Date lastLoginAt;

    /** 逻辑删除：0正常 1已删除 */
    private Integer deleted;

    /** 备注 */
    private String remark;

    /** 预留扩展字段（JSON字符串） */
    private String extra;

    /**
     * 头像缩略图（非表字段，A3 新增）。
     * 列表接口会把 {@link #headImg} 也指向它（前端 avatarUrl(headImg) 零改动即变小图）。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String headImgThumb;

    /**
     * 头像原图（非表字段，A3 新增）。
     * 仅在列表接口填充：此时 {@link #headImg} 已被替换为缩略图，原头像值保留在这里；
     * 个人资料等接口的 headImg 本身就是原图，不填充该字段。
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String headImgOriginal;

    private List<Role> roles = new ArrayList();//用户对应的角色集合

    public User() {
    }
}
