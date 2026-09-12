package com.culture.mapper;

import com.culture.entity.MailAccount;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 发件账号 Mapper（sys_mail_account）。
 */
@Mapper
public interface MailAccountMapper {

    /** 所有账号（按切换顺序） */
    @Select("select * from sys_mail_account order by sort_order, id")
    List<MailAccount> listAccounts();

    /** 启用的账号（按切换顺序） */
    @Select("select * from sys_mail_account where enabled=1 order by sort_order, id")
    List<MailAccount> listEnabledAccounts();

    @Select("select * from sys_mail_account where id=#{id}")
    MailAccount findById(@Param("id") Long id);

    @Insert("insert into sys_mail_account(username,password,daily_limit,sent_today,sent_date,enabled,sort_order) " +
            "values(#{username},#{password},#{dailyLimit},0,NULL,#{enabled},#{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAccount(MailAccount a);

    /** 更新账号（密码字段由调用方决定是否覆盖） */
    @Update("update sys_mail_account set username=#{username}, password=#{password}, " +
            "daily_limit=#{dailyLimit}, enabled=#{enabled}, sort_order=#{sortOrder}, updated_at=now() where id=#{id}")
    int updateAccount(MailAccount a);

    @Delete("delete from sys_mail_account where id=#{id}")
    int deleteAccount(@Param("id") Long id);

    /** 今日已发送 +1 */
    @Update("update sys_mail_account set sent_today=sent_today+1 where id=#{id}")
    int incrementSent(@Param("id") Long id);

    /** 跨天重置今日计数 */
    @Update("update sys_mail_account set sent_today=0, sent_date=#{date} where id=#{id}")
    int resetSent(@Param("id") Long id, @Param("date") java.sql.Date date);
}
